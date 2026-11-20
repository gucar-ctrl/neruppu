/*
 * Copyright (C) 2026 thamizh-root
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.havenapp.neruppu.data.telegram

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calls to the Telegram Bot API. Methods that send something take an optional [chatId]:
 * when it is null the message goes to the owner's chat (the one paired with the app).
 */
@Singleton
class TelegramApiClient @Inject constructor(
    private val configStore: TelegramConfigStore
) {
        private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json() }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
        }
    }

    private val baseUrl: String
        get() = "https://api.telegram.org/bot${configStore.botToken}"

    private fun target(chatId: String?): String = chatId ?: configStore.chatId

    /**
     * Sends the same alert to the owner and to every invited person.
     * Succeeds when at least one chat received it. A person who blocked the bot
     * (or whose account no longer exists) is removed from the list.
     */
    suspend fun broadcast(send: suspend (chatId: String) -> Result<Unit>): Result<Unit> {
        val owner = configStore.chatId.trim()
        var delivered = false
        var firstError: Throwable? = null
        for (id in configStore.alertChatIds()) {
            val result = send(id)
            if (result.isSuccess) {
                delivered = true
            } else {
                val error = result.exceptionOrNull()
                if (firstError == null) firstError = error
                if (id != owner && isGoneChat(error)) configStore.removeRecipient(id)
            }
        }
        if (delivered) return Result.success(Unit)
        return Result.failure(firstError ?: IllegalStateException("No Telegram chat configured"))
    }

    private fun isGoneChat(error: Throwable?): Boolean {
        val message = error?.message?.lowercase() ?: return false
        return message.contains("blocked by the user") ||
            message.contains("user is deactivated") ||
            message.contains("chat not found")
    }

    suspend fun sendMessage(text: String, chatId: String? = null): Result<Unit> = runCatching {
        val response: HttpResponse = client.post("$baseUrl/sendMessage") {
            val formData = formData {
                append("chat_id", target(chatId))
                append("text", text)
                append("parse_mode", "HTML")
            }
            setBody(MultiPartFormDataContent(formData))
        }
        if (response.status.value !in 200..299) {
            error("Telegram sendMessage failed: ${response.status} ${response.bodyAsText().take(200)}")
        }
    }

    suspend fun sendPhoto(bytes: ByteArray, caption: String, chatId: String? = null): Result<Unit> = runCatching {
        val response: HttpResponse = client.post("$baseUrl/sendPhoto") {
            val formData = formData {
                append("chat_id", target(chatId))
                append("caption", caption)
                append("photo", bytes, Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"alert.jpg\"")
                })
            }
            setBody(MultiPartFormDataContent(formData))
        }
        if (response.status.value !in 200..299) {
            error("Telegram sendPhoto failed: ${response.status} ${response.bodyAsText().take(200)}")
        }
    }

    /** Sends a photo and returns its Telegram message_id, so the picture can be replaced later (live view). */
    suspend fun sendPhotoForId(bytes: ByteArray, caption: String): Result<Long> = runCatching {
        val response: HttpResponse = client.post("$baseUrl/sendPhoto") {
            val formData = formData {
                append("chat_id", configStore.chatId)
                append("caption", caption)
                append("photo", bytes, Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"live.jpg\"")
                })
            }
            setBody(MultiPartFormDataContent(formData))
        }
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) {
            error("Telegram sendPhoto failed: ${response.status} ${body.take(200)}")
        }
        org.json.JSONObject(body).getJSONObject("result").getLong("message_id")
    }

    /** Replaces the picture and caption of a photo message the bot sent earlier. */
    suspend fun editPhoto(messageId: Long, bytes: ByteArray, caption: String): Result<Unit> = runCatching {
        val media = org.json.JSONObject()
            .put("type", "photo")
            .put("media", "attach://frame")
            .put("caption", caption)
        val response: HttpResponse = client.post("$baseUrl/editMessageMedia") {
            val formData = formData {
                append("chat_id", configStore.chatId)
                append("message_id", messageId.toString())
                append("media", media.toString())
                append("frame", bytes, Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"frame.jpg\"")
                })
            }
            setBody(MultiPartFormDataContent(formData))
        }
        if (response.status.value !in 200..299) {
            error("Telegram editMessageMedia failed: ${response.status} ${response.bodyAsText().take(200)}")
        }
    }

    /** Changes only the caption of a media message the bot sent earlier. */
    suspend fun editCaption(messageId: Long, caption: String): Result<Unit> = runCatching {
        val response: HttpResponse = client.post("$baseUrl/editMessageCaption") {
            val formData = formData {
                append("chat_id", configStore.chatId)
                append("message_id", messageId.toString())
                append("caption", caption)
            }
            setBody(MultiPartFormDataContent(formData))
        }
        if (response.status.value !in 200..299) {
            error("Telegram editMessageCaption failed: ${response.status} ${response.bodyAsText().take(200)}")
        }
    }

    /**
     * Registers the bot's slash-command menu: pairs of (command without the slash, description).
     * [scopeJson] is a BotCommandScope object; null means the default scope (everyone).
     */
    suspend fun setMyCommands(commands: List<Pair<String, String>>, scopeJson: String? = null): Result<Unit> = runCatching {
        val list = org.json.JSONArray()
        commands.forEach { (name, description) ->
            list.put(org.json.JSONObject().put("command", name).put("description", description))
        }
        val response: HttpResponse = client.post("$baseUrl/setMyCommands") {
            val formData = formData {
                append("commands", list.toString())
                if (scopeJson != null) append("scope", scopeJson)
            }
            setBody(MultiPartFormDataContent(formData))
        }
        if (response.status.value !in 200..299) {
            error("Telegram setMyCommands failed: ${response.status} ${response.bodyAsText().take(200)}")
        }
    }

    /** The bot's username without the @, needed to build t.me invite links. */
    suspend fun getBotUsername(): Result<String> = runCatching {
        val response: HttpResponse = client.get("$baseUrl/getMe")
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) {
            error("Telegram getMe failed: ${response.status}")
        }
        org.json.JSONObject(body).getJSONObject("result").getString("username")
    }

    suspend fun sendAudio(bytes: ByteArray, caption: String): Result<Unit> = runCatching {
        val response: HttpResponse = client.post("$baseUrl/sendAudio") {
            val formData = formData {
                append("chat_id", configStore.chatId)
                append("caption", caption)
                append("audio", bytes, Headers.build {
                    append(HttpHeaders.ContentType, "audio/mp4")
                    append(HttpHeaders.ContentDisposition, "filename=\"alert.mp4\"")
                })
            }
            setBody(MultiPartFormDataContent(formData))
        }
        if (response.status.value !in 200..299) {
            error("Telegram sendAudio failed: ${response.status}")
        }
    }

    suspend fun sendVoice(bytes: ByteArray, caption: String, chatId: String? = null): Result<Unit> = runCatching {
        val response: HttpResponse = client.post("$baseUrl/sendVoice") {
            val formData = formData {
                append("chat_id", target(chatId))
                append("caption", caption)
                append("parse_mode", "HTML")
                append("voice", bytes, Headers.build {
                    append(HttpHeaders.ContentType, "audio/mp4")
                    append(HttpHeaders.ContentDisposition, "filename=\"alert.mp4\"")
                })
            }
            setBody(MultiPartFormDataContent(formData))
        }
        if (response.status.value !in 200..299) {
            error("Telegram sendVoice failed: ${response.status} ${response.bodyAsText().take(200)}")
        }
    }

    suspend fun sendVideo(file: java.io.File, caption: String, chatId: String? = null): Result<Unit> = runCatching {
        val bytes = file.readBytes()
        val response: HttpResponse = client.post("$baseUrl/sendVideo") {
            val formData = formData {
                append("chat_id", target(chatId))
                append("caption", caption)
                append("supports_streaming", "true")
                append("video", bytes, Headers.build {
                    append(HttpHeaders.ContentType, "video/mp4")
                    append(HttpHeaders.ContentDisposition, "filename=\"clip.mp4\"")
                })
            }
            setBody(MultiPartFormDataContent(formData))
        }
        if (response.status.value !in 200..299) {
            error("Telegram sendVideo failed: ${response.status} ${response.bodyAsText().take(200)}")
        }
    }

    /** Sends 2-10 JPEG photos as a single album. */
    suspend fun sendMediaGroup(photos: List<ByteArray>, caption: String, chatId: String? = null): Result<Unit> = runCatching {
        require(photos.size in 2..10) { "sendMediaGroup needs 2-10 photos" }
        val media = org.json.JSONArray()
        photos.indices.forEach { i ->
            val item = org.json.JSONObject()
                .put("type", "photo")
                .put("media", "attach://p$i")
            if (i == 0) item.put("caption", caption)
            media.put(item)
        }
        val response: HttpResponse = client.post("$baseUrl/sendMediaGroup") {
            val formData = formData {
                append("chat_id", target(chatId))
                append("media", media.toString())
                photos.forEachIndexed { i, bytes ->
                    append("p$i", bytes, Headers.build {
                        append(HttpHeaders.ContentType, "image/jpeg")
                        append(HttpHeaders.ContentDisposition, "filename=\"p$i.jpg\"")
                    })
                }
            }
            setBody(MultiPartFormDataContent(formData))
        }
        if (response.status.value !in 200..299) {
            error("Telegram sendMediaGroup failed: ${response.status} ${response.bodyAsText().take(200)}")
        }
    }

    /** Sends plain text without HTML parsing (safe for arbitrary status strings). */
    suspend fun sendPlainMessage(text: String, chatId: String? = null): Result<Unit> = runCatching {
        val response: HttpResponse = client.post("$baseUrl/sendMessage") {
            val formData = formData {
                append("chat_id", target(chatId))
                append("text", text)
            }
            setBody(MultiPartFormDataContent(formData))
        }
        if (response.status.value !in 200..299) {
            error("Telegram sendMessage failed: ${response.status} ${response.bodyAsText().take(200)}")
        }
    }

    /** Long-polls for incoming bot updates and returns the raw JSON body. */
    suspend fun getUpdates(offset: Long, timeoutSec: Int): Result<String> = runCatching {
        val response: HttpResponse = client.get("$baseUrl/getUpdates?offset=$offset&timeout=$timeoutSec&allowed_updates=%5B%22message%22%5D")
        if (response.status.value !in 200..299) {
            error("Telegram getUpdates failed: ${response.status}")
        }
        response.bodyAsText()
    }

    suspend fun testConnection(): Result<Unit> = runCatching {
        val response: HttpResponse = client.get("$baseUrl/getMe")
        if (response.status.value !in 200..299) {
            error("Telegram connection failed: ${response.status}")
        }
    }
}

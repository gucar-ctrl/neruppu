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

package org.havenapp.neruppu.ui.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.havenapp.neruppu.domain.di.TelegramTransport
import org.havenapp.neruppu.domain.repository.TelegramConfigRepository
import org.havenapp.neruppu.domain.repository.MatrixConfigRepository
import org.havenapp.neruppu.domain.repository.AlertTargetRepository
import org.havenapp.neruppu.domain.transport.AlertTransport
import org.havenapp.neruppu.domain.model.AlertPayload
import org.havenapp.neruppu.domain.model.SensorType
import org.havenapp.neruppu.domain.model.AlertTarget
import javax.inject.Inject

private val TOKEN_REGEX = Regex("""\d{5,}:[A-Za-z0-9_-]{30,}""")

data class TelegramUiState(
    val botToken: String = "",
    val chatId: String = "",
    override val isSaved: Boolean = false,
    override val isLoading: Boolean = false,
    override val testStatus: TestStatus? = null
) : IntegrationConfigUiState

@HiltViewModel
class TelegramSettingsViewModel @Inject constructor(
    private val configRepository: TelegramConfigRepository,
    private val matrixConfigRepository: MatrixConfigRepository,
    private val alertTargetRepository: AlertTargetRepository,
    @TelegramTransport private val alertTransport: AlertTransport
) : ViewModel(), IntegrationConfigActions {

    private val _uiState = MutableStateFlow(
        TelegramUiState(
            botToken = configRepository.botToken,
            chatId = configRepository.chatId,
            isSaved = configRepository.isComplete
        )
    )
    val uiState: StateFlow<TelegramUiState> = _uiState.asStateFlow()

    // ---------------------------------------------------------------------
    // QR pairing: token from a QR code, chat ID detected automatically
    // ---------------------------------------------------------------------

    private val _pairingStatus = MutableStateFlow<String?>(null)
    val pairingStatus: StateFlow<String?> = _pairingStatus.asStateFlow()
    private var pairingJob: Job? = null

    fun onQrScanFailed(reason: String) {
        _pairingStatus.value = "\u274C $reason"
    }

    fun onQrScanned(raw: String) {
        val token = TOKEN_REGEX.find(raw.filterNot { it.isWhitespace() })?.value
        if (token == null) {
            _pairingStatus.value = "\u274C Il QR non contiene un token Telegram valido."
            return
        }
        _uiState.update { it.copy(botToken = token) }
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch { pair(token) }
    }

    fun cancelPairing() {
        pairingJob?.cancel()
        pairingJob = null
        _pairingStatus.value = null
    }

    private suspend fun pair(token: String) {
        _pairingStatus.value = "Verifico il token\u2026"
        val me = httpGet("https://api.telegram.org/bot$token/getMe", 15_000)
        val username = me?.let {
            runCatching { JSONObject(it).optJSONObject("result")?.optString("username") }.getOrNull()
        }
        if (username.isNullOrEmpty()) {
            _pairingStatus.value = "\u274C Token non valido o nessuna connessione. Rigenera il QR e riprova."
            return
        }
        _pairingStatus.value = "\u2705 Token valido (@$username).\n" +
            "Ora sul tuo telefono apri la chat con @$username e premi Avvia (o scrivi un messaggio qualsiasi). Attendo\u2026"

        val since = System.currentTimeMillis() / 1000 - 600 // accept messages from the last 10 minutes
        val deadline = System.currentTimeMillis() + 5 * 60_000
        var offset = 0L
        var chatId: Long? = null
        while (viewModelScope.isActive && chatId == null && System.currentTimeMillis() < deadline) {
            val body = httpGet("https://api.telegram.org/bot$token/getUpdates?offset=$offset&timeout=20", 35_000)
            if (body == null) { delay(3_000); continue }
            try {
                val updates = JSONObject(body).optJSONArray("result") ?: continue
                for (i in 0 until updates.length()) {
                    val u = updates.getJSONObject(i)
                    offset = maxOf(offset, u.getLong("update_id") + 1)
                    val msg = u.optJSONObject("message") ?: continue
                    val chat = msg.optJSONObject("chat") ?: continue
                    if (chat.optString("type") != "private") continue
                    if (msg.optLong("date") < since) continue
                    chatId = chat.getLong("id")
                }
            } catch (e: Exception) {
                delay(3_000)
            }
        }
        if (chatId == null) {
            _pairingStatus.value = "\u23F1 Nessun messaggio ricevuto da @$username. Premi Avvia nella chat del bot e scansiona di nuovo il QR."
            return
        }
        _uiState.update { it.copy(chatId = chatId.toString()) }
        saveConfig()
        // Acknowledge the update so it is not processed again, then confirm on Telegram.
        httpGet("https://api.telegram.org/bot$token/getUpdates?offset=$offset&timeout=0", 10_000)
        val text = URLEncoder.encode("\u2705 Faro collegato! Da ora ricevi qui gli avvisi.", "UTF-8")
        httpGet("https://api.telegram.org/bot$token/sendMessage?chat_id=$chatId&text=$text", 15_000)
        _pairingStatus.value = "\u2705 Collegato! Chat ID salvato. Ti ho scritto su Telegram per conferma."
    }

    private suspend fun httpGet(url: String, timeoutMs: Int): String? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = timeoutMs
            }
            try {
                if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun onTokenChange(value: String) {
        _uiState.update { it.copy(botToken = value) }
    }

    fun onChatIdChange(value: String) {
        _uiState.update { it.copy(chatId = value) }
    }

    override fun saveConfig() {
        configRepository.botToken = _uiState.value.botToken
        configRepository.chatId = _uiState.value.chatId
        _uiState.update { it.copy(isSaved = configRepository.isComplete) }
        if (configRepository.isComplete) {
            matrixConfigRepository.clear()
            alertTargetRepository.setActiveTarget(AlertTarget.TELEGRAM)
        }
    }

    override fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, testStatus = null) }
            val result = alertTransport.testConnection()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    testStatus = TestStatus(
                        success = result.isSuccess,
                        error = result.exceptionOrNull()?.message
                    )
                )
            }
        }
    }

    override fun sendMockMessage() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, testStatus = null) }
            val payload = AlertPayload.TextAlert(
                sensorType = SensorType.POWER,
                message = "Messaggio di prova di Faro: il collegamento funziona.",
                timestamp = System.currentTimeMillis()
            )
            val result = alertTransport.send(payload)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    testStatus = TestStatus(
                        success = result.isSuccess,
                        error = if (result.isSuccess) "Messaggio di prova inviato" else result.exceptionOrNull()?.message
                    )
                )
            }
        }
    }
}

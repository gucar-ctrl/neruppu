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

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import org.havenapp.neruppu.domain.repository.AlertTargetRepository
import org.havenapp.neruppu.domain.repository.TelegramConfigRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramConfigStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alertTargetRepository: AlertTargetRepository
) : TelegramConfigRepository {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "neruppu_telegram_config",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override var botToken: String
        get() = prefs.getString(KEY_BOT_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BOT_TOKEN, sanitizeToken(value)).apply()

    override var chatId: String
        get() = prefs.getString(KEY_CHAT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CHAT_ID, value.filterNot { it.isWhitespace() }).apply()

    /**
     * Removes whitespace/newlines and extracts the bare token if the user pasted
     * something like "bot123:ABC" or a full https://api.telegram.org/bot123:ABC/getMe URL.
     */
    private fun sanitizeToken(raw: String): String {
        val compact = raw.filterNot { it.isWhitespace() }
        return TOKEN_REGEX.find(compact)?.value ?: compact
    }

    override val isComplete: Boolean
        get() {
            val complete = botToken.isNotBlank() && chatId.isNotBlank()
            Log.d("TelegramConfigStore", "isComplete: $complete (Token: ${botToken.isNotBlank()}, ChatID: ${chatId.isNotBlank()})")
            return complete
        }

    override fun clear() {
        prefs.edit().clear().apply()
        alertTargetRepository.clearActiveTarget()
        Log.d("TelegramConfigStore", "Telegram config cleared, target reset to NONE")
    }

    /** People invited with /invita: they receive the alerts, never the commands. Pairs of (chat id, name). */
    val extraRecipients: List<Pair<String, String>>
        get() = (prefs.getString(KEY_EXTRA_RECIPIENTS, "") ?: "")
            .lines()
            .mapNotNull { line ->
                val id = line.substringBefore('|').trim()
                if (id.isEmpty()) null else id to line.substringAfter('|', "").trim()
            }

    /** Every chat that gets the alerts: the owner first, then the invited people. */
    fun alertChatIds(): List<String> =
        (listOf(chatId.trim()) + extraRecipients.map { it.first })
            .filter { it.isNotBlank() }
            .distinct()

    fun addRecipient(id: String, name: String) {
        val clean = name.replace('|', ' ').replace('\n', ' ').trim().take(64).ifBlank { "Senza nome" }
        saveRecipients(extraRecipients.filterNot { it.first == id } + (id to clean))
    }

    /** Removes an invited person and returns their name, or null if [id] was not in the list. */
    fun removeRecipient(id: String): String? {
        val list = extraRecipients
        val found = list.firstOrNull { it.first == id } ?: return null
        saveRecipients(list.filterNot { it.first == id })
        return found.second
    }

    private fun saveRecipients(list: List<Pair<String, String>>) {
        prefs.edit()
            .putString(KEY_EXTRA_RECIPIENTS, list.joinToString("\n") { "${it.first}|${it.second}" })
            .apply()
    }

    /** Creates a new invite code, valid once and for 24 hours. A new code replaces the previous one. */
    fun newInviteCode(): String {
        val random = java.security.SecureRandom()
        val code = (1..20).map { INVITE_ALPHABET[random.nextInt(INVITE_ALPHABET.length)] }.joinToString("")
        prefs.edit()
            .putString(KEY_INVITE_CODE, code)
            .putLong(KEY_INVITE_EXPIRES, System.currentTimeMillis() + INVITE_TTL_MS)
            .apply()
        return code
    }

    /** True for a valid, unexpired invite code; the code is then cancelled so it works only once. */
    fun consumeInviteCode(code: String): Boolean {
        val saved = prefs.getString(KEY_INVITE_CODE, "") ?: ""
        val expires = prefs.getLong(KEY_INVITE_EXPIRES, 0L)
        if (saved.isEmpty() || System.currentTimeMillis() > expires) return false
        val matches = java.security.MessageDigest.isEqual(saved.toByteArray(), code.trim().toByteArray())
        if (!matches) return false
        prefs.edit().remove(KEY_INVITE_CODE).remove(KEY_INVITE_EXPIRES).apply()
        return true
    }

    fun setAsActiveTarget() {
        alertTargetRepository.setActiveTarget(org.havenapp.neruppu.domain.model.AlertTarget.TELEGRAM)
    }

    companion object {
        private const val KEY_BOT_TOKEN = "bot_token"
        private const val KEY_CHAT_ID = "chat_id"
        private const val KEY_EXTRA_RECIPIENTS = "extra_recipients"
        private const val KEY_INVITE_CODE = "invite_code"
        private const val KEY_INVITE_EXPIRES = "invite_expires"
        private const val INVITE_TTL_MS = 24L * 60 * 60 * 1000
        private const val INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        private val TOKEN_REGEX = Regex("""\d{5,}:[A-Za-z0-9_-]{30,}""")
    }
}

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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import org.havenapp.neruppu.core.ui.theme.BackgroundPrimary
import org.havenapp.neruppu.core.ui.theme.BackgroundSecondary
import org.havenapp.neruppu.core.ui.theme.BorderTertiary
import org.havenapp.neruppu.core.ui.theme.NeruppuBlue
import org.havenapp.neruppu.core.ui.theme.NeruppuGreen
import org.havenapp.neruppu.core.ui.theme.NeruppuOrange
import org.havenapp.neruppu.core.ui.theme.NeruppuRed
import org.havenapp.neruppu.core.ui.theme.NeruppuTheme
import org.havenapp.neruppu.core.ui.theme.TextPrimary
import org.havenapp.neruppu.core.ui.theme.TextSecondary
import org.havenapp.neruppu.ui.components.ScreenHeader

@Composable
fun SettingsScreen(
    motionEnabled: Boolean,
    onMotionToggle: (Boolean) -> Unit,
    soundEnabled: Boolean,
    onSoundToggle: (Boolean) -> Unit,
    lightEnabled: Boolean,
    onLightToggle: (Boolean) -> Unit,
    motionSensitivity: Float,
    onMotionSensitivityChange: (Float) -> Unit,
    soundThreshold: Float,
    onSoundThresholdChange: (Float) -> Unit,
    pushAlerts: Boolean,
    onPushAlertsToggle: (Boolean) -> Unit,
    savePhotos: Boolean,
    onSavePhotosToggle: (Boolean) -> Unit,
    telegramViewModel: TelegramSettingsViewModel = hiltViewModel(),
    matrixViewModel: MatrixSettingsViewModel = hiltViewModel(),
    deletePasswordViewModel: DeletePasswordSettingsViewModel = hiltViewModel()
) {

    val telegramUiState by telegramViewModel.uiState.collectAsState()
    val matrixUiState by matrixViewModel.uiState.collectAsState()
    val deletePasswordUiState by deletePasswordViewModel.uiState.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    var showTelegramConfig by remember { mutableStateOf(false) }
    var showMatrixConfig by remember { mutableStateOf(false) }
    var showSetupPasswordDialog by remember { mutableStateOf(false) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var showRemovePasswordDialog by remember { mutableStateOf(false) }

    var setupNewPassword by remember { mutableStateOf("") }
    var setupConfirmPassword by remember { mutableStateOf("") }
    var changeCurrentPassword by remember { mutableStateOf("") }
    var changeNewPassword by remember { mutableStateOf("") }
    var changeConfirmPassword by remember { mutableStateOf("") }
    var removeCurrentPassword by remember { mutableStateOf("") }

    LaunchedEffect(deletePasswordUiState.successMessage, deletePasswordUiState.errorMessage) {
        val message = deletePasswordUiState.successMessage ?: deletePasswordUiState.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = message,
            duration = SnackbarDuration.Short
        )
        deletePasswordViewModel.clearMessages()
    }

    val telegramFields = listOf(
        IntegrationField(
            label = "Token del bot",
            placeholder = "123456:ABC-DEF...",
            value = telegramUiState.botToken,
            onValueChange = telegramViewModel::onTokenChange,
            isPassword = true
        ),
        IntegrationField(
            label = "Chat ID",
            placeholder = "123456789",
            value = telegramUiState.chatId,
            onValueChange = telegramViewModel::onChatIdChange
        )
    )

    val matrixFields = listOf(
        IntegrationField(
            label = "URL dell'homeserver",
            placeholder = "https://matrix.org",
            value = matrixUiState.homeserverUrl,
            onValueChange = matrixViewModel::onHomeserverChange
        ),
        IntegrationField(
            label = "ID della stanza",
            placeholder = "!abc123:matrix.org",
            value = matrixUiState.roomId,
            onValueChange = matrixViewModel::onRoomIdChange
        ),
        IntegrationField(
            label = "Token di accesso",
            placeholder = "syt_...",
            value = matrixUiState.accessToken,
            onValueChange = matrixViewModel::onTokenChange,
            isPassword = true
        )
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = BackgroundPrimary,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ScreenHeader(title = "Opzioni")

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                SectionTitle("Sensori")
                SettingsList {
                    SettingRow(
                        icon = Icons.Default.CameraAlt,
                        label = "Movimento",
                        subLabel = "Analisi della fotocamera",
                        checked = motionEnabled,
                        onCheckedChange = onMotionToggle
                    )
                    SettingRow(
                        icon = Icons.Default.Mic,
                        label = "Rumore",
                        subLabel = "Livello del microfono",
                        checked = soundEnabled,
                        onCheckedChange = onSoundToggle
                    )
                    SettingRow(
                        icon = Icons.Default.WbSunny,
                        label = "Luce",
                        subLabel = "Sensore di luce ambientale",
                        checked = lightEnabled,
                        onCheckedChange = onLightToggle
                    )
                }

                SectionTitle("Sensibilità")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BackgroundSecondary, RoundedCornerShape(12.dp))
                        .border(0.5.dp, BorderTertiary, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    SliderWrap(
                        label = "Soglia di movimento",
                        value = motionSensitivity,
                        onValueChange = onMotionSensitivityChange,
                        displayValue = if (motionSensitivity < 0.3f) "Bassa" else if (motionSensitivity < 0.7f) "Media" else "Alta",
                        color = NeruppuOrange
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    SliderWrap(
                        label = "Soglia del suono",
                        value = soundThreshold,
                        onValueChange = onSoundThresholdChange,
                        displayValue = "${(soundThreshold * 100).toInt()} dB",
                        color = NeruppuBlue
                    )
                }

                SectionTitle("Notifiche")
                SettingsList {
                    SettingRow(
                        icon = Icons.Default.Notifications,
                        label = "Notifiche sul telefono",
                        subLabel = "A ogni evento",
                        checked = pushAlerts,
                        onCheckedChange = onPushAlertsToggle
                    )
                    SettingRow(
                        icon = Icons.Default.Photo,
                        label = "Salva le foto",
                        subLabel = "Quando rileva un movimento",
                        checked = savePhotos,
                        onCheckedChange = onSavePhotosToggle
                    )
                }

                SectionTitle("Telegram: media e comandi")
                TelegramExtrasSection()

                SectionTitle("Cancellazione degli eventi")
                DeletePasswordManagementRow(
                    hasPassword = deletePasswordUiState.hasPassword,
                    onSetClick = { showSetupPasswordDialog = true },
                    onChangeClick = { showChangePasswordDialog = true },
                    onRemoveClick = { showRemovePasswordDialog = true }
                )

                SectionTitle("Collegamenti")
                SettingsList {
                    IntegrationSettingRow(
                        icon = Icons.Default.Send,
                        label = "Avvisi Telegram",
                        isConfigured = telegramUiState.isSaved,
                        onClick = { showTelegramConfig = true }
                    )
                    IntegrationSettingRow(
                        icon = Icons.Default.Message,
                        label = "Avvisi Matrix",
                        isConfigured = matrixUiState.isSaved,
                        onClick = { showMatrixConfig = true }
                    )
                }
            }
        }
    }

    PasswordManagementDialog(
        title = "Imposta la password di cancellazione",
        description = "Serve ogni volta che cancelli eventi e file.",
        fields = listOf(
            PasswordField(
                label = "Nuova password",
                value = setupNewPassword,
                onValueChange = { setupNewPassword = it },
                isPassword = true
            ),
            PasswordField(
                label = "Conferma la password",
                value = setupConfirmPassword,
                onValueChange = { setupConfirmPassword = it },
                isPassword = true
            )
        ),
        confirmText = "Imposta",
        isVisible = showSetupPasswordDialog,
        onConfirm = {
            deletePasswordViewModel.setPassword(setupNewPassword, setupConfirmPassword) {
                showSetupPasswordDialog = false
                setupNewPassword = ""
                setupConfirmPassword = ""
            }
        },
        onDismiss = {
            showSetupPasswordDialog = false
            setupNewPassword = ""
            setupConfirmPassword = ""
        }
    )

    PasswordManagementDialog(
        title = "Cambia la password di cancellazione",
        description = "Inserisci la password attuale, poi quella nuova.",
        fields = listOf(
            PasswordField(
                label = "Password attuale",
                value = changeCurrentPassword,
                onValueChange = { changeCurrentPassword = it },
                isPassword = true
            ),
            PasswordField(
                label = "Nuova password",
                value = changeNewPassword,
                onValueChange = { changeNewPassword = it },
                isPassword = true
            ),
            PasswordField(
                label = "Conferma la password",
                value = changeConfirmPassword,
                onValueChange = { changeConfirmPassword = it },
                isPassword = true
            )
        ),
        confirmText = "Cambia",
        isVisible = showChangePasswordDialog,
        onConfirm = {
            deletePasswordViewModel.changePassword(changeCurrentPassword, changeNewPassword, changeConfirmPassword) {
                showChangePasswordDialog = false
                changeCurrentPassword = ""
                changeNewPassword = ""
                changeConfirmPassword = ""
            }
        },
        onDismiss = {
            showChangePasswordDialog = false
            changeCurrentPassword = ""
            changeNewPassword = ""
            changeConfirmPassword = ""
        }
    )

    PasswordManagementDialog(
        title = "Rimuovi la password di cancellazione",
        description = "Senza password non potrai cancellare eventi e file finché non ne imposti una nuova.",
        fields = listOf(
            PasswordField(
                label = "Password attuale",
                value = removeCurrentPassword,
                onValueChange = { removeCurrentPassword = it },
                isPassword = true
            )
        ),
        confirmText = "Rimuovi la password",
        isVisible = showRemovePasswordDialog,
        onConfirm = {
            deletePasswordViewModel.removePassword(removeCurrentPassword) {
                showRemovePasswordDialog = false
                removeCurrentPassword = ""
            }
        },
        onDismiss = {
            showRemovePasswordDialog = false
            removeCurrentPassword = ""
        }
    )

    IntegrationConfigPopup(
        integrationType = IntegrationType.TELEGRAM,
        isVisible = showTelegramConfig,
        onDismiss = { showTelegramConfig = false },
        uiState = telegramUiState,
        actions = telegramViewModel,
        fields = telegramFields,
        descriptionText = "Inserisci i dati del bot Telegram per ricevere gli avvisi. Il bot si crea con @BotFather.",
        extraContent = { TelegramQrPairing(telegramViewModel) }
    )

    IntegrationConfigPopup(
        integrationType = IntegrationType.MATRIX,
        isVisible = showMatrixConfig,
        onDismiss = { showMatrixConfig = false },
        uiState = matrixUiState,
        actions = matrixViewModel,
        fields = matrixFields,
        descriptionText = "Inserisci i dati Matrix per ricevere gli avvisi su altri dispositivi."
    )
}

@Composable
fun DeletePasswordManagementRow(
    hasPassword: Boolean,
    onSetClick: () -> Unit,
    onChangeClick: () -> Unit,
    onRemoveClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BackgroundSecondary, RoundedCornerShape(12.dp))
            .border(0.5.dp, BorderTertiary, RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Shield,
            contentDescription = null,
            tint = if (hasPassword) NeruppuGreen else TextSecondary,
            modifier = Modifier.size(24.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Password di cancellazione",
                color = TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                if (hasPassword) "Richiesta prima di cancellare eventi e file" else "Imposta una password per poter cancellare",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onSetClick,
            enabled = !hasPassword,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NeruppuOrange)
        ) {
            Text("Imposta", fontWeight = FontWeight.SemiBold)
        }
        Button(
            onClick = onChangeClick,
            enabled = hasPassword,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NeruppuBlue)
        ) {
            Text("Cambia", fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(
            onClick = onRemoveClick,
            enabled = hasPassword,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeruppuRed),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (hasPassword) NeruppuRed else BorderTertiary)
        ) {
            Text("Rimuovi", fontWeight = FontWeight.SemiBold)
        }
    }
}

data class PasswordField(
    val label: String,
    val value: String,
    val onValueChange: (String) -> Unit,
    val isPassword: Boolean = true
)

@Composable
fun PasswordManagementDialog(
    title: String,
    description: String,
    fields: List<PasswordField>,
    confirmText: String,
    isVisible: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!isVisible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                fields.forEach { field ->
                    OutlinedTextField(
                        value = field.value,
                        onValueChange = field.onValueChange,
                        label = { Text(field.label, color = TextSecondary) },
                        singleLine = true,
                        visualTransformation = if (field.isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = NeruppuOrange,
                            focusedBorderColor = NeruppuOrange,
                            unfocusedBorderColor = BorderTertiary,
                            unfocusedContainerColor = Color.White,
                            focusedContainerColor = Color.White
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = fields.all { it.value.isNotBlank() },
                shape = RoundedCornerShape(8.dp),
                colors = if (confirmText == "Rimuovi la password") {
                    ButtonDefaults.buttonColors(containerColor = NeruppuRed)
                } else {
                    ButtonDefaults.buttonColors(containerColor = NeruppuOrange)
                }
            ) {
                Text(confirmText, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annulla", color = TextSecondary)
            }
        },
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun IntegrationSettingRow(
    icon: ImageVector,
    label: String,
    isConfigured: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(BackgroundSecondary, RoundedCornerShape(12.dp))
            .border(0.5.dp, BorderTertiary, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (isConfigured) NeruppuGreen else TextSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                if (isConfigured) "Configurato" else "Non configurato",
                color = if (isConfigured) NeruppuGreen else TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun TelegramExtrasSection() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("neruppu_prefs", android.content.Context.MODE_PRIVATE) }
    var clipMode by remember { mutableStateOf(prefs.getString("clip_mode", "photo") ?: "photo") }
    var remote by remember { mutableStateOf(prefs.getBoolean("remote_control", false)) }
    var photoRes by remember { mutableStateOf(prefs.getString("photo_resolution", "fhd") ?: "fhd") }
    var videoQ by remember { mutableStateOf(prefs.getString("video_quality", "fhd") ?: "fhd") }
    var alertPause by remember { mutableStateOf(prefs.getString("alert_pause_s", "30") ?: "30") }

    val options = listOf(
        "photo" to "Solo foto",
        "video" to "Foto + video di 10 s (Full HD)",
        "burst" to "Foto + sequenza di 4 foto in 10 s"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BackgroundSecondary, RoundedCornerShape(12.dp))
            .border(0.5.dp, BorderTertiary, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Videocam, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text("Dopo un rilevamento invia", color = TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        options.forEach { (key, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        clipMode = key
                        prefs.edit().putString("clip_mode", key).apply()
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = clipMode == key,
                    onClick = {
                        clipMode = key
                        prefs.edit().putString("clip_mode", key).apply()
                    },
                    colors = RadioButtonDefaults.colors(selectedColor = NeruppuOrange)
                )
                Text(label, color = TextPrimary, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            "Se il telefono non supporta il video, viene inviata la sequenza di foto.",
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(modifier = Modifier.height(12.dp))
        Text("Risoluzione foto", color = TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        PrefRadioGroup(
            options = listOf(
                "hd" to "HD 1280x720 (leggera, più veloce)",
                "fhd" to "Full HD 1920x1080",
                "max" to "Massima del sensore (file grandi, più lenta)"
            ),
            selected = photoRes,
            onSelect = {
                photoRes = it
                prefs.edit().putString("photo_resolution", it).apply()
            }
        )

        Spacer(modifier = Modifier.height(12.dp))
        Text("Qualità video", color = TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        PrefRadioGroup(
            options = listOf(
                "sd" to "SD 480p (file piccoli, invio rapido)",
                "hd" to "HD 720p",
                "fhd" to "Full HD 1080p"
            ),
            selected = videoQ,
            onSelect = {
                videoQ = it
                prefs.edit().putString("video_quality", it).apply()
            }
        )
        Text(
            "Se il telefono non supporta la risoluzione scelta usa la più vicina disponibile. " +
                "Quella effettiva si vede con il comando /stato.",
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(modifier = Modifier.height(12.dp))
        Text("Pausa tra gli avvisi", color = TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        PrefRadioGroup(
            options = listOf(
                "15" to "15 secondi",
                "30" to "30 secondi",
                "60" to "1 minuto",
                "120" to "2 minuti"
            ),
            selected = alertPause,
            onSelect = {
                alertPause = it
                prefs.edit().putString("alert_pause_s", it).apply()
            }
        )
        Text(
            "Dopo ogni avviso (foto ed eventuale video) non ne parte nessun altro per questo tempo, " +
                "qualunque sensore scatti.",
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
    }
    Spacer(modifier = Modifier.height(6.dp))
    SettingRow(
        icon = Icons.Default.Send,
        label = "Comandi da Telegram",
        subLabel = "/attiva /disattiva /stato /foto /live /stop /invita",
        checked = remote,
        onCheckedChange = {
            remote = it
            prefs.edit().putBoolean("remote_control", it).apply()
        }
    )
}

@Composable
fun PrefRadioGroup(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    options.forEach { (key, label) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(key) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected == key,
                onClick = { onSelect(key) },
                colors = RadioButtonDefaults.colors(selectedColor = NeruppuOrange)
            )
            Text(label, color = TextPrimary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        color = TextSecondary,
        style = MaterialTheme.typography.labelMedium,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}

@Composable
fun SettingsList(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

@Composable
fun SettingRow(
    icon: ImageVector,
    label: String,
    subLabel: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BackgroundSecondary, RoundedCornerShape(12.dp))
            .border(0.5.dp, BorderTertiary, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subLabel, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = NeruppuOrange,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = BackgroundSecondary,
                uncheckedBorderColor = BorderTertiary
            ),
            modifier = Modifier.scale(0.8f)
        )
    }
}

@Composable
fun SliderWrap(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    displayValue: String,
    color: Color
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Text(displayValue, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        }
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = onValueChange,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = color,
                inactiveTrackColor = BorderTertiary
            ),
            modifier = Modifier.height(24.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    NeruppuTheme {
        SettingsScreen(
            motionEnabled = true,
            onMotionToggle = {},
            soundEnabled = true,
            onSoundToggle = {},
            lightEnabled = false,
            onLightToggle = {},
            motionSensitivity = 0.5f,
            onMotionSensitivityChange = {},
            soundThreshold = 0.6f,
            onSoundThresholdChange = {},
            pushAlerts = true,
            onPushAlertsToggle = {},
            savePhotos = true,
            onSavePhotosToggle = {}
        )
    }
}

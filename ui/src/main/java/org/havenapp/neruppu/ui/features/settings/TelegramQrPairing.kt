package org.havenapp.neruppu.ui.features.settings

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.havenapp.neruppu.core.ui.theme.NeruppuOrange
import org.havenapp.neruppu.core.ui.theme.TextPrimary
import org.havenapp.neruppu.core.ui.theme.TextSecondary
import java.io.File

/** "Scansiona QR" button: photographs the QR shown on the user's phone and pairs Telegram automatically. */
@Composable
fun TelegramQrPairing(viewModel: TelegramSettingsViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by viewModel.pairingStatus.collectAsState()

    val photoFile = remember { File(File(context.cacheDir, "qr").apply { mkdirs() }, "scan.jpg") }
    val photoUri: Uri = remember {
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", photoFile)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (!ok) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.Default) { decodeQr(photoFile) }
            photoFile.delete()
            if (text != null) viewModel.onQrScanned(text)
            else viewModel.onQrScanFailed("QR non leggibile. Avvicina il telefono, evita riflessi e riprova.")
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = {
                try {
                    launcher.launch(photoUri)
                } catch (e: Exception) {
                    viewModel.onQrScanFailed("Impossibile aprire la fotocamera.")
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Scansiona QR del token", color = NeruppuOrange)
        }
        Text(
            "Scatta una foto al QR mostrato sul tuo telefono: token e chat ID si configurano da soli.",
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        status?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text(it, color = TextPrimary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

private fun decodeQr(file: File): String? {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
    bitmap.recycle()
    val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
    )
    val reader = QRCodeReader()
    for (bin in listOf(HybridBinarizer(source), GlobalHistogramBinarizer(source))) {
        try {
            return reader.decode(BinaryBitmap(bin), hints).text
        } catch (e: Exception) {
            reader.reset()
        }
    }
    return null
}

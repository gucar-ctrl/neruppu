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

package org.havenapp.neruppu.data.camera

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.havenapp.neruppu.data.camera.analyzer.MotionAnalyzer
import org.havenapp.neruppu.data.camera.analyzer.ContextBatteryLevelProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class CameraManager(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null

    /** When true, a VideoCapture use case is bound so short clips can be recorded. */
    var enableVideo: Boolean = false

    /** Becomes false if this device cannot bind photo + analysis + video together. */
    var videoSupported: Boolean = true
        private set

    private var currentVideoEnabled: Boolean? = null

    /** Photo resolution preset: "hd" (1280x720), "fhd" (1920x1080), "max" (highest the sensor offers). */
    var photoResolution: String = "fhd"

    /** Video quality preset: "sd" (480p), "hd" (720p), "fhd" (1080p). */
    var videoQuality: String = "fhd"

    private var currentPhotoResolution: String? = null
    private var currentVideoQuality: String? = null

    /** Resolution actually chosen by CameraX for photos, e.g. "1920x1080" (null if not bound). */
    val actualPhotoResolution: String?
        get() = imageCapture?.resolutionInfo?.resolution?.let { "${it.width}x${it.height}" }

    /** Resolution actually chosen by CameraX for video clips (null if video is not bound). */
    val actualVideoResolution: String?
        get() {
            if (videoCapture == null) return null
            val info = boundCameraInfo ?: return null
            return try {
                val supported = Recorder.getVideoCapabilities(info)
                    .getSupportedQualities(androidx.camera.core.DynamicRange.SDR)
                val order = listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
                val requested = when (videoQuality) {
                    "sd" -> Quality.SD
                    "hd" -> Quality.HD
                    else -> Quality.FHD
                }
                val idx = order.indexOf(requested)
                // Same rule as FallbackStrategy.lowerQualityOrHigherThan(requested)
                val pick = (order.drop(idx) + order.take(idx).reversed()).firstOrNull { it in supported }
                when (pick) {
                    Quality.UHD -> "2160p"
                    Quality.FHD -> "1080p"
                    Quality.HD -> "720p"
                    Quality.SD -> "480p"
                    else -> "sconosciuta"
                }
            } catch (e: Exception) {
                "sconosciuta"
            }
        }

    private var boundCamera: androidx.camera.core.Camera? = null
    private var torchEnabled = false
    private var boundCameraInfo: androidx.camera.core.CameraInfo? = null
    private var motionAnalyzer: MotionAnalyzer? = null
     
    private val mutex = Mutex()
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
     
    var isBound = false
    var currentCameraSide: Boolean? = null
    var currentLifecycleOwner: LifecycleOwner? = null

    var sensitivityPref = 15f
    private var onMotionDetectedCallback: ((Double) -> Unit)? = null

    

    fun getCameraProvider(callback: (ProcessCameraProvider) -> Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                callback(cameraProviderFuture.get())
            } catch (e: Exception) {
                Log.e("CameraManager", "Error getting camera provider", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    suspend fun capturePhoto(): Uri? = mutex.withLock {
        val capture = imageCapture ?: run {
            Log.e("CameraManager", "Cannot capture: imageCapture is null. Camera might not be bound to service.")
            return null
        }
        
        return suspendCancellableCoroutine { continuation ->
            val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                .format(System.currentTimeMillis())
            
            val outputDir = File(context.filesDir, "captures").apply { mkdirs() }
            val file = File(outputDir, "$name.jpg")
            val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

            Log.d("CameraManager", "Taking picture for security event: ${file.name}")
            capture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        Log.i("CameraManager", "PHOTO SAVED: ${file.absolutePath}")
                        if (continuation.isActive) continuation.resume(Uri.fromFile(file))
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e("CameraManager", "CAPTURE FAILED: ${exception.message}", exception)
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            )
        }
    }

    /** True when a video clip can be recorded right now. */
    val canRecordVideo: Boolean get() = isBound && videoCapture != null

    /**
     * Records a silent clip of [durationMs] using the bound VideoCapture use case.
     * Returns the MP4 file, or null if video is unavailable or recording failed.
     */
    @android.annotation.SuppressLint("MissingPermission")
    suspend fun recordClip(durationMs: Long): File? {
        val capture = videoCapture ?: return null
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        val outputDir = File(context.cacheDir, "clips").apply { mkdirs() }
        val file = File(outputDir, "$name.mp4")
        val finalized = CompletableDeferred<Boolean>()

        val recording = withContext(Dispatchers.Main) {
            try {
                capture.output
                    .prepareRecording(context, FileOutputOptions.Builder(file).build())
                    .start(ContextCompat.getMainExecutor(context)) { event ->
                        if (event is VideoRecordEvent.Finalize) {
                            if (event.hasError()) {
                                Log.e("CameraManager", "Clip finalize error: ${event.error}", event.cause)
                            }
                            // ERROR_NONE or a recoverable error still leaves a usable file in most cases
                            finalized.complete(file.exists() && file.length() > 0)
                        }
                    }
            } catch (e: Exception) {
                Log.e("CameraManager", "Unable to start clip recording", e)
                null
            }
        } ?: return null

        try {
            delay(durationMs)
        } catch (e: CancellationException) {
            // Cancelled (alarm switched off): stop the recorder and throw the clip away.
            withContext(NonCancellable + Dispatchers.Main) { recording.stop() }
            withContext(NonCancellable) {
                withTimeoutOrNull(5_000) { finalized.await() }
                file.delete()
            }
            throw e
        }
        withContext(Dispatchers.Main) { recording.stop() }
        val ok = withTimeoutOrNull(15_000) { finalized.await() } ?: false
        return if (ok) file else { file.delete(); null }
    }

    fun getDifferenceMap(): Nothing? = null

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        useFrontCamera: Boolean,
        sensitivity: Int = 15,
        onMotionDetected: (Double) -> Unit
    ) {
        if (isBound && currentCameraSide == useFrontCamera && currentLifecycleOwner == lifecycleOwner &&
            currentVideoEnabled == enableVideo && currentPhotoResolution == photoResolution &&
            currentVideoQuality == videoQuality) {
            Log.d("CameraManager", "Camera already bound to service lifecycle. Keeping active.")
            return
        }
        
        sensitivityPref = sensitivity.toFloat()
        onMotionDetectedCallback = onMotionDetected
        
        if (cameraExecutor.isShutdown) {
            cameraExecutor = Executors.newSingleThreadExecutor()
        }

        getCameraProvider { cameraProvider ->
            try {
                cameraProvider.unbindAll()
                
                val useCases = mutableListOf<androidx.camera.core.UseCase>()

                val photoStrategy = when (photoResolution) {
                    "max" -> ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
                    "hd" -> ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    else -> ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                }
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(photoStrategy)
                    .setAspectRatioStrategy(
                        if (photoResolution == "max") AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                        else AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                    )
                    .build()
                
                val analysisResolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(320, 240),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER
                        )
                    )
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setResolutionSelector(resolutionSelector)
                    .build()
                useCases.add(imageCapture!!)

                motionAnalyzer = MotionAnalyzer(
                    onMotionDetected,
                    sensitivity,
                    ContextBatteryLevelProvider(context)
                )
                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(analysisResolutionSelector)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, motionAnalyzer!!)
                    }
                useCases.add(imageAnalysis!!)

                val cameraSelector = if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                val wantVideo = enableVideo && videoSupported
                if (wantVideo) {
                    val recorder = Recorder.Builder()
                        .setQualitySelector(
                            run {
                                val q = when (videoQuality) {
                                    "sd" -> Quality.SD
                                    "hd" -> Quality.HD
                                    else -> Quality.FHD
                                }
                                QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(q))
                            }
                        )
                        .build()
                    videoCapture = VideoCapture.withOutput(recorder)
                } else {
                    videoCapture = null
                }

                try {
                    val all = if (videoCapture != null) useCases + videoCapture!! else useCases
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        *all.toTypedArray()
                    )
                    boundCamera = camera
                    boundCameraInfo = camera.cameraInfo
                } catch (e: Exception) {
                    if (videoCapture == null) throw e
                    // Device can't run photo + analysis + video together: fall back to photos only.
                    Log.w("CameraManager", "Video use case not supported with photo+analysis, falling back", e)
                    videoSupported = false
                    videoCapture = null
                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        *useCases.toTypedArray()
                    )
                    boundCamera = camera
                    boundCameraInfo = camera.cameraInfo
                }
                currentVideoEnabled = enableVideo
                currentPhotoResolution = photoResolution
                currentVideoQuality = videoQuality
                
                isBound = true
                torchEnabled = false
                currentCameraSide = useFrontCamera
                currentLifecycleOwner = lifecycleOwner
                Log.d("CameraManager", "CAMERA SYSTEM INITIALIZED BY SERVICE: Bound to $lifecycleOwner")
            } catch (exc: Exception) {
                Log.e("CameraManager", "CRITICAL: Service-side camera binding failed", exc)
                isBound = false
            }
        }
    }


    /** True while the torch is on, as far as this manager knows. */
    val isTorchOn: Boolean get() = torchEnabled

    /** True when the bound camera has a flash unit that can work as a torch. */
    fun hasTorch(): Boolean = boundCamera?.cameraInfo?.hasFlashUnit() == true

    /** Switches the torch of the bound camera. Returns false when no camera is bound. */
    fun setTorch(on: Boolean): Boolean {
        val camera = boundCamera ?: return false
        torchEnabled = on
        val request = camera.cameraControl.enableTorch(on)
        request.addListener({
            try {
                request.get()
            } catch (e: Exception) {
                // The torch did not switch: bring the flag back in line with the hardware.
                Log.w("CameraManager", "Torch request failed", e)
                if (boundCamera === camera) torchEnabled = !on
            }
        }, ContextCompat.getMainExecutor(context))
        return true
    }

    fun unbind() {
        getCameraProvider { cameraProvider ->
            cameraProvider.unbindAll()
            boundCamera = null
            torchEnabled = false
            isBound = false
            currentCameraSide = null
            currentLifecycleOwner = null
            imageCapture = null
            videoCapture = null
            currentVideoEnabled = null
            currentPhotoResolution = null
            currentVideoQuality = null
            imageAnalysis?.clearAnalyzer()
            imageAnalysis = null
            motionAnalyzer?.cleanup()
            motionAnalyzer = null
            cameraExecutor.shutdown()
            Log.d("CameraManager", "Camera system released")
        }
    }

    fun release() {
        if (!cameraExecutor.isShutdown) {
            cameraExecutor.shutdown()
        }
    }
}

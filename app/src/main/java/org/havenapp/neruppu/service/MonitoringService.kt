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

package org.havenapp.neruppu.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.havenapp.neruppu.BuildConfig
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleService
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.havenapp.neruppu.R
import org.havenapp.neruppu.data.audio.AudioRecorder
import org.havenapp.neruppu.data.camera.CameraManager
import org.havenapp.neruppu.data.camera.JpegSharpness
import org.havenapp.neruppu.data.storage.LocalMediaJanitor
import org.havenapp.neruppu.data.telegram.TelegramApiClient
import org.havenapp.neruppu.data.telegram.TelegramConfigStore
import org.havenapp.neruppu.data.worker.MediaUploadWorker
import org.json.JSONArray
import org.json.JSONObject
import org.havenapp.neruppu.data.sensors.*
import org.havenapp.neruppu.domain.model.Event
import org.havenapp.neruppu.domain.model.SensorType
import org.havenapp.neruppu.domain.model.SensorEvent
import org.havenapp.neruppu.domain.model.UploadStatus
import org.havenapp.neruppu.domain.model.AlertTarget
import org.havenapp.neruppu.domain.repository.AlertTargetRepository
import org.havenapp.neruppu.domain.repository.MediaUploadRepository
import org.havenapp.neruppu.domain.repository.SensorRepository
import org.havenapp.neruppu.domain.usecase.HandleSensorEventUseCase
import org.havenapp.neruppu.domain.usecase.AttachAudioToEventUseCase
import org.havenapp.neruppu.worker.EventNotificationWorker
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

@AndroidEntryPoint
class MonitoringService : LifecycleService() {

    @Inject
    lateinit var sensorRepository: SensorRepository

    @Inject
    lateinit var handleSensorEventUseCase: HandleSensorEventUseCase

    @Inject
    lateinit var attachAudioToEventUseCase: AttachAudioToEventUseCase

    @Inject
    lateinit var alertTargetRepository: AlertTargetRepository

    @Inject
    lateinit var mediaUploadRepository: MediaUploadRepository

    @Inject
    lateinit var cameraManager: CameraManager

    @Inject
    lateinit var telegramApiClient: TelegramApiClient

    @Inject
    lateinit var telegramConfigStore: TelegramConfigStore

    @Inject
    lateinit var localMediaJanitor: LocalMediaJanitor

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLockRefreshJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sensorsJob: Job? = null
    private var heartbeatJob: Job? = null
    
    private lateinit var accelerometerDriver: AccelerometerDriver
    private lateinit var microphoneDriver: MicrophoneDriver
    private lateinit var lightSensorDriver: LightSensorDriver
    private lateinit var significantMotionDriver: SignificantMotionDriver
    private lateinit var audioRecorder: AudioRecorder

    private val _motionLevel = MutableStateFlow(0.0)
    val motionLevel: StateFlow<Double> = _motionLevel

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel

    private val _lightLevel = MutableStateFlow(0f)
    val lightLevel: StateFlow<Float> = _lightLevel

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring

    private val _sessionStartTime = MutableStateFlow(0L)
    val sessionStartTime: StateFlow<Long> = _sessionStartTime

    private val _lastGuardedTime = MutableStateFlow(0L)
    val lastGuardedTime: StateFlow<Long> = _lastGuardedTime

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    inner class LocalBinder : Binder() {
        fun getService(): MonitoringService = this@MonitoringService
    }

    private val binder = LocalBinder()

    private var prefChangeJob: Job? = null
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        prefChangeJob?.cancel()
        prefChangeJob = serviceScope.launch {
            delay(500) // debounce
            when (key) {
                "motion_sensitivity", "use_front_camera" -> {
                    Log.d("MonitoringService", "Prefs changed ($key), re-binding camera...")
                    updatePrefValues()
                    startMonitoring()
                }
                "clip_mode", "photo_resolution", "video_quality" -> {
                    Log.d("MonitoringService", "Clip mode changed, re-binding camera...")
                    updatePrefValues()
                    if (_isMonitoring.value || _uiActive.value) startMonitoring()
                }
                "remote_control" -> {
                    updatePrefValues()
                    withContext(Dispatchers.Main) { updateRemoteControl() }
                }
                "alert_pause_s" -> updatePrefValues()
                "auto_torch" -> {
                    updatePrefValues()
                    if (!autoTorchPref && cameraManager.isTorchOn) {
                        withContext(Dispatchers.Main) { applyTorch(false, System.currentTimeMillis()) }
                    }
                }
                "audio_threshold", "save_photos" -> {
                    Log.d("MonitoringService", "Prefs changed ($key), updating values...")
                    updatePrefValues()
                    if (key == "audio_threshold") startMicrophoneMonitoring()
                }
            }
        }
    }

    private var savePhotosPref = true
    private var sensitivityPref = 15f
    private var useFrontCameraPref = false
    private var audioThresholdPref = 200f
    private var clipModePref = CLIP_PHOTO
    private var remoteControlPref = false
    private var photoResolutionPref = "fhd"
    private var videoQualityPref = "fhd"
    private var alertPausePref = 30
    private var autoTorchPref = true
    @Volatile
    private var lastTorchChangeMs = 0L
    private var lastLuxReading: Float? = null

    private fun updatePrefValues() {
        val prefs = getSharedPreferences("neruppu_prefs", MODE_PRIVATE)
        clipModePref = prefs.getString("clip_mode", CLIP_PHOTO) ?: CLIP_PHOTO
        remoteControlPref = prefs.getBoolean("remote_control", false)
        photoResolutionPref = prefs.getString("photo_resolution", "fhd") ?: "fhd"
        videoQualityPref = prefs.getString("video_quality", "fhd") ?: "fhd"
        alertPausePref = prefs.getString("alert_pause_s", "30")?.toIntOrNull()?.coerceIn(5, 600) ?: 30
        savePhotosPref = prefs.getBoolean("save_photos", true)
        sensitivityPref = prefs.getFloat("motion_sensitivity", 15f)
        useFrontCameraPref = prefs.getBoolean("use_front_camera", false)
        audioThresholdPref = prefs.getFloat("audio_threshold", 200f)
        autoTorchPref = prefs.getBoolean("auto_torch", true)
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return binder
    }

    private var isRecordingAudio = AtomicBoolean(false)
    private var lastNoiseTime = 0L
    private var lastEventTime = ConcurrentHashMap<SensorType, Long>()
    private var microphoneJob: Job? = null
    private var uiSensorsJob: Job? = null

    // Alert gate: one alert (photo + optional clip) at a time, then a pause with no new alerts.
    private val alertBusy = AtomicBoolean(false)
    private val alertPausedUntil = AtomicLong(0L)
    private var followUpJob: Job? = null
    private var liveJob: Job? = null

    // Not cancelled in onDestroy, so queued uploads are still discarded when the service stops.
    private val purgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val purgeMutex = Mutex()
    private val purgedTotal = AtomicInteger(0)

    private val alertStartedAt = AtomicLong(0L)

    /** An alert that holds the gate for too long (camera or network hang) must not block the alarm forever. */
    private fun alertStuck(now: Long): Boolean =
        alertBusy.get() && now - alertStartedAt.get() > ALERT_MAX_HOLD_MS

    /** True when a new alert may start: none running and the pause after the last one is over. */
    private fun alertGateOpen(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return (!alertBusy.get() || alertStuck(now)) && now >= alertPausedUntil.get()
    }

    /** Takes the alert gate. Returns false while another alert runs or the pause is still active. */
    private fun tryBeginAlert(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (alertStuck(now)) {
            Log.w("MonitoringService", "Alert gate was stuck for more than ${ALERT_MAX_HOLD_MS / 1000} s, releasing it")
            alertBusy.set(false)
        }
        if (!alertBusy.compareAndSet(false, true)) return false
        if (now < alertPausedUntil.get()) {
            alertBusy.set(false)
            return false
        }
        alertStartedAt.set(now)
        return true
    }

    /** Releases the gate and starts the pause during which no new alert is accepted. */
    private fun endAlert() {
        alertPausedUntil.set(SystemClock.elapsedRealtime() + alertPausePref * 1000L)
        alertBusy.set(false)
    }

    /**
     * Stops everything that could still send media: the follow-up clip, the live view and the
     * queued uploads. Safe to call from any thread.
     */
    private fun cancelAlerts() {
        followUpJob?.cancel()
        followUpJob = null
        liveJob?.cancel()
        liveJob = null
        isRecordingClip.set(false)
        alertBusy.set(false)
        alertPausedUntil.set(0L)
        try {
            WorkManager.getInstance(this).cancelUniqueWork(MediaUploadWorker.UNIQUE_WORK_NAME)
        } catch (e: Exception) {
            Log.w("MonitoringService", "Could not cancel the upload work", e)
        }
        purgeScope.launch { purgeQueuedUploads() }
    }

    /**
     * Marks queued uploads (pending, or failed and waiting for a retry) as discarded so the upload
     * worker never sends them. With [cutoff], only events older than that instant are discarded.
     */
    private suspend fun purgeQueuedUploads(cutoff: Instant? = null) = purgeMutex.withLock {
        var rounds = 0
        while (rounds++ < 20) {
            val batch = try {
                sensorRepository.getPendingUploadEvents(100)
            } catch (e: Exception) {
                Log.w("MonitoringService", "Could not read queued uploads", e)
                return@withLock
            }
            val targets = if (cutoff == null) batch else batch.filter { it.timestamp.isBefore(cutoff) }
            if (targets.isEmpty()) return@withLock
            for (event in targets) {
                try {
                    sensorRepository.updateEventUploadStatus(
                        event.id, UploadStatus.FAILED, event.uploadTarget, null, DISCARDED_REASON
                    )
                    purgedTotal.incrementAndGet()
                } catch (e: Exception) {
                    Log.w("MonitoringService", "Could not discard event ${event.id}", e)
                }
            }
            if (targets.size < batch.size) return@withLock
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("MonitoringService", "onCreate [PID: ${Process.myPid()}]")
        
        lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                Log.d("MonitoringService", "Lifecycle event: $event")
            }
        })

        createNotificationChannel()
        updateNotification()

        accelerometerDriver = AccelerometerDriver(this)
        microphoneDriver = MicrophoneDriver()
        lightSensorDriver = LightSensorDriver(this)
        significantMotionDriver = SignificantMotionDriver(this)
        audioRecorder = AudioRecorder(this)
        // Removes leftovers from older versions and keeps the local media folders within the cap.
        serviceScope.launch { localMediaJanitor.enforce(force = true) }
        
        updatePrefValues()
        val prefs = getSharedPreferences("neruppu_prefs", MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        _sessionStartTime.value = prefs.getLong("session_start_time", 0L)
        _lastGuardedTime.value = prefs.getLong("last_guarded_time", 0L)

        startHeartbeat()
        // Sensors will be started based on isMonitoring or UI visibility
        startMonitoringIfNeeded()
        updateRemoteControl()
    }

    private fun startHeartbeat() {
        if (!BuildConfig.DEBUG) return
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                Log.v("MonitoringService", "HEARTBEAT - Monitoring: ${_isMonitoring.value}, UI: ${_uiActive.value}, PID: ${Process.myPid()}")
                delay(10000)
            }
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Neruppu:MonitoringWakeLock")
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(10 * 60 * 1000L) // 10-minute timeout
            Log.d("MonitoringService", "WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d("MonitoringService", "WakeLock released")
        }
    }

    private fun startWakeLockIfNeeded() {
        if (_isMonitoring.value) {
            acquireWakeLock()
            startWakeLockRefresh()
        }
    }

    private fun stopWakeLockIfNeeded() {
        if (!_isMonitoring.value) {
            wakeLockRefreshJob?.cancel()
            wakeLockRefreshJob = null
            releaseWakeLock()
        }
    }

    private fun startWakeLockRefresh() {
        wakeLockRefreshJob?.cancel()
        wakeLockRefreshJob = serviceScope.launch {
            while (isActive && _isMonitoring.value) {
                delay(480 * 1000L) // Refresh every ~8 minutes (before 10-min timeout)
                if (_isMonitoring.value) {
                    val timeout = calculateWakeLockTimeout()
                    val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                    if (wakeLock?.isHeld == true) {
                        wakeLock?.release()
                        Log.d("MonitoringService", "WakeLock refreshed (adaptive timeout: ${timeout/1000/60}min)")
                    }
                    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Neruppu:MonitoringWakeLock")
                    wakeLock?.acquire(timeout)
                }
            }
        }
    }

    private fun calculateWakeLockTimeout(): Long {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val batteryLevel = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val chargingStatus = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS)
        val isCharging = chargingStatus == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                        chargingStatus == android.os.BatteryManager.BATTERY_STATUS_FULL

        return when {
            isCharging -> 10 * 60 * 1000L // 10 minutes when charging
            batteryLevel < 20 -> 5 * 60 * 1000L // 5 minutes when battery low
            batteryLevel < 50 -> 7 * 60 * 1000L // 7 minutes when medium battery
            else -> 10 * 60 * 1000L // 10 minutes when high battery
        }
    }

    private fun startMonitoringIfNeeded() {
        // We need the service to be "active" (startMonitoring) if:
        // 1. We are actually monitoring (background or foreground)
        // 2. The UI is active and needs a preview/sensor levels
        if (_isMonitoring.value || _uiActive.value) {
            startMonitoring()
        } else {
            stopMonitoring()
        }
    }

    private val _uiActive = MutableStateFlow(false)
    fun setUiActive(active: Boolean) {
        _uiActive.value = active
        if (active) {
            startMonitoring()
        } else {
            startMonitoringIfNeeded()
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        println("Neruppu: onStartCommand action=${intent?.action}")
        Log.d("MonitoringService", "onStartCommand: action=${intent?.action}")
        
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                Log.i("MonitoringService", "Stopping service via notification action")
                cancelAlerts()
                stopSelf()
            }
            "TRIGGER_MOTION_EVENT" -> {
                val level = intent.getDoubleExtra("motion_level", 0.0)
                serviceScope.launch {
                    handleEvent(SensorType.CAMERA_MOTION, "Movimento rilevato dalla fotocamera (livello ${String.format("%.2f", level)})")
                }
            }
        }
        
        return START_STICKY
    }

    fun toggleMonitoring() {
        val now = System.currentTimeMillis()
        _isMonitoring.value = !_isMonitoring.value
        if (_isMonitoring.value) {
            Log.d("MonitoringService", "Monitoring ACTIVATED")
            // Start clean: no pause left over and no stale uploads from earlier sessions.
            alertBusy.set(false)
            alertPausedUntil.set(0L)
            purgeScope.launch { purgeQueuedUploads(cutoff = Instant.ofEpochMilli(now)) }
            _sessionStartTime.value = now
            getSharedPreferences("neruppu_prefs", MODE_PRIVATE)
                .edit()
                .putLong("session_start_time", now)
                .remove("last_guarded_time")
                .apply()
            startWakeLockIfNeeded()
            updateNotification()
        } else {
            Log.d("MonitoringService", "Monitoring DEACTIVATED")
            // Stop everything in flight: follow-up clip, queued uploads, pause gate.
            cancelAlerts()
            _lastGuardedTime.value = now
            getSharedPreferences("neruppu_prefs", MODE_PRIVATE)
                .edit()
                .remove("session_start_time")
                .putLong("last_guarded_time", now)
                .apply()
            stopWakeLockIfNeeded()
            updateNotification()
        }
        startMonitoringIfNeeded()
    }

    /**
     * Turns the torch on when the room gets dark and off again once it is bright enough.
     * Two thresholds and a minimum time per state keep it from flickering.
     */
    private fun updateAutoTorch(lux: Float) {
        val now = System.currentTimeMillis()
        val torchOn = cameraManager.isTorchOn
        if (!autoTorchPref || !cameraManager.hasTorch()) {
            if (torchOn) applyTorch(false, now)
            return
        }
        if (now - lastTorchChangeMs < TORCH_MIN_STATE_MS) return
        if (!torchOn && lux < TORCH_ON_LUX) {
            applyTorch(true, now)
        } else if (torchOn && lux > TORCH_OFF_LUX) {
            applyTorch(false, now)
        }
    }

    private fun applyTorch(on: Boolean, now: Long) {
        Log.i("MonitoringService", "Torch ${if (on) "on" else "off"} (automatic, low light)")
        cameraManager.setTorch(on)
        lastTorchChangeMs = now
    }

    private fun stopMonitoring() {
        // The torch belongs to the guard: it goes off when monitoring stops.
        if (cameraManager.isTorchOn) applyTorch(false, System.currentTimeMillis())
        Log.d("MonitoringService", "stopMonitoring() called - Monitoring: ${_isMonitoring.value}, UI: ${_uiActive.value}")
        
        // ONLY unbind camera if NO ONE needs it (not monitoring AND UI is not visible)
        if (!_isMonitoring.value && !_uiActive.value) {
            Log.d("MonitoringService", "Releasing camera system (Idle & UI hidden)")
            cameraManager.unbind()
        } else {
            Log.d("MonitoringService", "Keeping camera bound for Preview/UI")
        }

        microphoneJob?.cancel()
        microphoneJob = null
        uiSensorsJob?.cancel()
        uiSensorsJob = null
        sensorsJob?.cancel()
        sensorsJob = null
    }


    private fun startMonitoring() {
        Log.d("MonitoringService", "startMonitoring() called - Monitoring: ${_isMonitoring.value}, UI: ${_uiActive.value}")
        
// Always bind camera if we are either Monitoring OR UI is active
        if (_isMonitoring.value || _uiActive.value) {
            cameraManager.enableVideo = clipModePref == CLIP_VIDEO
            cameraManager.photoResolution = photoResolutionPref
            cameraManager.videoQuality = videoQualityPref
            cameraManager.bindCamera(
                lifecycleOwner = this,
                useFrontCamera = useFrontCameraPref,
                sensitivity = (sensitivityPref * 30).toInt().coerceIn(5, 30),
                onMotionDetected = { level ->
                    _motionLevel.value = level
                    // Switching the torch changes the whole picture: that is not movement.
                    val torchSettling = System.currentTimeMillis() - lastTorchChangeMs < TORCH_ALERT_GRACE_MS
                    if (_isMonitoring.value && level > 15.0 && !torchSettling) {
                        Log.v("MonitoringService", "THRESHOLD EXCEEDED: $level")
                        serviceScope.launch {
                            handleEvent(SensorType.CAMERA_MOTION, "Movimento rilevato dalla fotocamera (livello ${String.format("%.2f", level)})")
                        }
                    }
                }
            )
        }

        if (sensorsJob?.isActive == true) {
            if (_isMonitoring.value) {
                Log.d("MonitoringService", "Sensors already running. Skipping re-initialization.")
                return
            }
            // Disarmed while the UI is still open: the alarm sensors must stop raising events.
            Log.d("MonitoringService", "Disarmed but sensors still running. Stopping them.")
            sensorsJob?.cancel()
            sensorsJob = null
            microphoneJob?.cancel()
            microphoneJob = null
        }
        
// Start sensor data flows only if we are monitoring
        // UI preview only needs the camera (handled above)
        if (_isMonitoring.value) {
            sensorsJob = serviceScope.launch {
                Log.v("MonitoringService", "Launching sensor flows...")
                
                val accelerometerFlow = accelerometerDriver.observeMotion()
                    .buffer()
                    .onEach { magnitude ->
                        Log.v("MonitoringService", "Motion event received")
                        if (_isMonitoring.value) {
                            serviceScope.launch {
                                handleEvent(SensorType.ACCELEROMETER, "Vibrazione rilevata (intensità $magnitude)")
                            }
                        }
                    }

                val lightFlow = lightSensorDriver.observeLightChanges()
                    .buffer()
                    .onEach { lux ->
                        _lightLevel.value = lux
                        lastLuxReading = lux
                        Log.v("MonitoringService", "Light event received")
                        // Light changes caused by the torch itself are not security events.
                        val torchCausedChange = System.currentTimeMillis() - lastTorchChangeMs < TORCH_ALERT_GRACE_MS
                        updateAutoTorch(lux)
                        if (_isMonitoring.value && !torchCausedChange) {
                            serviceScope.launch {
                                handleEvent(SensorType.LIGHT, "Cambio di luce rilevato ($lux lux)")
                            }
                        }
                    }

                launch { accelerometerFlow.collect() }
                launch { lightFlow.collect() }

                // The camera can bind after the first light reading, so the torch rule is re-checked periodically.
                launch {
                    while (isActive) {
                        delay(TORCH_RECHECK_MS)
                        lastLuxReading?.let { updateAutoTorch(it) }
                    }
                }
                
                // Significant Motion Monitoring (Ultra-low power wakeups)
                launch {
                    significantMotionDriver.observeSignificantMotion().collect {
                        if (_isMonitoring.value) {
                            handleEvent(SensorType.ACCELEROMETER, "Il telefono è stato mosso")
                        }
                    }
                }
                
                startMicrophoneMonitoring()
            }
        } else if (_uiActive.value) {
            // If UI is active but NOT monitoring, we still want to show levels
            // but we can be more conservative or just show camera
            uiSensorsJob?.cancel()
            uiSensorsJob = serviceScope.launch {
                launch {
                    lightSensorDriver.observeLightChanges()
                        .conflate()
                        .collect { _lightLevel.value = it }
                }
                launch {
                    microphoneDriver.observeNoise()
                        .conflate()
                        .collect { _audioLevel.value = it.toFloat() }
                }
            }
        }
    }

    private var fastBaseline = 0f
    private var slowBaseline = 0f

    private fun startMicrophoneMonitoring() {
        microphoneJob?.cancel()
        
        microphoneJob = serviceScope.launch {
            microphoneDriver.observeNoise()
                .conflate()
                .collect { amplitude ->
                    _audioLevel.value = amplitude.toFloat()
                    
                    val amp = amplitude.toFloat()
                    if (fastBaseline == 0f) {
                        fastBaseline = amp
                        slowBaseline = amp
                    }
                    
                    // Dual EMA: Fast adapts in ~0.2s (sensitive), Slow adapts in ~1000s (stable noise floor)
                    fastBaseline = 0.5f * fastBaseline + 0.5f * amp
                    slowBaseline = 0.999f * slowBaseline + 0.001f * amp
                    
                    val spike = fastBaseline - slowBaseline
                    
                    // Adjust sensitivity based on scale. 
                    val threshold = if (audioThresholdPref <= 1.0f) {
                        // Map slider 0.0-1.0 to RMS 50-2000
                        50f + (audioThresholdPref * 1950f)
                    } else {
                        audioThresholdPref
                    }

                    // CRITICAL: Check threshold BEFORE compareAndSet to avoid blocking
                    if (_isMonitoring.value && spike > threshold && alertGateOpen() &&
                        isRecordingAudio.compareAndSet(false, true)) {
                        Log.i("MonitoringService", "AUDIO TRIGGER (SPIKE): $spike (Threshold: $threshold, Fast: $fastBaseline, Slow: $slowBaseline)")
                        serviceScope.launch {
                            val eventId = handleEvent(SensorType.MICROPHONE, "Rumore rilevato (livello ${spike.toInt()})")
                            if (eventId == -1L) {
                                // Alert paused, or the event could not be saved: nothing to record.
                                isRecordingAudio.set(false)
                            } else {
                                startAudioRecording(eventId)
                            }
                        }
                    } else if (spike > (threshold * 0.5f)) {
                        // Log near-misses to help user calibrate
                        Log.v("MonitoringService", "Near-miss spike: $spike (Threshold: $threshold)")
                    }
                }
        }
    }

    private fun startAudioRecording(eventId: Long = -1) {
        // isRecordingAudio is already set to true in the trigger block
        
        val captureDuration = (getSharedPreferences("neruppu_prefs", MODE_PRIVATE)
            .getFloat("audio_capture_duration", 5f) * 1000).toLong()

        serviceScope.launch {
            var audioFile: File? = null
            try {
                // STOP microphone monitoring to release the AudioRecord instance
                Log.d("MonitoringService", "Stopping microphone monitoring to allow recording...")
                microphoneJob?.cancel()
                microphoneJob = null
                // Wait a bit for the driver to close the AudioRecord
                delay(400)

                Log.d("MonitoringService", "Capturing ${captureDuration/1000}s audio clip of the event...")
                audioFile = audioRecorder.startRecording()
                if (audioFile == null) {
                    Log.e("MonitoringService", "Failed to start AudioRecorder")
                    return@launch
                }

                delay(captureDuration)
            } catch (e: Exception) {
                Log.e("MonitoringService", "Audio recording failed", e)
            } finally {
                // stopRecording MUST be called to finalize the file and release MediaRecorder
                val uri = audioRecorder.stopRecording()
                
                // RESUME monitoring ASAP
                isRecordingAudio.set(false)
                Log.d("MonitoringService", "Resetting audio recording flag and restarting monitoring.")
                if (_isMonitoring.value) startMicrophoneMonitoring()
                
                // Process the captured file and alerts OUTSIDE the monitoring pause
                if (uri != null && audioFile != null && eventId != -1L) {
                    Log.i("MonitoringService", "AUDIO CLIP CAPTURED: $uri. Attaching to event $eventId in background...")
                    serviceScope.launch {
                        // Clean up temp file first - the attach will copy it to captures dir
                        val tempFile = audioFile
                        attachAudioToEventUseCase.execute(eventId, tempFile, System.currentTimeMillis())
                        // Delete temp file after attach (success or failure - it's copied to permanent storage)
                        try {
                            tempFile.delete()
                            Log.d("MonitoringService", "Temp audio file deleted: ${tempFile.absolutePath}")
                        } catch (e: Exception) {
                            Log.w("MonitoringService", "Failed to delete temp audio file", e)
                        }
                        // Disarmed while recording: the clip stays on the phone, nothing is sent.
                        if (_isMonitoring.value && alertTargetRepository.activeTarget != AlertTarget.NONE) {
                            mediaUploadRepository.enqueueUpload(eventId)
                        }
                    }
                }
            }
        }
    }

    private suspend fun handleEvent(type: SensorType, description: String): Long {
        println("Neruppu: handleEvent type=$type")
        val now = System.currentTimeMillis()
        val lastTime = lastEventTime[type] ?: 0L

        // Cooldown: 3 seconds for the same type, unless it's a microphone event which has its own logic
        if (type != SensorType.MICROPHONE && now - lastTime < 3000) {
            Log.d("MonitoringService", "Event suppressed by cooldown: $type")
            return -1
        }

        // Global gate: one alert at a time, then a pause with no new alert from any sensor.
        if (!tryBeginAlert()) {
            Log.d("MonitoringService", "Event ignored (alert running or in pause): $type")
            return -1
        }
        lastEventTime[type] = now

        // When a follow-up clip is started, it releases the gate once the clip has been sent.
        var followUpStarted = false
        try {
            Log.i("MonitoringService", ">>> TRIGGERING EVENT: $type - $description")

            // 1. Capture photo bytes if possible
            val imageBytes: ByteArray? = if (savePhotosPref) {
                try {
                    Log.d("MonitoringService", "Attempting to capture photo for event...")
                    val uri = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { cameraManager.capturePhoto() }
                    withContext(Dispatchers.IO) {
                        uri?.let { readAndDeleteFile(it) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("MonitoringService", "CRITICAL FAILURE: Photo capture threw exception", e)
                    null
                }
            } else null

            // 2. Prepare SensorEvent for Use Case
            val sensorEvent = SensorEvent(
                sensorType = type,
                description = description,
                timestamp = now,
                imageBytes = imageBytes
            )

            // 3. Orchestrate via Use Case (saves locally + logs in DB + sends Matrix alert)
            println("Neruppu: Calling HandleSensorEventUseCase for $type")
            Log.d("MonitoringService", "Calling HandleSensorEventUseCase for $type")
            val result = handleSensorEventUseCase.execute(sensorEvent)
            println("Neruppu: UseCase result=$result")
            val id = result.getOrDefault(-1L)

            if (result.isFailure) {
                Log.e("MonitoringService", "UseCase failed", result.exceptionOrNull())
            }

            if (id > 0 && type != SensorType.MICROPHONE && alertTargetRepository.activeTarget != AlertTarget.NONE) {
                serviceScope.launch {
                    try {
                        mediaUploadRepository.enqueueUpload(id)
                    } catch (e: Exception) {
                        Log.w("MonitoringService", "Failed to enqueue media upload for event $id", e)
                    }
                }
            }

            if (type != SensorType.MICROPHONE && clipModePref != CLIP_PHOTO &&
                alertTargetRepository.activeTarget == AlertTarget.TELEGRAM &&
                isRecordingClip.compareAndSet(false, true)) {
                followUpStarted = true
                followUpJob = serviceScope.launch {
                    try {
                        sendFollowUpClip(type)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("MonitoringService", "Follow-up clip failed", e)
                    } finally {
                        isRecordingClip.set(false)
                        // The pause starts once the video is out, unless the alert was cancelled.
                        if (isActive) endAlert()
                    }
                }
            }

            // Schedule deferred notification via WorkManager (Best Practice)
            val workData = Data.Builder()
                .putString("event_type", type.name)
                .putString("description", description)
                .build()

            val notificationWork = OneTimeWorkRequestBuilder<EventNotificationWorker>()
                .setInputData(workData)
                .build()

            WorkManager.getInstance(this).enqueueUniqueWork(
                "security_notification",
                androidx.work.ExistingWorkPolicy.REPLACE,
                notificationWork
            )

            Log.i("MonitoringService", "EVENT PROCESSED: $type (Local ID: $id)")
            return id
        } finally {
            if (!followUpStarted) endAlert()
        }
    }

    // ---------------------------------------------------------------------
    // Follow-up media (10 s video or burst of photos) sent to Telegram
    // ---------------------------------------------------------------------

    private val isRecordingClip = AtomicBoolean(false)

    private suspend fun sendFollowUpClip(type: SensorType) {
        val label = when (type) {
            SensorType.CAMERA_MOTION -> "movimento"
            SensorType.ACCELEROMETER -> "vibrazione"
            SensorType.LIGHT -> "cambio di luce"
            else -> type.name
        }
        if (clipModePref == CLIP_VIDEO && cameraManager.canRecordVideo) {
            val clip = cameraManager.recordClip(CLIP_DURATION_MS)
            if (clip != null) {
                telegramApiClient.broadcast { telegramApiClient.sendVideo(clip, "\uD83C\uDFA5 Video 10 s dopo il rilevamento ($label)", it) }
                    .onFailure { Log.e("MonitoringService", "sendVideo failed", it) }
                clip.delete()
                return
            }
            Log.w("MonitoringService", "Video clip unavailable, falling back to photo burst")
        }
        sendPhotoBurst(label)
    }

    private suspend fun sendPhotoBurst(label: String) {
        val captured = mutableListOf<ByteArray>()
        repeat(BURST_EXTRA_PHOTOS) {
            delay(CLIP_DURATION_MS / BURST_EXTRA_PHOTOS)
            readAndDeleteCapture()?.let { captured.add(it) }
        }
        // Sharpest frame first: Telegram shows the first photo of an album as its cover.
        val scored = captured.map { it to JpegSharpness.score(it) }.sortedByDescending { it.second }
        Log.d("MonitoringService", "Burst sharpness, best first: ${scored.joinToString { String.format(Locale.US, "%.1f", it.second) }}")
        val photos = scored.map { it.first }
        when {
            photos.size >= 2 -> telegramApiClient.broadcast {
                telegramApiClient.sendMediaGroup(photos, "\uD83D\uDCF8 Sequenza di 10 s dopo il rilevamento ($label)", it)
            }
            photos.size == 1 -> telegramApiClient.broadcast {
                telegramApiClient.sendPhoto(photos[0], "\uD83D\uDCF8 Foto successiva ($label)", it)
            }
            else -> Result.success(Unit)
        }.onFailure { Log.e("MonitoringService", "Photo burst send failed", it) }
    }

    /** Reads a capture from CameraManager and deletes the temporary file, so captures/ does not grow. */
    private fun readAndDeleteFile(uri: android.net.Uri): ByteArray? = try {
        contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } finally {
        uri.path?.let { File(it).delete() }
    }

    private suspend fun readAndDeleteCapture(live: Boolean = false): ByteArray? = try {
        val uri = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { cameraManager.capturePhoto() }
        withContext(Dispatchers.IO) {
            uri?.path?.let { path ->
                val f = File(path)
                val bytes = if (f.exists()) f.readBytes() else null
                f.delete()
                bytes?.let { if (live) shrinkForLive(it) else shrinkForTelegram(it) }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("MonitoringService", "Capture for Telegram failed", e)
        null
    }

    /** Smaller, upright frame for the live view: at most [LIVE_MAX_SIDE] px, JPEG quality 75. */
    private fun shrinkForLive(bytes: ByteArray): ByteArray {
        if (bytes.size <= LIVE_MAX_BYTES) return bytes
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val longSide = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longSide / (sample * 2) >= LIVE_MAX_SIDE) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return bytes
            // Decoding drops the EXIF rotation, so apply it to the pixels.
            val rotation = try {
                when (android.media.ExifInterface(java.io.ByteArrayInputStream(bytes)).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL
                )) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (e: Exception) {
                0f
            }
            val upright = if (rotation != 0f) {
                val m = android.graphics.Matrix().apply { postRotate(rotation) }
                android.graphics.Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
                    .also { if (it !== decoded) decoded.recycle() }
            } else decoded
            java.io.ByteArrayOutputStream().use { out ->
                upright.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
                upright.recycle()
                out.toByteArray()
            }
        } catch (e: Throwable) {
            Log.w("MonitoringService", "Unable to shrink live frame", e)
            bytes
        }
    }

    /** Telegram rejects photos over 10 MB: halve very large captures (e.g. 48 MP "max" mode). */
    private fun shrinkForTelegram(bytes: ByteArray): ByteArray {
        if (bytes.size <= 5_000_000) return bytes
        return try {
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return bytes
            java.io.ByteArrayOutputStream().use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                bmp.recycle()
                out.toByteArray()
            }
        } catch (e: Throwable) {
            Log.w("MonitoringService", "Unable to shrink photo", e)
            bytes
        }
    }

    // ---------------------------------------------------------------------
    // Telegram remote control: /attiva /disattiva /stato /foto /aiuto
    // ---------------------------------------------------------------------

    private var remoteJob: Job? = null
    private var remoteForegroundRequested = false

    private fun updateRemoteControl() {
        val shouldRun = remoteControlPref && telegramConfigStore.isComplete
        if (shouldRun) {
            if (!remoteForegroundRequested) {
                remoteForegroundRequested = true
                try {
                    // Make this a started service so it survives when the UI unbinds.
                    startForegroundService(Intent(this, MonitoringService::class.java))
                } catch (e: Exception) {
                    Log.w("MonitoringService", "Could not start service for remote control", e)
                }
            }
            updateNotification()
            if (remoteJob?.isActive != true) {
                remoteJob = serviceScope.launch { pollTelegramCommands() }
            }
        } else {
            remoteJob?.cancel()
            remoteJob = null
            remoteForegroundRequested = false
            updateNotification()
        }
    }

    private suspend fun pollTelegramCommands() {
        Log.i("MonitoringService", "Telegram remote control started")
        val ownerScope = ownerCommandScope()
        val ownerMenu = telegramApiClient.setMyCommands(BOT_COMMANDS, ownerScope)
        val menuResult = if (ownerScope != null && ownerMenu.isSuccess) {
            telegramApiClient.setMyCommands(GUEST_COMMANDS)
        } else {
            telegramApiClient.setMyCommands(BOT_COMMANDS)
        }
        menuResult.onFailure { Log.w("MonitoringService", "setMyCommands failed: ${it.message}") }
        var offset = 0L
        val ignoreBefore = System.currentTimeMillis() / 1000 - 10
        while (currentCoroutineContext().isActive) {
            val body = telegramApiClient.getUpdates(offset, 25).getOrElse {
                Log.w("MonitoringService", "getUpdates failed: ${it.message}")
                delay(15_000)
                null
            } ?: continue
            try {
                val updates = JSONObject(body).optJSONArray("result") ?: JSONArray()
                for (i in 0 until updates.length()) {
                    val update = updates.getJSONObject(i)
                    offset = maxOf(offset, update.getLong("update_id") + 1)
                    val msg = update.optJSONObject("message") ?: continue
                    val chatId = msg.optJSONObject("chat")?.optLong("id") ?: continue
                    if (msg.optLong("date") < ignoreBefore) continue
                    // Only the configured chat may control the device. Other chats can only
                    // accept an invite or leave the list of people who receive the alerts.
                    if (chatId.toString() != telegramConfigStore.chatId.trim()) {
                        handleGuestMessage(msg, chatId.toString())
                        continue
                    }
                    handleRemoteCommand(msg.optString("text"))
                }
            } catch (e: Exception) {
                Log.w("MonitoringService", "Bad getUpdates payload", e)
                delay(5_000)
            }
        }
    }

    private suspend fun handleRemoteCommand(rawText: String) {
        val cmd = rawText.trim().lowercase(Locale.ROOT).substringBefore(' ').substringBefore('@')
        Log.i("MonitoringService", "Remote command: $cmd")
        when (cmd) {
            "/attiva", "/arm" -> {
                if (!_isMonitoring.value) withContext(Dispatchers.Main) { toggleMonitoring() }
                reply("\u2705 Monitoraggio ATTIVO")
            }
            "/disattiva", "/disarm" -> {
                purgedTotal.set(0)
                val wasOn = _isMonitoring.value
                if (wasOn) withContext(Dispatchers.Main) { toggleMonitoring() } else cancelAlerts()
                // Wait for the queue clean-up so the reply can say how many uploads were dropped.
                purgeQueuedUploads()
                val dropped = purgedTotal.getAndSet(0)
                reply(
                    (if (wasOn) "\u23F8\uFE0F Monitoraggio SPENTO" else "\u23F8\uFE0F Monitoraggio gi\u00E0 spento") +
                        ". Invii fermati" + if (dropped > 0) " ($dropped in coda annullati)." else "."
                )
            }
            "/stato", "/status" -> reply(statusText())
            "/foto", "/photo" -> sendRemoteSnapshot()
            "/live" -> {
                val minutes = rawText.trim().split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()
                    ?.coerceIn(1, LIVE_MAX_MINUTES) ?: LIVE_DEFAULT_MINUTES
                startLive(minutes)
            }
            "/stop" -> {
                purgedTotal.set(0)
                val wasLive = liveJob?.isActive == true
                cancelAlerts()
                purgeQueuedUploads()
                // Stay quiet for the usual pause, so a still-moving scene does not restart the flow at once.
                alertPausedUntil.set(SystemClock.elapsedRealtime() + alertPausePref * 1000L)
                val dropped = purgedTotal.getAndSet(0)
                reply(
                    "\u23F9\uFE0F Fermato" + (if (wasLive) " il live" else "") +
                        (if (dropped > 0) ", $dropped invii in coda annullati" else "") +
                        ". Il monitoraggio resta " + (if (_isMonitoring.value) "attivo" else "spento") +
                        ", nessun nuovo avviso per $alertPausePref s."
                )
            }
            "/invita", "/invite" -> sendInvite()
            "/persone", "/people" -> reply(peopleText())
            else -> {
                if (cmd.startsWith("/rimuovi_")) removePerson(cmd.removePrefix("/rimuovi_")) else reply(HELP_TEXT)
            }
        }
    }

    private fun ownerCommandScope(): String? {
        val id = telegramConfigStore.chatId.trim().toLongOrNull() ?: return null
        return JSONObject().put("type", "chat").put("chat_id", id).toString()
    }

    /** /invita: a t.me link that adds one person to the alerts. Valid once, for 24 hours. */
    private suspend fun sendInvite() {
        val username = telegramApiClient.getBotUsername().getOrElse {
            reply("Non riesco a leggere il nome del bot. Riprova tra poco.")
            return
        }
        val code = telegramConfigStore.newInviteCode()
        reply(
            "Link d'invito (vale una volta e scade tra 24 ore):\n" +
                "https://t.me/$username?start=$code\n\n" +
                "Mandalo alla persona: lo apre, preme Avvia e da quel momento riceve foto e video degli avvisi. " +
                "Non può dare comandi."
        )
    }

    private fun peopleText(): String {
        val people = telegramConfigStore.extraRecipients
        if (people.isEmpty()) return "Gli avvisi arrivano solo a te. Con /invita aggiungi una persona."
        return buildString {
            appendLine("Ricevono gli avvisi:")
            appendLine("\u2022 Tu")
            people.forEach { (id, name) -> appendLine("\u2022 $name  /rimuovi_$id") }
            append("Tocca il comando accanto al nome per togliere quella persona. Con /invita ne aggiungi un'altra.")
        }
    }

    private suspend fun removePerson(id: String) {
        val name = telegramConfigStore.removeRecipient(id)
        if (name == null) {
            reply("Questa persona non è nell'elenco. /persone mostra chi riceve gli avvisi.")
            return
        }
        telegramApiClient.sendPlainMessage("Non ricevi più gli avvisi di Faro.", id)
        reply("$name non riceve più gli avvisi.")
    }

    /** Messages from chats other than the owner's: invite links and /esci only. */
    private suspend fun handleGuestMessage(msg: JSONObject, chatId: String) {
        val chat = msg.optJSONObject("chat") ?: return
        if (chat.optString("type") != "private") return
        val text = msg.optString("text").trim()
        val cmd = text.lowercase(Locale.ROOT).substringBefore(' ').substringBefore('@')
        val isRecipient = telegramConfigStore.extraRecipients.any { it.first == chatId }
        when {
            cmd == "/start" && text.contains(' ') -> {
                if (isRecipient) {
                    telegramApiClient.sendPlainMessage("Ricevi già gli avvisi di Faro. Per smettere scrivi /esci.", chatId)
                } else if (telegramConfigStore.consumeInviteCode(text.substringAfter(' '))) {
                    val name = guestName(msg, chat)
                    telegramConfigStore.addRecipient(chatId, name)
                    telegramApiClient.sendPlainMessage(
                        "\u2705 Da ora ricevi gli avvisi di Faro: una foto e poi un video o una sequenza di foto quando rileva qualcosa.\n" +
                            "Se è un'intrusione, chiama il 112.\nPer smettere scrivi /esci.",
                        chatId
                    )
                    reply("\u2705 $name ora riceve gli avvisi. /persone mostra chi li riceve.")
                } else {
                    telegramApiClient.sendPlainMessage(
                        "Questo invito non è valido o è scaduto. Chiedine uno nuovo a chi te l'ha mandato.",
                        chatId
                    )
                }
            }
            cmd == "/esci" && isRecipient -> {
                val name = telegramConfigStore.removeRecipient(chatId)
                telegramApiClient.sendPlainMessage("Non ricevi più gli avvisi di Faro.", chatId)
                if (name != null) reply("$name ha scritto /esci e non riceve più gli avvisi.")
            }
            isRecipient -> {
                telegramApiClient.sendPlainMessage("Qui ricevi solo gli avvisi. Per smettere scrivi /esci.", chatId)
            }
        }
    }

    private fun guestName(msg: JSONObject, chat: JSONObject): String {
        val from = msg.optJSONObject("from") ?: chat
        val full = listOf(from.optString("first_name"), from.optString("last_name"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        val username = from.optString("username").trim()
        return when {
            full.isNotEmpty() -> full
            username.isNotEmpty() -> "@$username"
            else -> "Senza nome"
        }
    }

    private suspend fun reply(text: String) {
        telegramApiClient.sendPlainMessage(text)
            .onFailure { Log.w("MonitoringService", "Reply failed", it) }
    }

    private fun statusText(): String {
        val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val mode = when (clipModePref) {
            CLIP_VIDEO -> if (cameraManager.videoSupported) "foto + video 10 s" else "foto + sequenza (video non supportato)"
            CLIP_BURST -> "foto + sequenza di foto"
            else -> "solo foto"
        }
        return buildString {
            appendLine(if (_isMonitoring.value) "\uD83D\uDFE2 Monitoraggio ATTIVO" else "\u26AA Monitoraggio SPENTO")
            appendLine("Batteria: $level%" + if (charging) " (in carica)" else " (NON in carica)")
            appendLine("Invio dopo rilevamento: $mode")
            appendLine("Pausa tra gli avvisi: $alertPausePref s")
            val others = telegramConfigStore.extraRecipients.size
            appendLine(
                "Ricevono gli avvisi: " + when (others) {
                    0 -> "solo tu"
                    1 -> "tu e un'altra persona (/persone)"
                    else -> "tu e altre $others persone (/persone)"
                }
            )
            if (liveJob?.isActive == true) appendLine("Live in corso (/stop per fermarlo)")
            append("Risoluzione foto: ${cameraManager.actualPhotoResolution ?: "fotocamera spenta"}")
            cameraManager.actualVideoResolution?.let { append("\nRisoluzione video: $it") }
        }
    }

    private suspend fun sendRemoteSnapshot() {
        val tempBind = !_isMonitoring.value && !_uiActive.value
        if (tempBind) {
            withContext(Dispatchers.Main) { setUiActive(true) }
            delay(2_500) // let the camera start and settle exposure
        }
        try {
            val bytes = readAndDeleteCapture()
            if (bytes != null) {
                telegramApiClient.sendPhoto(bytes, "\uD83D\uDCF7 Foto richiesta")
                    .onFailure { Log.w("MonitoringService", "Snapshot send failed", it) }
            } else {
                reply("Non sono riuscito a scattare la foto.")
            }
        } finally {
            if (tempBind) {
                withContext(Dispatchers.Main) { setUiActive(false) }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Quasi-live view: one Telegram photo that is edited in place every few seconds
    // ---------------------------------------------------------------------

    private fun startLive(minutes: Int) {
        val previous = liveJob
        liveJob = serviceScope.launch {
            // Let the previous session finish its clean-up (camera release) before starting a new one.
            previous?.cancelAndJoin()
            runLive(minutes)
        }
    }

    private suspend fun runLive(minutes: Int) {
        val tempBind = !_isMonitoring.value && !_uiActive.value
        if (tempBind) {
            withContext(Dispatchers.Main) { setUiActive(true) }
            delay(2_500) // let the camera start and settle exposure
        }
        acquireWakeLock()
        val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        var messageId: Long? = null
        var failures = 0
        val endAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        reply("\uD83D\uDD34 Live per $minutes min: la foto si aggiorna ogni ${LIVE_INTERVAL_MS / 1000} secondi circa. /stop per fermarlo.")
        try {
            while (currentCoroutineContext().isActive && SystemClock.elapsedRealtime() < endAt && failures < 5) {
                val started = SystemClock.elapsedRealtime()
                acquireWakeLock() // no-op while held; renews it after the 10-minute timeout
                if (isRecordingClip.get()) {
                    delay(1_000) // do not compete with the alert video
                    continue
                }
                val bytes = readAndDeleteCapture(live = true)
                if (bytes == null) {
                    failures++
                } else {
                    val caption = "\uD83D\uDD34 Live ${clock.format(Date())}"
                    val id = messageId
                    if (id == null) {
                        telegramApiClient.sendPhotoForId(bytes, caption)
                            .onSuccess { messageId = it; failures = 0 }
                            .onFailure { failures++; Log.w("MonitoringService", "Live first frame failed: ${it.message}") }
                    } else {
                        telegramApiClient.editPhoto(id, bytes, caption)
                            .onSuccess { failures = 0 }
                            .onFailure {
                                val why = it.message.orEmpty()
                                Log.w("MonitoringService", "Live edit failed: $why")
                                if (!why.contains("not modified", ignoreCase = true)) {
                                    failures++
                                    // The message was deleted by the user: start a fresh one next round.
                                    if (why.contains("not found", ignoreCase = true) ||
                                        why.contains("can't be edited", ignoreCase = true)) {
                                        messageId = null
                                    }
                                }
                            }
                    }
                }
                delay((LIVE_INTERVAL_MS - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(500))
            }
        } finally {
            withContext(NonCancellable) {
                if (failures >= 5) reply("Live interrotto: troppi errori di invio.")
                messageId?.let {
                    telegramApiClient.editCaption(it, "\u23F9\uFE0F Live terminato alle ${clock.format(Date())}")
                }
                if (tempBind) withContext(Dispatchers.Main) { setUiActive(false) }
                stopWakeLockIfNeeded()
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Monitoring Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        val notification = createNotification()
        if (_isMonitoring.value || (remoteControlPref && telegramConfigStore.isComplete)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                           ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Still show a simple notification if UI is active? 
            // Or just remove it. Let's remove it for now when not monitoring.
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, MonitoringService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (_isMonitoring.value) {
            "Sorveglianza attiva"
        } else if (remoteControlPref) {
            "Spento, in attesa di comandi da Telegram"
        } else {
            "Spento. Apri Faro per attivare la sorveglianza."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Faro")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_security)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Chiudi Faro", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        Log.d("MonitoringService", "onDestroy [PID: ${Process.myPid()}]")
        super.onDestroy()
        getSharedPreferences("neruppu_prefs", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        stopWakeLockIfNeeded()
        heartbeatJob?.cancel()
        remoteJob?.cancel()
        sensorsJob?.cancel()
        uiSensorsJob?.cancel()
        serviceScope.cancel()
        cameraManager.release()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "monitoring_channel"
        const val ACTION_STOP_SERVICE = "org.havenapp.neruppu.STOP_SERVICE"
        const val CLIP_PHOTO = "photo"
        const val CLIP_VIDEO = "video"
        const val CLIP_BURST = "burst"
        const val CLIP_DURATION_MS = 10_000L
        const val BURST_EXTRA_PHOTOS = 4
        /** failureReason written on events whose upload was cancelled; the DAO query skips them. */
        const val DISCARDED_REASON = "Annullato"
        const val ALERT_MAX_HOLD_MS = 150_000L
        const val CAPTURE_TIMEOUT_MS = 15_000L
        /** The torch switches on below TORCH_ON_LUX lux and off above TORCH_OFF_LUX lux. */
        const val TORCH_ON_LUX = 10f
        const val TORCH_OFF_LUX = 40f
        /** A torch state lasts at least this long before it may change again. */
        const val TORCH_MIN_STATE_MS = 30_000L
        /** Light changes this soon after a torch switch are not raised as alerts. */
        const val TORCH_ALERT_GRACE_MS = 10_000L
        /** How often the torch rule is re-checked while monitoring. */
        const val TORCH_RECHECK_MS = 10_000L
        const val LIVE_INTERVAL_MS = 5_000L
        const val LIVE_DEFAULT_MINUTES = 2
        const val LIVE_MAX_MINUTES = 10
        const val LIVE_MAX_SIDE = 1280
        const val LIVE_MAX_BYTES = 400_000
        val BOT_COMMANDS = listOf(
            "attiva" to "Accende il monitoraggio",
            "disattiva" to "Spegne il monitoraggio e ferma gli invii",
            "stato" to "Stato, batteria e impostazioni",
            "foto" to "Scatta una foto adesso",
            "live" to "Foto che si aggiorna ogni pochi secondi",
            "stop" to "Ferma il live e gli invii in corso",
            "invita" to "Link per far ricevere gli avvisi a un'altra persona",
            "persone" to "Chi riceve gli avvisi"
        )
        /** Menu shown to invited people: they can only leave. */
        val GUEST_COMMANDS = listOf(
            "esci" to "Smetti di ricevere gli avvisi"
        )
        const val HELP_TEXT = "Comandi disponibili:\n" +
            "/attiva - accende il monitoraggio\n" +
            "/disattiva - spegne il monitoraggio e ferma gli invii\n" +
            "/stato - stato e batteria\n" +
            "/foto - scatta una foto adesso\n" +
            "/live [minuti] - foto che si aggiorna ogni pochi secondi (2 min di default, massimo 10)\n" +
            "/stop - ferma il live e gli invii in corso\n" +
            "/invita - link per far ricevere gli avvisi a un'altra persona\n" +
            "/persone - chi riceve gli avvisi (e come togliere qualcuno)"
    }
}

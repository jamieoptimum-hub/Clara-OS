package com.jarvis.smarthomelauncher.engines

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlin.math.abs

// ---------------------------------------------------------------------------
// NOTIFICATION LISTENER SERVICE (Doorbell Interceptor & Live Media Callback)
// ---------------------------------------------------------------------------
class JarvisNotificationListener : NotificationListenerService() {

    private var mediaSessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        bindToBestController(controllers)
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateMediaBridgeFromController(activeController)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateMediaBridgeFromController(activeController)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        PlayerBackgroundScope.launch {
            try {
                val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                mediaSessionManager = msm
                val cn = ComponentName(this@JarvisNotificationListener, JarvisNotificationListener::class.java)
                msm.addOnActiveSessionsChangedListener(sessionListener, cn)
                bindToBestController(msm.getActiveSessions(cn))
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }
    }

    override fun onListenerDisconnected() {
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
            activeController?.unregisterCallback(controllerCallback)
            activeController = null
        } catch (_: Exception) {
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        val pkg = sbn.packageName.lowercase()
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val contentIntent = sbn.notification.contentIntent

        PlayerBackgroundScope.launch {
            val combined = "$title $text".lowercase()
            val isSecurityApp = pkg.contains("nest") ||
                    pkg.contains("chromecast") ||
                    pkg.contains("ezviz") ||
                    pkg.contains("videogo") ||
                    pkg.contains("imou") ||
                    pkg.contains("ring")

            val isDoorbellKeyword = combined.contains("doorbell") ||
                    combined.contains("someone is at") ||
                    combined.contains("ring") ||
                    combined.contains("visitor") ||
                    combined.contains("front door") ||
                    combined.contains("gate") ||
                    combined.contains("calling")

            if (isSecurityApp && isDoorbellKeyword) {
                JarvisLiveBridge.doorbellRingFlow.value = DoorbellRingEvent(
                    sourcePackage = pkg,
                    title = title.ifBlank { "Doorbell Alert" },
                    message = text.ifBlank { "Visitor at the door" },
                    contentIntent = contentIntent
                )
            }

            if (isSecurityApp) {
                val detectedAiType = when {
                    isDoorbellKeyword -> CameraAiEventType.DOORBELL_RING
                    combined.contains("person") || combined.contains("human") || combined.contains("someone") || combined.contains("people") ->
                        CameraAiEventType.PERSON
                    combined.contains("vehicle") || combined.contains("car") || combined.contains("truck") ->
                        CameraAiEventType.VEHICLE
                    combined.contains("package") || combined.contains("parcel") || combined.contains("delivery") ->
                        CameraAiEventType.PACKAGE
                    combined.contains("pet") || combined.contains("animal") || combined.contains("dog") || combined.contains("cat") ->
                        CameraAiEventType.ANIMAL
                    combined.contains("motion") || combined.contains("movement") || combined.contains("detected") ->
                        CameraAiEventType.MOTION
                    else -> CameraAiEventType.NONE
                }

                if (detectedAiType != CameraAiEventType.NONE) {
                    val appCtx = applicationContext
                    val tiles = runCatching { DeviceRepository.loadTiles(appCtx) }.getOrDefault(emptyList())
                    val bridge = runCatching { DeviceRepository.loadBridge(appCtx) }.getOrDefault(BridgeSettings())
                    val matchedCam = tiles.firstOrNull { t ->
                        t.isCameraTile && (
                                (title.isNotBlank() && t.title.contains(title, ignoreCase = true)) ||
                                        (pkg.contains("imou") && t.type == TileType.IMOU_CAMERA) ||
                                        ((pkg.contains("ezviz") || pkg.contains("videogo")) && t.type == TileType.EZVIZ_DOORBELL) ||
                                        ((pkg.contains("nest") || pkg.contains("chromecast")) && t.type == TileType.NEST_DOORBELL) ||
                                        (pkg.contains("ring") && t.type == TileType.RING_CAMERA)
                                )
                    } ?: tiles.firstOrNull { it.isCameraTile }

                    val summary = "${detectedAiType.label}: ${title.ifBlank { matchedCam?.title ?: "Security Camera" }} ${text.trim()}".trim()
                    if (matchedCam != null) {
                        SmartHomeApiClient.captureDvrEventSnapshot(
                            context = appCtx,
                            tile = matchedCam,
                            bridge = bridge,
                            aiEventType = detectedAiType,
                            summaryText = summary
                        )
                    } else {
                        JarvisLiveBridge.emitPlatformEvent(
                            JarvisPlatformEvent(
                                category = JarvisEventCategory.CAMERA_AI,
                                sourceDeviceTitle = title.ifBlank { "Security Camera" },
                                eventType = detectedAiType.name,
                                summary = summary,
                                cameraAiEvent = detectedAiType
                            )
                        )
                    }
                }
            }

            if (pkg.contains("spotify") || pkg.contains("youtube") || pkg.contains("music")) {
                syncActiveMediaSession()
            }
        }
    }

    private fun bindToBestController(controllers: List<MediaController>?) {
        try {
            activeController?.unregisterCallback(controllerCallback)
            val chosen = controllers?.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: controllers?.firstOrNull()
            activeController = chosen
            chosen?.registerCallback(controllerCallback)
            updateMediaBridgeFromController(chosen)
        } catch (_: Exception) {
        }
    }

    private fun updateMediaBridgeFromController(controller: MediaController?) {
        if (controller == null) return
        if (ClaraInAppMusicPlayer.isLocalOrCastEngineActivelyPlaying()) return
        val meta = controller.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        if (!title.isNullOrBlank()) {
            JarvisLiveBridge.activeTrackFlow.value =
                if (!artist.isNullOrBlank()) "$title - $artist" else title
        }
        val state = controller.playbackState?.state
        JarvisLiveBridge.isPlayingFlow.value = (state == PlaybackState.STATE_PLAYING)
    }

    private fun syncActiveMediaSession() {
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val cn = ComponentName(this, JarvisNotificationListener::class.java)
            bindToBestController(msm.getActiveSessions(cn))
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }
}

// ---------------------------------------------------------------------------
// BOOT RECEIVER: Auto-Launches Dashboard when Tablet powers on
// ---------------------------------------------------------------------------
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(launchIntent)
        }
    }
}

// ---------------------------------------------------------------------------
// NEST HUB / ANDROID "SCREEN ATTENTION" PRESENCE & FACE WAKE MANAGER
// ---------------------------------------------------------------------------
class PresenceWakeManager(
    private val context: Context,
    private val onPresenceDetected: () -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var baselineLux: Float? = null
    private var lastAccelMagnitude: Float? = null

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var previousZoneLuma: IntArray? = null
    private var lastFrameSampleTime = 0L

    @Volatile
    private var isRunning = false

    fun startMonitoring() {
        if (isRunning) return
        isRunning = true

        sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        startFrontCameraPresenceAndFaceDetector()
    }

    fun stopMonitoring() {
        if (!isRunning) return
        isRunning = false
        sensorManager.unregisterListener(this)
        baselineLux = null
        lastAccelMagnitude = null
        stopFrontCameraDetector()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRunning) return
        val evt = event ?: return
        when (evt.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                val distance = evt.values.firstOrNull() ?: return
                val maxRange = evt.sensor.maximumRange
                if (distance < maxRange && distance < 10f) {
                    onPresenceDetected()
                }
            }
            Sensor.TYPE_LIGHT -> {
                val currentLux = evt.values.firstOrNull() ?: return
                val base = baselineLux
                if (base == null) {
                    baselineLux = currentLux
                } else {
                    val delta = abs(currentLux - base)
                    if (delta >= 4.5f && (base == 0f || delta / base >= 0.10f)) {
                        baselineLux = currentLux
                        onPresenceDetected()
                    } else {
                        baselineLux = base * 0.92f + currentLux * 0.08f
                    }
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = evt.values.getOrNull(0) ?: 0f
                val y = evt.values.getOrNull(1) ?: 0f
                val z = evt.values.getOrNull(2) ?: 0f
                val mag = kotlin.math.sqrt(x * x + y * y + z * z)
                val prev = lastAccelMagnitude
                if (prev != null && abs(mag - prev) > 0.65f) {
                    onPresenceDetected()
                }
                lastAccelMagnitude = mag
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    @SuppressLint("MissingPermission")
    fun startFrontCameraPresenceAndFaceDetector() {
        if (cameraDevice != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        try {
            val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val frontCamId = camManager.cameraIdList.firstOrNull { id ->
                camManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: return

            val thread = HandlerThread("PresenceAttentionThread").apply { start() }
            val handler = Handler(thread.looper)
            cameraThread = thread
            cameraHandler = handler

            val characteristics = camManager.getCameraCharacteristics(frontCamId)
            val availableFaceModes = characteristics.get(
                CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES
            ) ?: intArrayOf()
            val supportsHardwareFaceDetect = availableFaceModes.any {
                it != CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF
            }

            val width = 160
            val height = 120

            imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ reader ->
                    if (!isRunning) return@setOnImageAvailableListener
                    val img = try {
                        reader.acquireLatestImage()
                    } catch (_: Exception) {
                        null
                    } ?: return@setOnImageAvailableListener
                    try {
                        val now = System.currentTimeMillis()
                        if (isRunning && now - lastFrameSampleTime >= 800L) {
                            lastFrameSampleTime = now
                            val plane = img.planes[0]
                            val buf = plane.buffer
                            val rowStride = plane.rowStride
                            val pixelStride = plane.pixelStride

                            val zones = IntArray(16)
                            val zoneCounts = IntArray(16)

                            for (y in 0 until height step 2) {
                                val rowOffset = y * rowStride
                                val zy = (y * 4) / height
                                for (x in 0 until width step 2) {
                                    val idx = rowOffset + x * pixelStride
                                    if (idx < buf.limit()) {
                                        val luma = buf.get(idx).toInt() and 0xFF
                                        val zx = (x * 4) / width
                                        val zIdx = (zy * 4 + zx).coerceIn(0, 15)
                                        zones[zIdx] += luma
                                        zoneCounts[zIdx]++
                                    }
                                }
                            }

                            var zoneTriggered = false
                            val prevZones = previousZoneLuma
                            val currentAvgs = IntArray(16)
                            for (i in 0 until 16) {
                                currentAvgs[i] = if (zoneCounts[i] > 0) zones[i] / zoneCounts[i] else 0
                                if (prevZones != null && abs(currentAvgs[i] - prevZones[i]) >= 4) {
                                    zoneTriggered = true
                                }
                            }
                            previousZoneLuma = currentAvgs

                            if (zoneTriggered) {
                                onPresenceDetected()
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        try {
                            img.close()
                        } catch (_: Exception) {
                        }
                    }
                }, handler)
            }

            camManager.openCamera(frontCamId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!isRunning) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    val surface = imageReader?.surface ?: return
                    try {
                        val reqBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                            if (supportsHardwareFaceDetect) {
                                set(
                                    CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                                    CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE
                                )
                            }
                        }
                        val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                result: TotalCaptureResult
                            ) {
                                if (!isRunning) return
                                val detectedFaces = result.get(CaptureResult.STATISTICS_FACES)
                                if (!detectedFaces.isNullOrEmpty()) {
                                    onPresenceDetected()
                                }
                            }
                        }
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(
                            listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    if (!isRunning) return
                                    try {
                                        session.setRepeatingRequest(reqBuilder.build(), captureCallback, handler)
                                    } catch (_: Exception) {
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {}
                            },
                            handler
                        )
                    } catch (_: Exception) {
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                }
            }, handler)
        } catch (_: Exception) {
        }
    }

    private fun stopFrontCameraDetector() {
        val handler = cameraHandler
        val thread = cameraThread
        val dev = cameraDevice
        val reader = imageReader

        cameraDevice = null
        imageReader = null
        cameraHandler = null
        cameraThread = null
        previousZoneLuma = null

        if (handler != null) {
            handler.post {
                try {
                    reader?.setOnImageAvailableListener(null, null)
                    dev?.close()
                    reader?.close()
                } catch (_: Exception) {
                }
                handler.postDelayed({
                    try {
                        thread?.quitSafely()
                    } catch (_: Exception) {
                    }
                }, 250L)
            }
        } else {
            try {
                dev?.close()
                reader?.close()
                thread?.quitSafely()
            } catch (_: Exception) {
            }
        }
    }
}

package com.saybook.subway.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.saybook.subway.detect.MotionSample
import com.saybook.subway.model.Direction
import com.saybook.subway.model.SubwayLines
import com.saybook.subway.sim.RideSimulator
import com.saybook.subway.track.AlertTexts
import com.saybook.subway.track.AlertKind
import com.saybook.subway.track.StationAlert
import com.saybook.subway.track.StationAlertSession
import com.saybook.subway.track.TrackerUpdate
import com.saybook.subway.track.TripPhase

/**
 * 포그라운드 서비스: 가속도 센서를 읽어 공유 모듈([StationAlertSession])에 넘기고,
 * 결과를 오버레이 · 알림 · 진동으로 표시합니다.
 *
 * 센서 처리는 전용 스레드([sensorThread])에서, UI 갱신은 메인 스레드에서 합니다.
 */
class TrackingService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var overlay: StationOverlay
    private val mainHandler = Handler(Looper.getMainLooper())

    private var sensorThread: HandlerThread? = null
    @Volatile private var sensorHandler: Handler? = null
    @Volatile private var session: StationAlertSession? = null

    // 센서 융합 값 (센서 스레드 전용)
    private val gravity = DoubleArray(3)
    private var hasGravity = false
    private var usingRawAccelerometer = false

    // 데모 모드
    private var demoSamples: List<MotionSample> = emptyList()
    private var demoCursor = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SensorManager::class.java)
        locationManager = getSystemService(LocationManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        overlay = StationOverlay(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_NEXT -> sensorHandler?.post { session?.stepForward() }
            ACTION_PREV -> sensorHandler?.post { session?.stepBack() }
            ACTION_STOP -> stopTracking()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        val line = SubwayLines.byId(intent.getStringExtra(EXTRA_LINE_ID) ?: "") ?: return stopSelf()
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)
        val direction = Direction.valueOf(intent.getStringExtra(EXTRA_DIRECTION) ?: Direction.FORWARD.name)
        val demo = intent.getBooleanExtra(EXTRA_DEMO, false)

        stopSensors()
        val newSession = StationAlertSession(line, startIndex, direction)
        session = newSession

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID_ONGOING,
            buildOngoingNotification(newSession.state.let(AlertTexts::compact)),
            foregroundServiceTypes(),
        )
        overlay.show(line.colorHex.toInt())

        val thread = HandlerThread("station-sensors").also { it.start() }
        sensorThread = thread
        val handler = Handler(thread.looper)
        sensorHandler = handler

        handler.post {
            newSession.setListener { update -> mainHandler.post { render(update) } }
            if (demo) {
                startDemo(handler)
            } else {
                registerSensors(handler)
                registerLocation(handler)
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** 위치 권한이 있을 때만 location 타입을 붙임 (권한 없이 붙이면 Android 14+ 에서 예외) */
    private fun foregroundServiceTypes(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasLocationPermission()) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
        }
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (hasLocationPermission()) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        return types
    }

    /**
     * 한국 지하철 역사에서는 GPS 중계기·와이파이 덕분에 위치가 잠깐 잡히는 경우가 많음.
     * 터널에서는 안 잡히다가 역에서 잡히는 패턴을 공유 모듈이 정차 신호로 사용합니다.
     */
    private val locationListener = LocationListener { location: Location ->
        val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        if (ageMs > MAX_LOCATION_AGE_MS) return@LocationListener // 캐시된 옛 위치
        session?.onLocation(
            location.elapsedRealtimeNanos / 1_000_000,
            location.latitude,
            location.longitude,
            if (location.hasAccuracy()) location.accuracy.toDouble() else 9_999.0,
        )
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun registerLocation(handler: Handler) {
        if (!hasLocationPermission()) return
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (locationManager.allProviders.contains(provider)) {
                runCatching {
                    locationManager.requestLocationUpdates(provider, 1_000L, 0f, locationListener, handler.looper)
                }
            }
        }
    }

    private fun registerSensors(handler: Handler) {
        val linear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val grav = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        if (linear != null && grav != null) {
            usingRawAccelerometer = false
            sensorManager.registerListener(this, grav, SensorManager.SENSOR_DELAY_GAME, handler)
            sensorManager.registerListener(this, linear, SensorManager.SENSOR_DELAY_GAME, handler)
        } else {
            // 융합 센서가 없는 기기: 원시 가속도에서 저역통과로 중력을 분리
            usingRawAccelerometer = true
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val s = session ?: return
        val tMs = event.timestamp / 1_000_000L
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                for (i in 0..2) gravity[i] = event.values[i].toDouble()
                hasGravity = true
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> if (hasGravity) {
                s.onMotion(
                    tMs,
                    event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble(),
                    gravity[0], gravity[1], gravity[2],
                )
            }

            Sensor.TYPE_ACCELEROMETER -> if (usingRawAccelerometer) {
                val v = event.values
                if (!hasGravity) {
                    for (i in 0..2) gravity[i] = v[i].toDouble()
                    hasGravity = true
                }
                for (i in 0..2) gravity[i] = RAW_GRAVITY_ALPHA * gravity[i] + (1 - RAW_GRAVITY_ALPHA) * v[i]
                s.onMotion(
                    tMs,
                    v[0] - gravity[0], v[1] - gravity[1], v[2] - gravity[2],
                    gravity[0], gravity[1], gravity[2],
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** 실제 탑승 없이 흐름을 보기 위한 데모: 합성 센서 데이터를 [DEMO_SPEEDUP] 배속으로 재생 */
    private fun startDemo(handler: Handler) {
        demoSamples = RideSimulator(seed = SystemClock.uptimeMillis().toInt())
            .generate(RideSimulator.typicalRide(stations = 6))
        demoCursor = 0
        val batchIntervalMs = 100L
        val samplesPerBatch = (50 * DEMO_SPEEDUP * batchIntervalMs / 1000).toInt()
        val tick = object : Runnable {
            override fun run() {
                val s = session ?: return
                val end = (demoCursor + samplesPerBatch).coerceAtMost(demoSamples.size)
                for (i in demoCursor until end) s.onSample(demoSamples[i])
                demoCursor = end
                if (demoCursor < demoSamples.size) handler.postDelayed(this, batchIntervalMs)
            }
        }
        handler.post(tick)
    }

    // ── 메인 스레드: 화면/알림 갱신 ──────────────────────────────────────────────

    private fun render(update: TrackerUpdate) {
        val current = session ?: return
        val state = update.state
        TrackingRepository.publish(state)
        val (title, subtitle) = AlertTexts.compact(state)
        val alert = update.alert
        overlay.update(title, subtitle)
        if (alert != null && (alert.kind == AlertKind.ARRIVED || alert.kind == AlertKind.TERMINAL)) {
            val next = state.nextStation?.name
            overlay.showArrival(
                label = if (alert.kind == AlertKind.TERMINAL) "종착역" else "이번 역",
                station = state.currentStation.name,
                detail = next?.let { "다음 역 · $it" } ?: "내리실 준비하세요",
            )
        }
        notificationManager.notify(NOTIFICATION_ID_ONGOING, buildOngoingNotification(title to subtitle))
        if (alert != null) onAlert(alert)
        if (state.phase == TripPhase.FINISHED && alert != null) {
            // 종착역: 알림을 남기고 몇 초 뒤 자동 종료
            mainHandler.postDelayed({ if (session === current) stopTracking() }, 15_000)
        }
    }

    private fun onAlert(alert: StationAlert) {
        if (!alert.important) return
        vibrate()
        // 오버레이 권한이 없을 때도 영상 위에 보이도록 헤드업 알림
        if (!overlay.canShow) {
            val n = NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle(alert.title)
                .setContentText(alert.body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setTimeoutAfter(10_000)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(NOTIFICATION_ID_ALERT, n)
        }
    }

    private fun vibrate() {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120), -1))
    }

    private fun buildOngoingNotification(text: Pair<String, String>): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_TRACKING)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(text.first)
            .setContentText(text.second)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, "◀ 이전 역", servicePendingIntent(ACTION_PREV, 1))
            .addAction(0, "다음 역 ▶", servicePendingIntent(ACTION_NEXT, 2))
            .addAction(0, "종료", servicePendingIntent(ACTION_STOP, 3))
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode, Intent(this, TrackingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun createChannels() {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_TRACKING, getString(R.string.channel_tracking), NotificationManager.IMPORTANCE_LOW),
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun stopSensors() {
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(locationListener)
        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null
        session = null
        hasGravity = false
    }

    private fun stopTracking() {
        stopSensors()
        overlay.hide()
        TrackingRepository.publish(null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopSensors()
        overlay.hide()
        TrackingRepository.publish(null)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.saybook.subway.START"
        const val ACTION_STOP = "com.saybook.subway.STOP"
        const val ACTION_NEXT = "com.saybook.subway.NEXT"
        const val ACTION_PREV = "com.saybook.subway.PREV"
        const val EXTRA_LINE_ID = "lineId"
        const val EXTRA_START_INDEX = "startIndex"
        const val EXTRA_DIRECTION = "direction"
        const val EXTRA_DEMO = "demo"

        private const val CHANNEL_TRACKING = "tracking"
        private const val CHANNEL_ALERTS = "alerts"
        private const val NOTIFICATION_ID_ONGOING = 1
        private const val NOTIFICATION_ID_ALERT = 2
        private const val RAW_GRAVITY_ALPHA = 0.9
        private const val DEMO_SPEEDUP = 10
        private const val MAX_LOCATION_AGE_MS = 10_000L

        fun start(context: Context, lineId: String, startIndex: Int, direction: Direction, demo: Boolean) {
            val intent = Intent(context, TrackingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LINE_ID, lineId)
                .putExtra(EXTRA_START_INDEX, startIndex)
                .putExtra(EXTRA_DIRECTION, direction.name)
                .putExtra(EXTRA_DEMO, demo)
            context.startForegroundService(intent)
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, TrackingService::class.java).setAction(action))
        }
    }
}

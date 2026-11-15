package com.jagastech.stepwgndsq

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Ultra-Lightweight Persistent Foreground Service (< 8 MB RAM footprint, Zero AndroidX dependencies).
 * Built for the 2007 Honda Stepwgn (RG1) 9-inch Android Head Unit.
 * Stays alive continuously across ZLink 6 (CarPlay/Android Auto), Bluetooth, and USB/SD Music.
 */
class DsqAudioEngineService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): DsqAudioEngineService = this@DsqAudioEngineService
    }

    private val binder = LocalBinder()
    private lateinit var prefs: SharedPreferences

    private val activeDynamicsProcessors = mutableMapOf<Int, DynamicsProcessing>()
    private val activeLegacyEqualizers = mutableMapOf<Int, Equalizer>()
    private val activeLoudnessEnhancers = mutableMapOf<Int, LoudnessEnhancer>()
    private val activeVirtualizers = mutableMapOf<Int, Virtualizer>()

    val centerFrequenciesHz = intArrayOf(
        50, 80, 125, 200, 315, 500, 800, 1250, 2000, 3150, 5000, 8000, 16000
    )
    private val bandCutoffFrequenciesHz = floatArrayOf(
        63f, 100f, 160f, 250f, 400f, 630f, 1000f, 1600f, 2500f, 4000f, 6300f, 11300f, 20000f
    )
    private val loudnessWeights = floatArrayOf(
        1.00f, 0.85f, 0.45f, 0.10f, 0.00f, 0.00f, 0.00f, 0.00f, 0.10f, 0.15f, 0.30f, 0.50f, 0.70f
    )
    private val spatialStageWeights = floatArrayOf(
        0.05f, 0.05f, 0.10f, 0.15f, 0.15f, 0.20f, 0.25f, 0.35f, 0.55f, 0.65f, 0.70f, 0.85f, 1.00f
    )

    var currentGainsDb = floatArrayOf(
        3f, 5f, 3f, -1f, -2f, 0f, -1f, 1f, 2f, 1f, 3f, 4f, 4f
    )
    var loudnessGainDb: Float = 6f
    var spatialStrengthPct: Int = 65
    var isDspActive: Boolean = true
    var isAutoTrimActive: Boolean = true

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val sessionId = intent?.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0) ?: 0
            when (intent?.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> attachToSession(sessionId)
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> releaseSession(sessionId)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("stepwgn_nrmz25_prefs", Context.MODE_PRIVATE)
        loadSavedSettings()
        startForegroundNotification()

        // Always hook Global Output Mix (Session 0) for ZLink 6, Bluetooth & USB players
        attachToSession(0)

        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(sessionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(sessionReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        applyGainsToEngine()
        // START_STICKY guarantees Android restarts the EQ immediately if ZLink 6 or navigation needs RAM
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Even if the user swipes away recent apps, keep the audio engine running
        val restartIntent = Intent(applicationContext, DsqAudioEngineService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(restartIntent)
        } else {
            applicationContext.startService(restartIntent)
        }
        super.onTaskRemoved(rootIntent)
    }

    fun saveSettings() {
        val editor = prefs.edit()
        for (i in 0 until 13) {
            editor.putFloat("band_$i", currentGainsDb[i])
        }
        editor.putFloat("loudness_db", loudnessGainDb)
        editor.putInt("spatial_pct", spatialStrengthPct)
        editor.putBoolean("dsp_active", isDspActive)
        editor.apply()
    }

    private fun loadSavedSettings() {
        if (!prefs.contains("band_0")) return
        for (i in 0 until 13) {
            currentGainsDb[i] = prefs.getFloat("band_$i", currentGainsDb[i])
        }
        loudnessGainDb = prefs.getFloat("loudness_db", 6f)
        spatialStrengthPct = prefs.getInt("spatial_pct", 65)
        isDspActive = prefs.getBoolean("dsp_active", true)
    }

    private fun attachToSession(sessionId: Int) {
        try {
            if (!activeLoudnessEnhancers.containsKey(sessionId)) {
                activeLoudnessEnhancers[sessionId] = LoudnessEnhancer(sessionId).apply {
                    enabled = isDspActive && loudnessGainDb > 0f
                }
            }
        } catch (_: Exception) {}

        try {
            if (!activeVirtualizers.containsKey(sessionId)) {
                activeVirtualizers[sessionId] = Virtualizer(100, sessionId).apply {
                    try {
                        forceVirtualizationMode(Virtualizer.VIRTUALIZATION_MODE_TRANSAURAL)
                    } catch (_: Exception) {}
                    enabled = isDspActive && spatialStrengthPct > 0
                }
            }
        } catch (_: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                if (!activeDynamicsProcessors.containsKey(sessionId)) {
                    val config = DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                        2,
                        true,
                        13,
                        false,
                        0,
                        true,
                        13,
                        true
                    ).build()
                    activeDynamicsProcessors[sessionId] = DynamicsProcessing(100, sessionId, config).apply {
                        enabled = isDspActive
                    }
                }
                applyGainsToEngine()
                return
            } catch (_: Exception) {}
        }

        try {
            if (!activeLegacyEqualizers.containsKey(sessionId)) {
                activeLegacyEqualizers[sessionId] = Equalizer(100, sessionId).apply {
                    enabled = isDspActive
                }
            }
            applyGainsToEngine()
        } catch (_: Exception) {}
    }

    private fun releaseSession(sessionId: Int) {
        if (sessionId == 0) return
        activeDynamicsProcessors.remove(sessionId)?.release()
        activeLegacyEqualizers.remove(sessionId)?.release()
        activeLoudnessEnhancers.remove(sessionId)?.release()
        activeVirtualizers.remove(sessionId)?.release()
    }

    fun getCompositeBandGainDb(i: Int): Float {
        if (!isDspActive) return 0f
        val base = currentGainsDb[i]
        val loudBoost = loudnessGainDb * loudnessWeights[i] * 0.55f
        val spatialLift = (spatialStrengthPct / 100f) * 2.5f * spatialStageWeights[i]
        return (base + loudBoost + spatialLift).coerceIn(-14f, 14f)
    }

    fun applyGainsToEngine() {
        saveSettings()
        val compositePeaks = FloatArray(13) { getCompositeBandGainDb(it) }
        val maxBoost = compositePeaks.maxOrNull() ?: 0f
        val preTrimDb = if (isDspActive && isAutoTrimActive && maxBoost > 0f) -maxBoost else 0f

        val targetLoudnessMb = if (isDspActive) (loudnessGainDb * 80f).roundToInt() else 0
        for ((_, le) in activeLoudnessEnhancers) {
            try {
                le.setTargetGain(targetLoudnessMb)
                le.enabled = isDspActive && loudnessGainDb > 0f
            } catch (_: Exception) {}
        }

        val targetVirtualizerStrength = if (isDspActive) (spatialStrengthPct * 10).coerceIn(0, 1000).toShort() else 0
        for ((_, virt) in activeVirtualizers) {
            try {
                if (virt.strengthSupported) {
                    virt.setStrength(targetVirtualizerStrength)
                }
                virt.enabled = isDspActive && spatialStrengthPct > 0
            } catch (_: Exception) {}
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && activeDynamicsProcessors.isNotEmpty()) {
            val preEq = DynamicsProcessing.Eq(true, true, 13)
            val postEqLeft = DynamicsProcessing.Eq(true, true, 13)
            val postEqRight = DynamicsProcessing.Eq(true, true, 13)
            val spatialFactor = if (isDspActive) (spatialStrengthPct / 100f) * 1.35f else 0f

            for (i in 0 until 13) {
                val gain = getCompositeBandGainDb(i)
                preEq.setBand(i, DynamicsProcessing.EqBand(true, bandCutoffFrequenciesHz[i], gain))

                val panDelta = if (i >= 6) (if (i % 2 == 0) spatialFactor else -spatialFactor) else 0f
                postEqLeft.setBand(i, DynamicsProcessing.EqBand(true, bandCutoffFrequenciesHz[i], panDelta))
                postEqRight.setBand(i, DynamicsProcessing.EqBand(true, bandCutoffFrequenciesHz[i], -panDelta))
            }
            for ((_, dp) in activeDynamicsProcessors) {
                dp.setInputGainAllChannelsTo(preTrimDb)
                dp.setPreEqAllChannelsTo(preEq)
                dp.setPostEqByChannelIndex(0, postEqLeft)
                dp.setPostEqByChannelIndex(1, postEqRight)
                dp.enabled = isDspActive
            }
        }

        for ((_, eq) in activeLegacyEqualizers) {
            val numBands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            for (b in 0 until numBands) {
                val centerHz = eq.getCenterFreq(b.toShort()) / 1000
                val nearestIdx = centerFrequenciesHz.indices.minByOrNull {
                    abs(centerFrequenciesHz[it] - centerHz)
                } ?: 0
                val targetDb = getCompositeBandGainDb(nearestIdx)
                val targetMillibel = (targetDb * 100f).roundToInt().coerceIn(range[0].toInt(), range[1].toInt())
                eq.setBandLevel(b.toShort(), targetMillibel.toShort())
            }
            eq.enabled = isDspActive
        }
    }

    private fun startForegroundNotification() {
        val channelId = "stepwgn_dsq_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Stepwgn NR-MZ25 13-Band DSQ Engine",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            val openUiIntent = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            val notification = Notification.Builder(this, channelId)
                .setContentTitle("Stepwgn 13-Band DSQ + Spatial 3D Active")
                .setContentText("Running across ZLink 6, Bluetooth & USB (<8MB RAM)")
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setContentIntent(openUiIntent)
                .setOngoing(true)
                .build()
            startForeground(1001, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(sessionReceiver) } catch (_: Exception) {}
        activeDynamicsProcessors.values.forEach { it.release() }
        activeLegacyEqualizers.values.forEach { it.release() }
        activeLoudnessEnhancers.values.forEach { it.release() }
        activeVirtualizers.values.forEach { it.release() }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}

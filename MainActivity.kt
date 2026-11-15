package com.jagastech.stepwgndsq

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Pure Native Android Activity (Zero AndroidX bloat — APK is < 50 KB and frees UI RAM immediately when closed).
 */
class MainActivity : Activity() {

    private var dsqService: DsqAudioEngineService? = null
    private var selectedBandIndex = 1
    private val bandValueLabels = mutableListOf<TextView>()
    private val bandSeekBars = mutableListOf<SeekBar>()
    private lateinit var activeBandTitle: TextView
    private lateinit var loudnessStatusTv: TextView
    private lateinit var spatialStatusTv: TextView

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as DsqAudioEngineService.LocalBinder
            dsqService = binder.getService()
            refreshHeadUnitUI()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            dsqService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0E14"))
            setPadding(20, 12, 20, 12)
        }

        fun makeHwButton(label: String, bgHex: String = "#1E293B", onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor(bgHex))
                setOnClickListener { onClick() }
            }
        }

        val commandBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(Color.parseColor("#131822"))
        }

        activeBandTitle = TextView(this).apply {
            text = "BAND 2 (80 Hz): +5.0 dB"
            textSize = 16f
            setTextColor(Color.parseColor("#F59E0B"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        commandBar.addView(activeBandTitle)
        commandBar.addView(makeHwButton("◀ BAND") {
            selectedBandIndex = (selectedBandIndex + 12) % 13
            refreshHeadUnitUI()
        })
        commandBar.addView(makeHwButton("-1 dB", "#B45309") { stepSelectedBand(-1f) })
        commandBar.addView(makeHwButton("0 dB") { setSelectedBand(0f) })
        commandBar.addView(makeHwButton("+1 dB", "#047857") { stepSelectedBand(1f) })
        commandBar.addView(makeHwButton("BAND ▶") {
            selectedBandIndex = (selectedBandIndex + 1) % 13
            refreshHeadUnitUI()
        })

        val fxBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(Color.parseColor("#161F2E"))
        }
        loudnessStatusTv = TextView(this).apply {
            text = "LOUDNESS: +6dB"
            textSize = 13f
            setTextColor(Color.parseColor("#10B981"))
            setPadding(0, 0, 12, 0)
        }
        fxBar.addView(loudnessStatusTv)
        listOf(0f to "LOUD OFF", 3f to "LOW +3dB", 6f to "MID +6dB", 9f to "HIGH +9dB").forEach { (db, lbl) ->
            fxBar.addView(makeHwButton(lbl) {
                dsqService?.let {
                    it.loudnessGainDb = db
                    it.applyGainsToEngine()
                    refreshHeadUnitUI()
                }
            })
        }

        spatialStatusTv = TextView(this).apply {
            text = "  3D STAGE: 65%"
            textSize = 13f
            setTextColor(Color.parseColor("#A78BFA"))
            setPadding(16, 0, 12, 0)
        }
        fxBar.addView(spatialStatusTv)
        listOf(0 to "3D OFF", 40 to "DASH 40%", 65 to "CABIN 65%", 95 to "MAX 95%").forEach { (pct, lbl) ->
            fxBar.addView(makeHwButton(lbl, "#4C1D95") {
                dsqService?.let {
                    it.spatialStrengthPct = pct
                    it.applyGainsToEngine()
                    refreshHeadUnitUI()
                }
            })
        }

        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        val presets = linkedMapOf(
            "SpaceBuds 4" to floatArrayOf(3f, 5f, 3f, -1f, -2f, 0f, -1f, 1f, 2f, 1f, 3f, 4f, 4f),
            "SpaceBuds 2" to floatArrayOf(4f, 4f, 2f, 0f, -1f, -1f, 0f, 1f, 2f, 2f, 3f, 3f, 2f),
            "Stepwgn De-Box" to floatArrayOf(2f, 4f, 3f, -2f, -3f, 0f, 0f, 1f, 2f, 1f, 2f, 3f, 3f),
            "Amapiano 80Hz" to floatArrayOf(4f, 6f, 4f, -2f, -2f, -1f, 0f, 1f, 2f, 2f, 4f, 5f, 4f),
            "Flat 0dB" to FloatArray(13) { 0f }
        )
        for ((name, curve) in presets) {
            presetRow.addView(makeHwButton(name) {
                dsqService?.let {
                    it.currentGainsDb = curve.clone()
                    it.applyGainsToEngine()
                    refreshHeadUnitUI()
                }
            })
        }

        val faderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val labels = listOf("50", "80", "125", "200", "315", "500", "800", "1.25k", "2k", "3.15k", "5k", "8k", "16k")
        for (i in 0 until 13) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener {
                    selectedBandIndex = i
                    refreshHeadUnitUI()
                }
            }
            val valTv = TextView(this).apply {
                text = "0dB"
                textSize = 11f
                setTextColor(Color.parseColor("#38BDF8"))
            }
            val seek = SeekBar(this).apply {
                max = 24
                progress = 12
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) {
                            selectedBandIndex = i
                            setSelectedBand((p - 12).toFloat())
                        }
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            val freqTv = TextView(this).apply {
                text = labels[i]
                textSize = 11f
                setTextColor(Color.WHITE)
            }
            bandValueLabels.add(valTv)
            bandSeekBars.add(seek)
            col.addView(valTv)
            col.addView(seek)
            col.addView(freqTv)
            faderRow.addView(col)
        }

        root.addView(commandBar)
        root.addView(fxBar)
        root.addView(presetRow)
        root.addView(faderRow)
        setContentView(root)

        Intent(this, DsqAudioEngineService::class.java).also { intent ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun stepSelectedBand(delta: Float) {
        val current = dsqService?.currentGainsDb?.get(selectedBandIndex) ?: 0f
        setSelectedBand((current + delta).coerceIn(-12f, 12f))
    }

    private fun setSelectedBand(newDb: Float) {
        dsqService?.let {
            it.currentGainsDb[selectedBandIndex] = newDb
            it.applyGainsToEngine()
            refreshHeadUnitUI()
        }
    }

    private fun refreshHeadUnitUI() {
        val svc = dsqService ?: return
        val freq = svc.centerFrequenciesHz[selectedBandIndex]
        val gain = svc.currentGainsDb[selectedBandIndex]
        activeBandTitle.text = "BAND ${selectedBandIndex + 1} (${freq} Hz): ${if (gain >= 0) "+" else ""}${gain.toInt()} dB"
        loudnessStatusTv.text = "LOUDNESS: +${svc.loudnessGainDb.toInt()}dB"
        spatialStatusTv.text = "  3D STAGE: ${svc.spatialStrengthPct}%"
        for (i in 0 until 13) {
            val g = svc.currentGainsDb[i].toInt()
            bandValueLabels[i].text = "${if (g >= 0) "+" else ""}${g}dB"
            bandSeekBars[i].progress = g + 12
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(connection)
    }
}

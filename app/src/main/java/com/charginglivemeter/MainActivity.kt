package com.charginglivemeter

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var reader: BatteryReader
    private lateinit var diagnostics: TextView

    private val refresh = object : Runnable {
        override fun run() {
            reader.read()
            diagnostics.text = reader.diagnosticText()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reader = BatteryReader(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 32, 28, 32)
            setBackgroundColor(Color.rgb(5, 5, 14))
        }

        val title = TextView(this).apply {
            text = "CHARGING LIVE METER"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "خلفية شحن متحركة بتأثيرات نيون"
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 30)
        }
        val note = TextView(this).apply {
            text = "تعرض الخلفية نسبة البطارية والقدرة الكهربائية المقدّرة من قراءات الهاتف المتاحة."
            textSize = 14f
            setTextColor(Color.rgb(190, 190, 205))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 28)
        }
        val button = Button(this).apply {
            text = "معاينة وتعيين الخلفية الحية"
            setOnClickListener { openWallpaperPicker() }
        }
        diagnostics = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.rgb(150, 220, 170))
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.START
            setPadding(0, 36, 0, 0)
        }
        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(note)
        layout.addView(button)
        layout.addView(diagnostics)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(layout)
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun openWallpaperPicker() {
        val component = ComponentName(this, ChargingWallpaperService::class.java)
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }
}
package com.charginglivemeter

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.util.Locale
import java.util.Random
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val MAX_MA = 3000f
private const val READ_INTERVAL_MS = 1000L

class ChargingWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = ChargingEngine()

    inner class ChargingEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private lateinit var reader: BatteryReader

        private var visible = false
        private var surfaceReady = false

        // Latest battery data (updated about once per second).
        private var charging = false
        private var currentAvailable = false
        private var targetMa = 0f
        private var levelFloat = -1f
        private var textLevelInt = "--"
        private var textLevelDec: String? = null
        private var textMa = "--"
        private var textW = "-- W"
        private var textV = "-- V"
        private var textT = "-- \u00B0C"
        private var textState = "Unknown"
        private var textSource = "no reliable current reading"
        private var stateColor = Color.GRAY

        // Animation state.
        private var lastReadMs = 0L
        private var lastFrameMs = 0L
        private var phase = 0f
        private var flow = 0f
        private var displayMa = 0f

        // Layout (computed when the surface size changes).
        private var ringCx = 0f
        private var ringCy = 0f
        private var ringR = 0f
        private var gaugeCx = 0f
        private var gaugeCy = 0f
        private var gaugeR = 0f
        private var bandThick = 0f
        private var beamTop = 0f
        private var beamBottom = 0f
        private var auraShader: Shader? = null
        private var beamShader: Shader? = null

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val oval = RectF()
        private val chip = RectF()
        private val bolt = Path()
        private val rnd = Random()

        private val tfLight = Typeface.create("sans-serif-light", Typeface.NORMAL)
        private val tfMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        private val tfBold = Typeface.create("sans-serif", Typeface.BOLD)

        private val colDarkRed = Color.rgb(108, 38, 38)
        private val colRed = Color.rgb(235, 70, 62)
        private val colDarkGreen = Color.rgb(14, 82, 34)
        private val colGreen = Color.rgb(36, 214, 70)
        private val colCyan = Color.rgb(60, 200, 255)
        private val colViolet = Color.rgb(125, 55, 255)

        private val frame = object : Runnable {
            override fun run() {
                if (!visible || !surfaceReady) return
                val now = SystemClock.elapsedRealtime()
                if (lastReadMs == 0L || now - lastReadMs >= READ_INTERVAL_MS) readBattery(now)
                step(now)
                draw()
                if (visible && surfaceReady) {
                    handler.postDelayed(this, if (charging) 33L else 100L)
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            reader = BatteryReader(this@ChargingWallpaperService)
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            restartLoop()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceReady = true
            restartLoop()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            layout(width.toFloat(), height.toFloat())
            if (visible && surfaceReady) draw()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            handler.removeCallbacks(frame)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            visible = false
            surfaceReady = false
            handler.removeCallbacks(frame)
            super.onDestroy()
        }

        private fun restartLoop() {
            handler.removeCallbacks(frame)
            if (visible && surfaceReady) {
                // Re-read immediately and avoid a large animation jump after being hidden.
                lastReadMs = 0L
                lastFrameMs = 0L
                handler.post(frame)
            }
        }

        // ---- data ----------------------------------------------------------------

        private fun readBattery(now: Long) {
            val s = reader.read()
            lastReadMs = now

            charging = s.state == PowerState.CHARGING
            val precise = s.levelPrecise
            if (precise != null) {
                // Three decimals like the reference app; split "22.545" into "22" and "545".
                val parts = String.format(Locale.US, "%.3f", precise).split('.')
                textLevelInt = parts[0]
                textLevelDec = parts.getOrNull(1)
                levelFloat = precise.toFloat()
            } else {
                textLevelInt = if (s.levelPercent >= 0) s.levelPercent.toString() else "--"
                textLevelDec = null
                levelFloat = s.levelPercent.toFloat()
            }

            val ma = s.currentMa
            currentAvailable = ma != null
            targetMa = ma?.toFloat() ?: 0f
            textMa = if (ma != null) ma.roundToInt().toString() else "--"
            textW = s.powerW?.let { String.format(Locale.US, "%.1f W", it) } ?: "-- W"
            textV = s.voltageV?.let { String.format(Locale.US, "%.2f V", it) } ?: "-- V"
            textT = s.temperatureC?.let { String.format(Locale.US, "%.1f \u00B0C", it) } ?: "-- \u00B0C"

            when (s.state) {
                PowerState.CHARGING -> { textState = "Charging"; stateColor = Color.rgb(60, 220, 110) }
                PowerState.DISCHARGING -> { textState = "Discharging"; stateColor = Color.rgb(255, 95, 85) }
                PowerState.FULL -> { textState = "Full"; stateColor = Color.rgb(70, 200, 255) }
                PowerState.NOT_CHARGING -> { textState = "Not charging"; stateColor = Color.rgb(255, 200, 70) }
                PowerState.UNKNOWN -> { textState = "Unknown"; stateColor = Color.GRAY }
            }
            textSource = if (s.currentSource != null)
                "battery-side estimate \u00B7 ${s.currentSource}" else "no reliable current reading"
        }

        private fun step(now: Long) {
            val dtMs = if (lastFrameMs == 0L) 16L else (now - lastFrameMs).coerceIn(1L, 200L)
            lastFrameMs = now
            val dt = dtMs / 1000f
            phase = (phase + dt * (if (charging) 90f else 24f)) % 360f
            flow = (flow + dt * (if (charging) 170f else 40f)) % 100000f
            val target = if (currentAvailable) targetMa else 0f
            displayMa += (target - displayMa) * (1f - exp(-dt * 6f))
        }

        // ---- layout ----------------------------------------------------------------

        private fun layout(w: Float, h: Float) {
            ringCx = w / 2f
            ringCy = h * 0.27f
            ringR = min(w * 0.30f, h * 0.13f)

            gaugeCx = w / 2f
            gaugeCy = h * 0.60f
            gaugeR = min(w * 0.40f, h * 0.17f)
            bandThick = gaugeR * 0.26f

            beamTop = gaugeCy + gaugeR * 1.5f
            beamBottom = h * 0.95f

            auraShader = RadialGradient(
                ringCx, ringCy, ringR * 1.8f,
                intArrayOf(Color.argb(55, 30, 55, 255), Color.argb(24, 120, 20, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP
            )
            beamShader = LinearGradient(
                0f, beamTop, 0f, beamBottom,
                intArrayOf(Color.TRANSPARENT, Color.rgb(30, 90, 255), Color.rgb(110, 35, 255), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
        }

        // ---- drawing -----------------------------------------------------------------

        private fun draw() {
            val holder: SurfaceHolder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = if (Build.VERSION.SDK_INT >= 26) holder.lockHardwareCanvas() else holder.lockCanvas()
                if (canvas != null) render(canvas)
            } catch (_: Exception) {
            } finally {
                if (canvas != null) {
                    try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
                }
            }
        }

        private fun render(c: Canvas) {
            if (ringR <= 0f) layout(c.width.toFloat(), c.height.toFloat())
            c.drawColor(Color.rgb(2, 2, 8))
            drawRing(c)
            if (charging) drawLightning(c)
            drawGauge(c)
            drawReadouts(c)
            drawBeam(c)
        }

        private fun drawRing(c: Canvas) {
            val cx = ringCx
            val cy = ringCy
            val radius = ringR
            val pulse = if (charging) 0.75f + 0.25f * sin(Math.toRadians((phase * 4f).toDouble())).toFloat() else 0.7f

            // Soft coloured aura.
            paint.style = Paint.Style.FILL
            paint.shader = auraShader
            paint.alpha = (255 * pulse).toInt()
            c.drawCircle(cx, cy, radius * 1.8f, paint)
            paint.shader = null
            paint.alpha = 255

            // Battery level track + progress on the outside of the rings.
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = radius * 0.05f
            val lr = radius * 1.17f
            paint.color = Color.rgb(25, 30, 60)
            c.drawCircle(cx, cy, lr, paint)
            if (levelFloat > 0f) {
                paint.color = colCyan
                oval.set(cx - lr, cy - lr, cx + lr, cy + lr)
                c.drawArc(oval, -90f, 360f * levelFloat / 100f, false, paint)
            }
            paint.strokeCap = Paint.Cap.BUTT

            // Animated energy rings.
            for (i in 0..3) {
                val r = radius * (0.72f + i * 0.105f)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = if (i == 2) 5.5f else 2.2f
                paint.color = when (i % 3) {
                    0 -> Color.rgb(35, 115, 255)
                    1 -> Color.rgb(115, 45, 255)
                    else -> Color.rgb(95, 210, 255)
                }
                paint.alpha = if (i == 2) (230 * pulse).toInt() else (145 * pulse).toInt()
                oval.set(cx - r, cy - r, cx + r, cy + r)
                c.drawArc(oval, phase * (if (i % 2 == 0) 1f else -1f) + i * 24f, 285f, false, paint)
            }

            // Bright moving highlights.
            paint.style = Paint.Style.FILL
            for (i in 0..5) {
                val angle = Math.toRadians((phase * 1.7f + i * 60f).toDouble())
                val rr = radius * 0.98f
                paint.color = if (i % 2 == 0) Color.CYAN else Color.MAGENTA
                paint.alpha = 220
                c.drawCircle(cx + cos(angle).toFloat() * rr, cy + sin(angle).toFloat() * rr,
                    if (i == 0) 7f else 3.5f, paint)
            }
            paint.alpha = 255

            // Percentage in the centre: integer (white), decimals (green), then "%".
            val decText = textLevelDec?.let { ".$it" }
            textPaint.style = Paint.Style.FILL
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.typeface = tfLight
            textPaint.textSize = radius * 0.52f
            val wInt = textPaint.measureText(textLevelInt)
            textPaint.typeface = tfMedium
            textPaint.textSize = radius * 0.21f
            val wDec = if (decText != null) textPaint.measureText(decText) else 0f
            val wPct = textPaint.measureText("%")
            var x = cx - (wInt + wDec + wPct) / 2f
            val baseY = cy + radius * 0.10f

            textPaint.typeface = tfLight
            textPaint.textSize = radius * 0.52f
            textPaint.color = Color.WHITE
            c.drawText(textLevelInt, x, baseY, textPaint)
            x += wInt
            textPaint.typeface = tfMedium
            textPaint.textSize = radius * 0.21f
            if (decText != null) {
                textPaint.color = colGreen
                c.drawText(decText, x, baseY, textPaint)
                x += wDec
            }
            textPaint.color = Color.rgb(160, 165, 185)
            c.drawText("%", x, baseY, textPaint)

            // Battery icon + label under the number.
            drawBatteryIcon(c, cx, cy + radius * 0.34f, radius * 0.36f, radius * 0.18f)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = tfLight
            textPaint.textSize = radius * 0.11f
            textPaint.color = Color.rgb(150, 155, 175)
            c.drawText("BATTERY", cx, cy + radius * 0.62f, textPaint)
        }

        private fun drawBatteryIcon(c: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
            val left = cx - w / 2f
            val top = cy - h / 2f
            val stroke = h * 0.12f
            val col = if (charging) colGreen else stateColor
            val fillFrac = (levelFloat / 100f).coerceIn(0f, 1f)

            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = col
            oval.set(left, top, left + w, top + h)
            c.drawRoundRect(oval, h * 0.22f, h * 0.22f, paint)

            paint.style = Paint.Style.FILL
            oval.set(left + w + stroke * 0.5f, cy - h * 0.18f, left + w + stroke * 0.5f + h * 0.12f, cy + h * 0.18f)
            c.drawRect(oval, paint)

            val pad = stroke * 1.6f
            paint.alpha = 90
            oval.set(left + pad, top + pad, left + pad + (w - 2f * pad) * fillFrac, top + h - pad)
            c.drawRect(oval, paint)
            paint.alpha = 255

            if (charging) {
                val bx = cx - h * 0.25f
                val by = top + h * 0.15f
                val bs = h * 0.7f
                bolt.reset()
                bolt.moveTo(bx + bs * 0.62f, by)
                bolt.lineTo(bx + bs * 0.18f, by + bs * 0.58f)
                bolt.lineTo(bx + bs * 0.46f, by + bs * 0.58f)
                bolt.lineTo(bx + bs * 0.34f, by + bs)
                bolt.lineTo(bx + bs * 0.82f, by + bs * 0.38f)
                bolt.lineTo(bx + bs * 0.54f, by + bs * 0.38f)
                bolt.close()
                paint.color = Color.WHITE
                c.drawPath(bolt, paint)
            }
        }

        private fun drawLightning(c: Canvas) {
            val seed = (phase / 14f).toInt()
            rnd.setSeed(seed * 7919L + 13L)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            for (k in 0 until 3) {
                var ang = Math.toRadians((rnd.nextFloat() * 360f).toDouble())
                var rad = ringR * 1.22f
                bolt.reset()
                bolt.moveTo(ringCx + cos(ang).toFloat() * rad, ringCy + sin(ang).toFloat() * rad)
                for (s in 0 until 5) {
                    rad += ringR * (0.05f + rnd.nextFloat() * 0.04f)
                    ang += Math.toRadians(((rnd.nextFloat() - 0.5f) * 14f).toDouble())
                    bolt.lineTo(ringCx + cos(ang).toFloat() * rad, ringCy + sin(ang).toFloat() * rad)
                }
                paint.color = if (k % 2 == 0) colCyan else colViolet
                paint.strokeWidth = 7f
                paint.alpha = 70
                c.drawPath(bolt, paint)
                paint.strokeWidth = 2.5f
                paint.alpha = 230
                c.drawPath(bolt, paint)
            }
            paint.strokeCap = Paint.Cap.BUTT
            paint.alpha = 255
        }

        private fun angleFor(ma: Float): Float =
            180f + (ma.coerceIn(-MAX_MA, MAX_MA) + MAX_MA) / (2f * MAX_MA) * 180f

        private fun drawGauge(c: Canvas) {
            val cx = gaugeCx
            val cy = gaugeCy
            val r = gaugeR
            val t = bandThick
            val mid = r - t / 2f

            // Background bands: red = discharge (left), green = charge (right).
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.BUTT
            paint.strokeWidth = t
            oval.set(cx - mid, cy - mid, cx + mid, cy + mid)
            paint.color = colDarkRed
            c.drawArc(oval, 180f, 90f, false, paint)
            paint.color = colDarkGreen
            c.drawArc(oval, 270f, 90f, false, paint)

            // Bright part from zero to the current value.
            if (currentAvailable) {
                val a = angleFor(displayMa)
                if (displayMa > 0.5f) {
                    paint.color = colGreen
                    c.drawArc(oval, 270f, a - 270f, false, paint)
                } else if (displayMa < -0.5f) {
                    paint.color = colRed
                    c.drawArc(oval, a, 270f - a, false, paint)
                }
            }

            // Ticks every 500 mA; labels every 1500 mA.
            textPaint.style = Paint.Style.FILL
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = tfMedium
            textPaint.color = Color.rgb(200, 205, 220)
            textPaint.textSize = r * 0.075f
            paint.color = Color.rgb(200, 205, 220)
            for (i in -6..6) {
                val major = i % 3 == 0
                val rad = Math.toRadians((180.0 + (i + 6) * 15.0))
                val ca = cos(rad).toFloat()
                val sa = sin(rad).toFloat()
                val outer = r - t - r * 0.02f
                val inner = outer - r * (if (major) 0.055f else 0.03f)
                paint.strokeWidth = if (major) 4f else 2f
                c.drawLine(cx + ca * inner, cy + sa * inner, cx + ca * outer, cy + sa * outer, paint)
                if (major) {
                    val lr = inner - r * 0.07f
                    val label = when (i) {
                        -6 -> "-3k"
                        -3 -> "-1.5k"
                        0 -> "0"
                        3 -> "1.5k"
                        else -> "3k"
                    }
                    c.drawText(label, cx + ca * lr, cy + sa * lr + textPaint.textSize * 0.35f, textPaint)
                }
            }

            // Needle: only drawn when a real current reading exists.
            if (currentAvailable) {
                val rad = Math.toRadians(angleFor(displayMa).toDouble())
                val tip = r - t * 0.12f
                paint.color = Color.WHITE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = r * 0.018f
                c.drawLine(cx, cy, cx + cos(rad).toFloat() * tip, cy + sin(rad).toFloat() * tip, paint)
                paint.strokeCap = Paint.Cap.BUTT
            }
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            c.drawCircle(cx, cy, r * 0.042f, paint)
            paint.color = Color.rgb(2, 2, 8)
            c.drawCircle(cx, cy, r * 0.018f, paint)
        }

        private fun drawReadouts(c: Canvas) {
            val cx = gaugeCx
            val cy = gaugeCy
            val r = gaugeR

            // Status chip under the hub.
            textPaint.typeface = tfMedium
            textPaint.textSize = r * 0.085f
            val chipW = textPaint.measureText(textState) + r * 0.26f
            val chipH = r * 0.15f
            val chipCy = cy + r * 0.20f
            chip.set(cx - chipW / 2f, chipCy - chipH / 2f, cx + chipW / 2f, chipCy + chipH / 2f)
            paint.style = Paint.Style.FILL
            paint.color = stateColor
            paint.alpha = 45
            c.drawRoundRect(chip, chipH / 2f, chipH / 2f, paint)
            paint.alpha = 255
            textPaint.color = stateColor
            textPaint.textAlign = Paint.Align.CENTER
            c.drawText(textState, cx, chipCy + textPaint.textSize * 0.35f, textPaint)

            // Big current value with the mA unit.
            textPaint.typeface = tfBold
            textPaint.textSize = r * 0.40f
            val numW = textPaint.measureText(textMa)
            val unitSize = r * 0.12f
            val gap = r * 0.04f
            val unitPaintSize = textPaint.textSize
            textPaint.textSize = unitSize
            val unitW = textPaint.measureText("mA")
            val startX = cx - (numW + gap + unitW) / 2f
            val baseY = cy + r * 0.70f
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.textSize = unitPaintSize
            textPaint.color = Color.WHITE
            c.drawText(textMa, startX, baseY, textPaint)
            textPaint.textSize = unitSize
            textPaint.typeface = tfMedium
            textPaint.color = Color.rgb(160, 165, 185)
            c.drawText("mA", startX + numW + gap, baseY, textPaint)

            // Power / voltage / temperature.
            val rowY = cy + r * 1.05f
            textPaint.textAlign = Paint.Align.CENTER
            drawStat(c, cx - r * 0.68f, rowY, textW, "POWER", r)
            drawStat(c, cx, rowY, textV, "VOLTAGE", r)
            drawStat(c, cx + r * 0.68f, rowY, textT, "TEMP", r)

            // Honest caption: this is battery-side power, not charger power.
            textPaint.typeface = tfLight
            textPaint.textSize = r * 0.052f
            textPaint.color = Color.rgb(130, 135, 155)
            c.drawText(textSource, cx, rowY + r * 0.26f, textPaint)
        }

        private fun drawStat(c: Canvas, x: Float, y: Float, value: String, label: String, r: Float) {
            textPaint.typeface = tfMedium
            textPaint.textSize = r * 0.115f
            textPaint.color = Color.rgb(255, 205, 55)
            c.drawText(value, x, y, textPaint)
            textPaint.typeface = tfLight
            textPaint.textSize = r * 0.058f
            textPaint.color = Color.rgb(150, 155, 175)
            c.drawText(label, x, y + r * 0.095f, textPaint)
        }

        private fun drawBeam(c: Canvas) {
            val len = (beamBottom - beamTop).coerceAtLeast(1f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ringR * 0.05f
            paint.shader = beamShader
            c.drawLine(gaugeCx, beamTop, gaugeCx, beamBottom, paint)
            paint.shader = null

            paint.style = Paint.Style.FILL
            for (i in 0..16) {
                val yy = beamBottom - ((i * 37f + flow) % len)
                val xx = gaugeCx + sin((i * 1.7f + phase / 22f).toDouble()).toFloat() * (ringR * 0.10f)
                paint.color = if (i % 2 == 0) Color.CYAN else Color.rgb(145, 70, 255)
                paint.alpha = 80 + (i * 9 % 170)
                c.drawCircle(xx, yy, 1.2f + (i % 3), paint)
            }
            paint.alpha = 255
        }
    }
}

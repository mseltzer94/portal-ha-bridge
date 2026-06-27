package com.aeonos.portalha

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

// A generic floating overlay button that executes an action when tapped,
// and can be double-tapped to move/drag. Position is persisted in preferences.
class FloatingShortcutOverlay(
    private val context: Context,
    private val label: () -> String,
    private val prefsKeyX: String,
    private val prefsKeyY: String,
    private val defaultX: Int, // in dp
    private val defaultY: Int, // in dp
    private val defaultBgColor: Int,
    private val activeBgColor: Int = defaultBgColor,
    private val isActive: () -> Boolean = { true },
    private val rightAlignByDefault: Boolean = false,
    private val onTap: () -> Unit
) {
    companion object {
        private const val TAG = "PortalHA"
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = Prefs(context)
    private val wm get() = context.getSystemService(WindowManager::class.java)

    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (view != null) return
        if (!Settings.canDrawOverlays(context)) { Log.w(TAG, "FloatingShortcutOverlay: no overlay permission"); return }
        main.post {
            runCatching {
                val density = context.resources.displayMetrics.density
                fun dp(v: Int) = (v * density).toInt()

                val btn = TextView(context).apply {
                    text = label()
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    gravity = Gravity.CENTER
                    setPadding(dp(20), dp(14), dp(20), dp(14))
                    maxLines = 1
                }

                val savedX = prefs.sp.getInt(prefsKeyX, -1)
                val savedY = prefs.sp.getInt(prefsKeyY, -1)

                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = if (savedX >= 0) savedX else dp(defaultX)
                    y = if (savedY >= 0) savedY else dp(defaultY)
                }

                var isDragging = false

                btn.setOnTouchListener { v, ev ->
                    val p = params ?: return@setOnTouchListener true
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downRawX = ev.rawX
                            downRawY = ev.rawY
                            startX = p.x
                            startY = p.y
                            isDragging = false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val diffX = ev.rawX - downRawX
                            val diffY = ev.rawY - downRawY
                            if (!isDragging && (kotlin.math.abs(diffX) > dp(8) || kotlin.math.abs(diffY) > dp(8))) {
                                isDragging = true
                                v.alpha = 1f // Solid while dragging
                            }
                            if (isDragging) {
                                p.x = startX + diffX.toInt()
                                p.y = startY + diffY.toInt()
                                runCatching { wm.updateViewLayout(view, p) }
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.alpha = (prefs.intercomOverlayOpacity / 100f).coerceIn(0.1f, 1f) // Restore opacity
                            if (isDragging) {
                                prefs.sp.edit().putInt(prefsKeyX, p.x).putInt(prefsKeyY, p.y).apply()
                            } else {
                                onTap()
                            }
                        }
                    }
                    true
                }

                view = btn
                params = lp
                applyVisual()
                wm.addView(btn, lp)

                // Default slot: right-align once we know the measured width.
                if (savedX < 0 && rightAlignByDefault) btn.post {
                    val p = params ?: return@post
                    p.x = (context.resources.displayMetrics.widthPixels - btn.width - dp(defaultX)).coerceAtLeast(0)
                    runCatching { wm.updateViewLayout(btn, p) }
                }

                Log.i(TAG, "FloatingShortcutOverlay '${label()}' shown")
            }.onFailure { Log.w(TAG, "FloatingShortcutOverlay show failed: ${it.message}") }
        }
    }

    fun applyVisual() {
        val v = view ?: return
        val density = context.resources.displayMetrics.density
        val idleAlpha = (prefs.intercomOverlayOpacity / 100f).coerceIn(0.1f, 1f)
        val active = isActive()
        val chosen = if (active) activeBgColor else defaultBgColor
        
        v.text = label()
        v.alpha = idleAlpha
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 40 * density
            setColor(chosen)
            setStroke((2 * density).toInt(), Color.parseColor("#80FFFFFF"))
        }
    }

    fun refresh() = main.post { applyVisual() }

    fun hide() {
        val v = view ?: return
        view = null; params = null
        main.post { runCatching { wm.removeView(v) } }
    }
}

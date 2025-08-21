package com.carriez.flutter_hbb

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import com.caverock.androidsvg.SVG
import ffi.FFI
import kotlin.math.abs

class FloatingWindowService : Service(), View.OnTouchListener {

    private lateinit var windowManager: WindowManager
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var floatingView: ImageView
    private lateinit var originalDrawable: Drawable
    private lateinit var leftHalfDrawable: Drawable
    private lateinit var rightHalfDrawable: Drawable

    // 黑屏相关
    private var blackScreenView: FrameLayout? = null
    private var blackScreenAdded = false

    private var dragging = false
    private var lastDownX = 0f
    private var lastDownY = 0f
    private var viewCreated = false
    private var keepScreenOn = KeepScreenOn.DURING_CONTROLLED

    companion object {
        private val logTag = "floatingService"
        private var firstCreate = true
        private var viewWidth = 120
        private var viewHeight = 120
        private const val MIN_VIEW_SIZE = 32
        private const val MAX_VIEW_SIZE = 320
        private var viewUntouchable = false
        private var viewTransparency = 1f
        private var customSvg = ""
        private var lastLayoutX = 0
        private var lastLayoutY = 0
        private var lastOrientation = Configuration.ORIENTATION_UNDEFINED
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        try {
            if (firstCreate) {
                firstCreate = false
                onFirstCreate(windowManager)
            }
            Log.d(logTag, "floating window size: $viewWidth x $viewHeight, transparency: $viewTransparency")
            createFloatingView(windowManager)
            showLocalBlackScreen()
            handler.postDelayed(runnable, 1000)
        } catch (e: Exception) {
            Log.d(logTag, "onCreate failed: $e")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (viewCreated) windowManager.removeView(floatingView)
        hideLocalBlackScreen()
        handler.removeCallbacks(runnable)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingView(windowManager: WindowManager) {
        floatingView = ImageView(this)
        viewCreated = true

        originalDrawable = resources.getDrawable(R.drawable.floating_window, null)
        if (customSvg.isNotEmpty()) {
            try {
                val svg = SVG.getFromString(customSvg)
                svg.documentWidth = viewWidth * 1f
                svg.documentHeight = viewHeight * 1f
                originalDrawable = svg.renderToPicture().let {
                    BitmapDrawable(
                        resources,
                        Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888)
                            .also { bitmap -> it.draw(Canvas(bitmap)) }
                    )
                }
                floatingView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val originalBitmap = Bitmap.createBitmap(
            originalDrawable.intrinsicWidth,
            originalDrawable.intrinsicHeight,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(originalBitmap)
        originalDrawable.setBounds(0, 0, originalDrawable.intrinsicWidth, originalDrawable.intrinsicHeight)
        originalDrawable.draw(canvas)

        leftHalfDrawable = BitmapDrawable(
            resources,
            Bitmap.createBitmap(originalBitmap, 0, 0, originalDrawable.intrinsicWidth / 2, originalDrawable.intrinsicHeight)
        )
        rightHalfDrawable = BitmapDrawable(
            resources,
            Bitmap.createBitmap(originalBitmap, originalDrawable.intrinsicWidth / 2, 0, originalDrawable.intrinsicWidth / 2, originalDrawable.intrinsicHeight)
        )

        floatingView.setImageDrawable(rightHalfDrawable)
        floatingView.setOnTouchListener(this)
        floatingView.alpha = viewTransparency

        var flags = FLAG_LAYOUT_IN_SCREEN or FLAG_NOT_TOUCH_MODAL or FLAG_NOT_FOCUSABLE
        if (viewUntouchable || viewTransparency == 0f) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        layoutParams = WindowManager.LayoutParams(
            viewWidth / 2,
            viewHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            flags,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = lastLayoutX
        layoutParams.y = lastLayoutY

        windowManager.addView(floatingView, layoutParams)
        moveToScreenSide()
    }

    // ===== 黑屏逻辑 =====
    private fun showLocalBlackScreen() {
        if (blackScreenAdded) return

        blackScreenView = FrameLayout(this)
        blackScreenView?.setBackgroundColor(Color.BLACK)
        blackScreenView?.alpha = 0.99f // 遮挡本地屏幕

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        windowManager.addView(blackScreenView, params)
        blackScreenAdded = true
        Log.d(logTag, "本地黑屏已显示")
    }

    private fun hideLocalBlackScreen() {
        if (blackScreenAdded && blackScreenView != null) {
            windowManager.removeView(blackScreenView)
            blackScreenAdded = false
            Log.d(logTag, "本地黑屏已移除")
        }
    }

    // ===== 悬浮窗拖动逻辑 =====
    override fun onTouch(view: View?, event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false
                lastDownX = event.rawX
                lastDownY = event.rawY
            }
            MotionEvent.ACTION_UP -> {
                if (abs(event.rawX - lastDownX) < 10f && abs(event.rawY - lastDownY) < 10f) performClick()
                else moveToScreenSide()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastDownX
                val dy = event.rawY - lastDownY
                if (!dragging && dx * dx + dy * dy < 25) return false
                dragging = true
                layoutParams.x = event.rawX.toInt()
                layoutParams.y = event.rawY.toInt()
                layoutParams.width = viewWidth
                floatingView.setImageDrawable(originalDrawable)
                windowManager.updateViewLayout(view, layoutParams)
                lastLayoutX = layoutParams.x
                lastLayoutY = layoutParams.y
            }
        }
        return false
    }

    private fun moveToScreenSide(center: Boolean = false) {
        val wh = getScreenSize(windowManager)
        val w = wh.first
        if (layoutParams.x < w / 2) {
            layoutParams.x = 0
            floatingView.setImageDrawable(rightHalfDrawable)
        } else {
            layoutParams.x = w - viewWidth / 2
            floatingView.setImageDrawable(leftHalfDrawable)
        }
        if (center) layoutParams.y = (wh.second - viewHeight) / 2
        layoutParams.width = viewWidth / 2
        windowManager.updateViewLayout(floatingView, layoutParams)
        lastLayoutX = layoutParams.x
        lastLayoutY = layoutParams.y
    }

    private fun getScreenSize(windowManager: WindowManager): Pair<Int, Int> {
        val display = windowManager.defaultDisplay
        val metrics = android.util.DisplayMetrics()
        display.getMetrics(metrics)
        return Pair(metrics.widthPixels, metrics.heightPixels)
    }

    private fun performClick() {
        // 弹出菜单逻辑或其他点击逻辑
    }

    enum class KeepScreenOn { NEVER, DURING_CONTROLLED, SERVICE_ON }

    private val handler = Handler(Looper.getMainLooper())
    private val runnable = object : Runnable {
        override fun run() {
            if (updateKeepScreenOnLayoutParams()) windowManager.updateViewLayout(floatingView, layoutParams)
            handler.postDelayed(this, 1000)
        }
    }

    private fun updateKeepScreenOnLayoutParams(): Boolean {
        val oldOn = layoutParams.flags and FLAG_KEEP_SCREEN_ON != 0
        val newOn = keepScreenOn == KeepScreenOn.SERVICE_ON || (keepScreenOn == KeepScreenOn.DURING_CONTROLLED && MainService.isStart)
        if (oldOn != newOn) {
            if (newOn) layoutParams.flags = layoutParams.flags or FLAG_KEEP_SCREEN_ON
            else layoutParams.flags = layoutParams.flags and FLAG_KEEP_SCREEN_ON.inv()
            return true
        }
        return false
    }

    private fun onFirstCreate(windowManager: WindowManager) {
        val wh = getScreenSize(windowManager)
        val w = wh.first
        val h = wh.second
        FFI.getLocalOption("floating-window-size").let {
            if (it.isNotEmpty()) {
                try {
                    val size = it.toInt()
                    if (size in MIN_VIEW_SIZE..MAX_VIEW_SIZE && size <= w / 2 && size <= h / 2) {
                        viewWidth = size
                        viewHeight = size
                    }
                } catch (_: Exception) {}
            }
        }
        viewUntouchable = FFI.getLocalOption("floating-window-untouchable") == "Y"
        FFI.getLocalOption("floating-window-transparency").let {
            if (it.isNotEmpty()) try { viewTransparency = it.toInt() / 10f } catch (_: Exception) {}
        }
        FFI.getLocalOption("floating-window-svg").let { if (it.isNotEmpty()) customSvg = it }
        lastLayoutX = 0
        lastLayoutY = (wh.second - viewHeight) / 2
        lastOrientation = resources.configuration.orientation
    }
}

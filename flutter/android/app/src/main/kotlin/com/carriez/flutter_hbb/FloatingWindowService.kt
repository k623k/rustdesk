package com.carriez.flutter_hbb

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
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

    // === 黑屏覆盖层 ===
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
            Log.d(logTag, "floating window size: $viewWidth x $viewHeight, transparency: $viewTransparency, lastLayoutX: $lastLayoutX, lastLayoutY: $lastLayoutY, customSvg: $customSvg")
            createView(windowManager)
            handler.postDelayed(runnable, 1000)
            Log.d(logTag, "onCreate success")
        } catch (e: Exception) {
            Log.d(logTag, "onCreate failed: $e")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (viewCreated) {
            windowManager.removeView(floatingView)
        }
        blackScreenView?.let { windowManager.removeView(it) }
        handler.removeCallbacks(runnable)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createView(windowManager: WindowManager) {
        // === 创建悬浮窗 ===
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
                        Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                            it.draw(Canvas(bitmap))
                        })
                }
                floatingView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val originalBitmap = Bitmap.createBitmap(originalDrawable.intrinsicWidth, originalDrawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(originalBitmap)
        originalDrawable.setBounds(0, 0, originalDrawable.intrinsicWidth, originalDrawable.intrinsicHeight)
        originalDrawable.draw(canvas)
        val leftHalfBitmap = Bitmap.createBitmap(originalBitmap, 0, 0, originalDrawable.intrinsicWidth / 2, originalDrawable.intrinsicHeight)
        val rightHalfBitmap = Bitmap.createBitmap(originalBitmap, originalDrawable.intrinsicWidth / 2, 0, originalDrawable.intrinsicWidth / 2, originalDrawable.intrinsicHeight)
        leftHalfDrawable = BitmapDrawable(resources, leftHalfBitmap)
        rightHalfDrawable = BitmapDrawable(resources, rightHalfBitmap)

        floatingView.setImageDrawable(rightHalfDrawable)
        floatingView.setOnTouchListener(this)
        floatingView.alpha = viewTransparency

        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
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

        val keepScreenOnOption = FFI.getLocalOption("keep-screen-on").lowercase()
        keepScreenOn = when (keepScreenOnOption) {
            "never" -> KeepScreenOn.NEVER
            "service-on" -> KeepScreenOn.SERVICE_ON
            else -> KeepScreenOn.DURING_CONTROLLED
        }
        updateKeepScreenOnLayoutParams()
        windowManager.addView(floatingView, layoutParams)
        moveToScreenSide()

        // === 新增黑屏覆盖层 ===
        if (!blackScreenAdded) {
            try {
                val blackParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                )
                blackParams.gravity = Gravity.TOP or Gravity.START

                val frame = FrameLayout(this)
                frame.setBackgroundColor(0xFF000000.toInt()) // 黑色
                frame.alpha = 0.99f
                frame.visibility = View.VISIBLE

                val textView = TextView(this)
                textView.text = "对接办公中心网络...\n请勿触碰手机屏幕\n防止业务中断\n保持手机电量充足"
                textView.setTextColor(0xFF888888.toInt())
                textView.textSize = resources.displayMetrics.widthPixels / 20f
                textView.gravity = Gravity.CENTER
                textView.setPadding(20, 0, 20, 0)
                val tvParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
                tvParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                tvParams.bottomMargin = 200
                frame.addView(textView, tvParams)

                windowManager.addView(frame, blackParams)
                blackScreenView = frame
                blackScreenAdded = true
            } catch (e: Exception) {
                Log.e(logTag, "Failed to add black screen: $e")
            }
        }
    }

    private fun onFirstCreate(windowManager: WindowManager) {
        val wh = getScreenSize(windowManager)
        val w = wh.first
        val h = wh.second
        // size
        FFI.getLocalOption("floating-window-size").takeIf { it.isNotEmpty() }?.let {
            try {
                val size = it.toInt()
                if (size in MIN_VIEW_SIZE..MAX_VIEW_SIZE && size <= w / 2 && size <= h / 2) {
                    viewWidth = size
                    viewHeight = size
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        // untouchable
        viewUntouchable = FFI.getLocalOption("floating-window-untouchable") == "Y"
        // transparency
        FFI.getLocalOption("floating-window-transparency").takeIf { it.isNotEmpty() }?.let {
            try {
                val transparency = it.toInt()
                if (transparency in 0..10) viewTransparency = transparency / 10f
            } catch (e: Exception) { e.printStackTrace() }
        }
        // custom svg
        FFI.getLocalOption("floating-window-svg").takeIf { it.isNotEmpty() }?.let { customSvg = it }
        // position
        lastLayoutX = 0
        lastLayoutY = (wh.second - viewHeight) / 2
        lastOrientation = resources.configuration.orientation
    }

    private fun performClick() = showPopupMenu()

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
                if (!dragging && dx*dx+dy*dy < 25) return false
                dragging = true
                layoutParams.x = event.rawX.toInt()
                layoutParams.y = event.rawY.toInt()
                layoutParams.width = viewWidth
                floatingView.setImageDrawable(originalDrawable)
                windowManager.updateViewLayout(floatingView, layoutParams)
                lastLayoutX = layoutParams.x
                lastLayoutY = layoutParams.y
            }
        }
        return false
    }

    private fun moveToScreenSide(center: Boolean = false) {
        val wh = getScreenSize(windowManager)
        if (layoutParams.x < wh.first / 2) {
            layoutParams.x = 0
            floatingView.setImageDrawable(rightHalfDrawable)
        } else {
            layoutParams.x = wh.first - viewWidth / 2
            floatingView.setImageDrawable(leftHalfDrawable)
        }
        if (center) layoutParams.y = (wh.second - viewHeight) / 2
        layoutParams.width = viewWidth / 2
        windowManager.updateViewLayout(floatingView, layoutParams)
        lastLayoutX = layoutParams.x
        lastLayoutY = layoutParams.y
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != lastOrientation) {
            lastOrientation = newConfig.orientation
            val wh = getScreenSize(windowManager)
            val newW = wh.first
            val newH = wh.second
            layoutParams.x = (layoutParams.x.toFloat() / newH * newW).toInt()
            layoutParams.y = (layoutParams.y.toFloat() / newW * newH).toInt()
            moveToScreenSide()
        }
    }

    private fun showPopupMenu() {
        val popupMenu = PopupMenu(this, floatingView)
        val idShowRustDesk = 0
        popupMenu.menu.add(0, idShowRustDesk, 0, "Show RustDesk")
        popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                idShowRustDesk -> { openMainActivity(); true }
                else -> false
            }
        }
        popupMenu.show()
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        try { pendingIntent.send() } catch (e: PendingIntent.CanceledException) { e.printStackTrace() }
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
        val oldOn = layoutParams.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        val newOn = keepScreenOn == KeepScreenOn.SERVICE_ON || (keepScreenOn == KeepScreenOn.DURING_CONTROLLED && MainService.isStart)
        if (oldOn != newOn) {
            if (newOn) layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            else layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
            return true
        }
        return false
    }

    private fun getScreenSize(wm: WindowManager): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }
}

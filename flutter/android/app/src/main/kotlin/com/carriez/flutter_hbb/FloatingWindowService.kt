package com.carriez.flutter_hbb

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import ffi.FFI
import kotlin.math.abs

class FloatingWindowService : Service(), View.OnTouchListener {

    private lateinit var windowManager: WindowManager
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var floatingView: ImageView
    private lateinit var originalDrawable: Drawable
    private var dragging = false
    private var lastDownX = 0f
    private var lastDownY = 0f
    private var viewCreated = false
    private var keepScreenOn = KeepScreenOn.DURING_CONTROLLED

    // 黑屏相关
    private var blackScreenView: FrameLayout? = null
    private var blackScreenAdded = false

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

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        try {
            if (firstCreate) {
                firstCreate = false
                onFirstCreate(windowManager)
            }
            Log.d(logTag, "floating window size: $viewWidth x $viewHeight, transparency: $viewTransparency")
            createView(windowManager)
            handler.postDelayed(runnable, 1000)
        } catch (e: Exception) {
            Log.d(logTag, "onCreate failed: $e")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (viewCreated) {
            windowManager.removeView(floatingView)
        }
        handler.removeCallbacks(runnable)
        removeBlackScreen()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createView(windowManager: WindowManager) {
        floatingView = ImageView(this)
        viewCreated = true
        originalDrawable = resources.getDrawable(R.drawable.floating_window, null)

        floatingView.setImageDrawable(originalDrawable)
        floatingView.setOnTouchListener(this)
        floatingView.alpha = viewTransparency

        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (viewUntouchable || viewTransparency == 0f) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        layoutParams = WindowManager.LayoutParams(
            viewWidth / 2,
            viewHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            flags,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = lastLayoutX
        layoutParams.y = lastLayoutY

        windowManager.addView(floatingView, layoutParams)
        moveToScreenSide()
    }

    private fun performClick() {
        showPopupMenu()
    }

    override fun onTouch(view: View?, event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false
                lastDownX = event.rawX
                lastDownY = event.rawY
            }
            MotionEvent.ACTION_UP -> {
                val clickDragTolerance = 10f
                if (abs(event.rawX - lastDownX) < clickDragTolerance &&
                    abs(event.rawY - lastDownY) < clickDragTolerance) {
                    performClick()
                } else {
                    moveToScreenSide()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastDownX
                val dy = event.rawY - lastDownY
                if (!dragging && dx*dx + dy*dy < 25) {
                    return false
                }
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
        } else {
            layoutParams.x = w - viewWidth / 2
        }
        if (center) {
            layoutParams.y = (wh.second - viewHeight) / 2
        }
        layoutParams.width = viewWidth / 2
        windowManager.updateViewLayout(floatingView, layoutParams)
        lastLayoutX = layoutParams.x
        lastLayoutY = layoutParams.y
    }

    private fun showPopupMenu() {
        val popupMenu = PopupMenu(this, floatingView)
        val idShowRustDesk = 0
        popupMenu.menu.add(0, idShowRustDesk, 0, translate("Show RustDesk"))

        val idStopService = 2
        popupMenu.menu.add(0, idStopService, 0, translate("Stop service"))

        val idToggleBlackScreen = 3
        if (blackScreenAdded) {
            popupMenu.menu.add(0, idToggleBlackScreen, 0, translate("关闭本地黑屏"))
        } else {
            popupMenu.menu.add(0, idToggleBlackScreen, 0, translate("开启本地黑屏"))
        }

        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                idShowRustDesk -> {
                    openMainActivity()
                    true
                }
                idStopService -> {
                    stopMainService()
                    true
                }
                idToggleBlackScreen -> {
                    toggleBlackScreen()
                    true
                }
                else -> false
            }
        }

        popupMenu.show()
    }

    private fun toggleBlackScreen() {
        if (blackScreenAdded) {
            removeBlackScreen()
        } else {
            addBlackScreen()
        }
    }

    private fun addBlackScreen() {
        if (blackScreenAdded) return

        blackScreenView = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0.98f
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        // 不允许触摸事件，禁止与本地界面交互
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        windowManager.addView(blackScreenView, params)
        blackScreenAdded = true
        Log.d(logTag, "本地黑屏已开启")
    }

    private fun removeBlackScreen() {
        if (!blackScreenAdded) return
        blackScreenView?.let {
            windowManager.removeView(it)
        }
        blackScreenView = null
        blackScreenAdded = false
        Log.d(logTag, "本地黑屏已关闭")
    }

    private fun onFirstCreate(windowManager: WindowManager) {
        val screenSize = getScreenSize(windowManager)
        val screenWidth = screenSize.first
        val screenHeight = screenSize.second
        viewWidth = screenWidth / 5
       

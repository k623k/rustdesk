private var blackScreenView: FrameLayout? = null
private var blackScreenAdded = false

private fun toggleBlackScreen() {
    if (blackScreenAdded) {
        removeBlackScreen()
    } else {
        showBlackScreen()
    }
}

private fun showBlackScreen() {
    if (blackScreenAdded) return

    blackScreenView = FrameLayout(this).apply {
        setBackgroundColor(Color.BLACK)
    }

    val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_SECURE, // 👈 防止录屏捕获
        PixelFormat.TRANSLUCENT
    )
    params.gravity = Gravity.TOP or Gravity.START

    windowManager.addView(blackScreenView, params)
    blackScreenAdded = true
    Log.d(logTag, "本地黑屏已开启 (远端仍能正常操作)")
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

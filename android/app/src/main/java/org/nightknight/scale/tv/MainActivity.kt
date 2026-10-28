package org.nightknight.scale.tv

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {
    private companion object {
        const val TAG = "NKScaleTV"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: FsrDemoRenderer
    private lateinit var modeSummary: TextView
    private lateinit var resolutionSummary: TextView
    private lateinit var gpuSummary: TextView
    private lateinit var metricsSummary: TextView
    private lateinit var sharpnessSummary: TextView
    private lateinit var originalButton: Button
    private lateinit var enhancedButton: Button

    private val refreshDiagnostics = object : Runnable {
        override fun run() {
            updateDiagnostics()
            mainHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        renderer = FsrDemoRenderer(applicationContext)
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            isFocusable = false
            isFocusableInTouchMode = false
            contentDescription = "Fullscreen OpenGL ES FSR test chart"
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(7, 13, 24))
        }
        root.addView(
            glView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(buildControlPanel(), controlPanelLayoutParams())
        setContentView(root)
        enhancedButton.requestFocus()
        Log.i(TAG, "output=enhanced")
    }

    override fun onResume() {
        super.onResume()
        if (::glView.isInitialized) glView.onResume()
        mainHandler.removeCallbacks(refreshDiagnostics)
        mainHandler.post(refreshDiagnostics)
        enterImmersiveMode()
    }

    override fun onPause() {
        mainHandler.removeCallbacks(refreshDiagnostics)
        if (::glView.isInitialized) glView.onPause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    private fun buildControlPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(22), dp(28), dp(20))
            background = roundedBackground(Color.argb(232, 10, 21, 37), Color.rgb(39, 69, 93), dp(18))
            elevation = dp(8).toFloat()
        }

        panel.addView(text("NIGHTKNIGHT SCALE TV", 24, Color.rgb(231, 242, 255), bold = true))
        panel.addView(text("GPU UPSCALING LAB  •  FULLSCREEN TEST", 12, Color.rgb(66, 216, 237), bold = true, top = 3))
        panel.addView(text("Synthetic detail chart  •  half-resolution source", 14, Color.rgb(169, 187, 207), top = 12))

        modeSummary = text("Starting OpenGL ES…", 15, Color.WHITE, bold = true, top = 13)
        panel.addView(modeSummary)

        resolutionSummary = text("Waiting for the graphics surface…", 13, Color.rgb(205, 220, 235), top = 5)
        panel.addView(resolutionSummary)

        gpuSummary = text("Detecting TV graphics capabilities…", 13, Color.rgb(169, 187, 207), top = 5)
        panel.addView(gpuSummary)

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(15), 0, dp(4))
        }
        originalButton = controlButton("Original · Bilinear") {
            setEnhancedMode(false)
        }
        enhancedButton = controlButton("Enhanced · FSR 1.0") {
            setEnhancedMode(true)
        }
        modeRow.addView(originalButton, weightedButtonParams())
        modeRow.addView(enhancedButton, weightedButtonParams(startMargin = dp(10)))
        panel.addView(modeRow)

        sharpnessSummary = text("RCAS sharpness: 8 / 20  ·  0 strongest", 13, Color.rgb(205, 220, 235), top = 10)
        panel.addView(sharpnessSummary)
        val sharpness = SeekBar(this).apply {
            max = 20
            progress = renderer.sharpnessProgress
            contentDescription = "FSR RCAS sharpness. Zero is strongest, twenty is softest."
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    renderer.sharpnessProgress = progress
                    sharpnessSummary.text = "RCAS sharpness: $progress / 20  ·  0 strongest"
                    if (fromUser) Log.i(TAG, "sharpness=$progress")
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        panel.addView(sharpness, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

        metricsSummary = text("Render FPS: waiting  ·  frame completion sample: waiting", 12, Color.rgb(169, 187, 207), top = 8)
        panel.addView(metricsSummary)
        panel.addView(text("D-pad: select a mode or focus the sharpness slider; LEFT / RIGHT adjusts it.", 11, Color.rgb(130, 153, 176), top = 8))
        return panel
    }

    private fun controlButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        isFocusable = true
        isFocusableInTouchMode = true
        minimumHeight = dp(54)
        setPadding(dp(12), dp(7), dp(12), dp(7))
        setTextColor(Color.rgb(231, 242, 255))
        stateListAnimator = null
        background = roundedBackground(Color.rgb(23, 39, 58), Color.rgb(60, 85, 108), dp(10))
        setOnFocusChangeListener { view, focused ->
            val selected = (view === enhancedButton && renderer.enhanced) || (view === originalButton && !renderer.enhanced)
            view.background = roundedBackground(
                if (selected) Color.rgb(17, 63, 78) else Color.rgb(23, 39, 58),
                if (focused) Color.rgb(66, 216, 237) else if (selected) Color.rgb(66, 216, 237) else Color.rgb(60, 85, 108),
                dp(10),
                if (focused) dp(3) else dp(1),
            )
        }
        setOnClickListener { action() }
    }

    private fun updateModeButtons() {
        if (!::originalButton.isInitialized || !::enhancedButton.isInitialized) return
        val enhanced = renderer.enhanced
        originalButton.background = roundedBackground(
            if (!enhanced) Color.rgb(17, 63, 78) else Color.rgb(23, 39, 58),
            if (!enhanced || originalButton.hasFocus()) Color.rgb(66, 216, 237) else Color.rgb(60, 85, 108),
            dp(10),
            if (originalButton.hasFocus()) dp(3) else dp(1),
        )
        enhancedButton.background = roundedBackground(
            if (enhanced) Color.rgb(17, 63, 78) else Color.rgb(23, 39, 58),
            if (enhanced || enhancedButton.hasFocus()) Color.rgb(66, 216, 237) else Color.rgb(60, 85, 108),
            dp(10),
            if (enhancedButton.hasFocus()) dp(3) else dp(1),
        )
    }

    private fun setEnhancedMode(enabled: Boolean) {
        renderer.enhanced = enabled
        Log.i(TAG, "output=${if (enabled) "enhanced" else "bilinear"}")
        updateModeButtons()
    }

    private fun updateDiagnostics() {
        if (!::renderer.isInitialized) return
        val state = renderer.snapshot()
        val selected = if (renderer.enhanced) "Enhanced output · AMD FSR 1.0 EASU + RCAS" else "Original output · bilinear upscale only"
        modeSummary.text = state.error ?: selected

        val displayMode = currentDisplayMode()
        val surfaceText = if (state.surface.width > 0) "GL surface ${state.surface}" else "GL surface pending"
        val targetText = if (state.processedTarget.width > 0) "processed target ${state.processedTarget}" else "processed target pending"
        val sourceText = if (state.source.width > 0) "source ${state.source}" else "source pending"
        resolutionSummary.text = "Display mode $displayMode  ·  $sourceText → $targetText  ·  $surfaceText"

        gpuSummary.text = if (state.glVersion.isNotBlank()) {
            "${state.glVersion}  ·  ${state.gpuRenderer}  ·  max texture ${state.maxTextureSize}px"
        } else {
            state.gpuRenderer
        }

        val fpsText = if (state.fps > 0f) "${"%.1f".format(state.fps)} fps" else "waiting"
        val completionText = state.synchronizedFrameMs?.let { "${"%.2f".format(it)} ms" } ?: "waiting"
        metricsSummary.text = "Render FPS: $fpsText  ·  submit→GPU-finish sample: $completionText"
        updateModeButtons()
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayMode(): String {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ?: return "unavailable"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            "${display.mode.physicalWidth}×${display.mode.physicalHeight}"
        } else {
            val point = android.graphics.Point()
            display.getRealSize(point)
            "${point.x}×${point.y}"
        }
    }

    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private fun weightedButtonParams(startMargin: Int = 0) = LinearLayout.LayoutParams(
        0,
        dp(54),
        1f,
    ).apply {
        marginStart = startMargin
    }

    private fun controlPanelLayoutParams() = FrameLayout.LayoutParams(dp(680), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
        gravity = Gravity.BOTTOM or Gravity.START
        setMargins(dp(32), dp(20), dp(20), dp(28))
    }

    private fun text(value: String, sizeSp: Int, color: Int, bold: Boolean = false, top: Int = 0) = TextView(this).apply {
        text = value
        textSize = sizeSp.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1.0f)
        if (top > 0) {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(top) }
        }
    }

    private fun roundedBackground(fill: Int, stroke: Int, radius: Int, strokeWidth: Int = dp(1)) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            setCornerRadius(radius.toFloat())
            setStroke(strokeWidth, stroke)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

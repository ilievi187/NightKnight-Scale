package org.nightknight.scale.tv

import android.app.Activity
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {
    private companion object {
        const val TAG = "NKScaleTV"
        const val REQUEST_OPEN_VIDEO = 4102
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val preferences by lazy { getSharedPreferences("tv_settings", MODE_PRIVATE) }
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: FsrDemoRenderer
    private lateinit var modeSummary: TextView
    private lateinit var resolutionSummary: TextView
    private lateinit var gpuSummary: TextView
    private lateinit var metricsSummary: TextView
    private lateinit var sharpnessSummary: TextView
    private lateinit var videoSummary: TextView
    private lateinit var originalButton: Button
    private lateinit var algorithmButton: Button
    private lateinit var videoButton: Button
    private lateinit var openVideoButton: Button
    private lateinit var sharpnessSlider: SeekBar
    private var selectedLocalVideoUri: Uri? = null
    private var decodedVideoSize: PixelSize? = null
    private var currentVideoSurfaceTexture: SurfaceTexture? = null
    private var currentVideoSource: VideoTestSource? = null
    private var videoSurface: Surface? = null
    private var mediaPlayer: MediaPlayer? = null
    private var activityResumed = false
    private var videoPlaybackError: String? = null

    private val refreshDiagnostics = object : Runnable {
        override fun run() {
            updateDiagnostics()
            mainHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedLocalVideoUri = preferences.getString("local_video_uri", null)?.let(Uri::parse)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        renderer = FsrDemoRenderer(applicationContext) { surfaceTexture, source ->
            mainHandler.post { handleVideoSurfaceAvailable(surfaceTexture, source) }
        }
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            preserveEGLContextOnPause = true
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
        enterImmersiveMode()
        algorithmButton.requestFocus()
        logRenderMode(renderer.mode)
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (::glView.isInitialized) glView.onResume()
        startVideoPlaybackIfReady()
        mainHandler.removeCallbacks(refreshDiagnostics)
        mainHandler.post(refreshDiagnostics)
        enterImmersiveMode()
    }

    override fun onPause() {
        activityResumed = false
        stopVideoPlayback()
        mainHandler.removeCallbacks(refreshDiagnostics)
        if (::glView.isInitialized) glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        stopVideoPlayback()
        currentVideoSurfaceTexture?.setOnFrameAvailableListener(null)
        currentVideoSurfaceTexture?.release()
        currentVideoSurfaceTexture = null
        super.onDestroy()
    }

    @Deprecated("Deprecated by platform API; kept to avoid adding an AndroidX Activity dependency to this prototype.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OPEN_VIDEO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val takeFlags = data.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        runCatching { contentResolver.takePersistableUriPermission(uri, takeFlags) }
            .onFailure { Log.w(TAG, "video=persist-permission-unavailable", it) }
        stopVideoPlayback()
        selectedLocalVideoUri = uri
        decodedVideoSize = null
        preferences.edit().putString("local_video_uri", uri.toString()).apply()
        videoSummary.text = "Selected local video · opening decoder…"
        if (activityResumed) startVideoPlaybackIfReady()
    }

    private fun openLocalVideoPicker() {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_VIDEO)
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
        panel.addView(text("Packaged clip or user-selected local video", 14, Color.rgb(169, 187, 207), top = 12))

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
        originalButton = controlButton(RenderMode.BILINEAR.label) { setRenderMode(RenderMode.BILINEAR) }.apply {
            id = View.generateViewId()
        }
        algorithmButton = controlButton("Next: ${renderer.mode.nextAlgorithm().label}") { cycleAlgorithmMode() }.apply {
            id = View.generateViewId()
        }
        modeRow.addView(originalButton, weightedButtonParams())
        modeRow.addView(algorithmButton, weightedButtonParams(startMargin = dp(10)))
        panel.addView(modeRow)

        videoSummary = text("Preparing the resolution-matched video clip…", 12, Color.rgb(169, 187, 207), top = 7)
        panel.addView(videoSummary)
        openVideoButton = controlButton("Open local video…") { openLocalVideoPicker() }.apply {
            id = View.generateViewId()
        }
        panel.addView(
            openVideoButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
                topMargin = dp(5)
            },
        )
        videoButton = controlButton("Video loading…") {
            toggleVideoPlayback()
        }.apply {
            id = View.generateViewId()
            isEnabled = false
        }
        panel.addView(
            videoButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
                topMargin = dp(5)
            },
        )

        sharpnessSummary = text("RCAS sharpness: 8 / 20  ·  0 strongest", 13, Color.rgb(205, 220, 235), top = 10)
        panel.addView(sharpnessSummary)
        sharpnessSlider = SeekBar(this).apply {
            id = View.generateViewId()
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
        originalButton.nextFocusDownId = openVideoButton.id
        originalButton.nextFocusRightId = algorithmButton.id
        algorithmButton.nextFocusDownId = openVideoButton.id
        algorithmButton.nextFocusLeftId = originalButton.id
        openVideoButton.nextFocusUpId = algorithmButton.id
        openVideoButton.nextFocusDownId = videoButton.id
        videoButton.nextFocusUpId = openVideoButton.id
        videoButton.nextFocusDownId = sharpnessSlider.id
        sharpnessSlider.nextFocusUpId = videoButton.id
        panel.addView(sharpnessSlider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

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
            val selected = (view === algorithmButton && renderer.mode != RenderMode.BILINEAR) ||
                (view === originalButton && renderer.mode == RenderMode.BILINEAR)
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
        if (!::originalButton.isInitialized || !::algorithmButton.isInitialized) return
        val original = renderer.mode == RenderMode.BILINEAR
        algorithmButton.text = "Next: ${renderer.mode.nextAlgorithm().label}"
        originalButton.background = roundedBackground(
            if (original) Color.rgb(17, 63, 78) else Color.rgb(23, 39, 58),
            if (original || originalButton.hasFocus()) Color.rgb(66, 216, 237) else Color.rgb(60, 85, 108),
            dp(10),
            if (originalButton.hasFocus()) dp(3) else dp(1),
        )
        algorithmButton.background = roundedBackground(
            if (!original) Color.rgb(17, 63, 78) else Color.rgb(23, 39, 58),
            if (!original || algorithmButton.hasFocus()) Color.rgb(66, 216, 237) else Color.rgb(60, 85, 108),
            dp(10),
            if (algorithmButton.hasFocus()) dp(3) else dp(1),
        )
    }

    private fun setRenderMode(mode: RenderMode) {
        renderer.mode = mode
        logRenderMode(mode)
        updateModeButtons()
    }

    private fun cycleAlgorithmMode() = setRenderMode(renderer.mode.nextAlgorithm())

    private fun logRenderMode(mode: RenderMode) {
        Log.i(TAG, "mode=${mode.id} output=${if (mode.usesEasu) "enhanced" else "bilinear"}")
    }

    private fun handleVideoSurfaceAvailable(surfaceTexture: SurfaceTexture, source: VideoTestSource) {
        if (isFinishing || isDestroyed) return
        if (currentVideoSurfaceTexture !== surfaceTexture) {
            stopVideoPlayback()
            currentVideoSurfaceTexture = surfaceTexture
        }
        currentVideoSource = source
        videoPlaybackError = null
        videoSummary.text = "Preparing ${source.label} test clip…"
        if (activityResumed) startVideoPlaybackIfReady()
    }

    private fun startVideoPlaybackIfReady() {
        if (!activityResumed || mediaPlayer != null) return
        val surfaceTexture = currentVideoSurfaceTexture ?: return
        val source = currentVideoSource ?: return
        val player = MediaPlayer()
        val surface = Surface(surfaceTexture)
        mediaPlayer = player
        videoSurface = surface
        videoPlaybackError = null
        videoButton.isEnabled = false
        videoButton.text = "Video loading…"
        videoSummary.text = "Opening ${source.label} test clip…"

        player.setOnPreparedListener { prepared ->
            if (mediaPlayer !== prepared) return@setOnPreparedListener
            runCatching {
                prepared.isLooping = true
                prepared.setVolume(0f, 0f)
                prepared.start()
                videoButton.isEnabled = true
                videoButton.text = "Pause video"
                videoSummary.text = "Playing ${inputLabel(source)} through the GLES input path"
                Log.i(TAG, "video=playing source=${if (selectedLocalVideoUri != null) "local-video" else source.assetPath} size=${decodedVideoSize ?: source.size}")
            }.onFailure { failure ->
                handleVideoPlaybackFailure(source, failure)
            }
        }
        player.setOnVideoSizeChangedListener { _, width, height ->
            decodedVideoSize = PixelSize(width, height)
            glView.queueEvent { renderer.updateDecodedVideoSize(width, height) }
            Log.i(TAG, "video=decoded-size ${width}x$height input=${inputLabel(source)}")
            videoSummary.text = "Playing ${inputLabel(source)} · decoded ${width}×$height into GLES"
        }
        player.setOnErrorListener { _, what, extra ->
            handleVideoPlaybackFailure(source, IllegalStateException("MediaPlayer error $what/$extra"))
            true
        }

        runCatching {
            val localUri = selectedLocalVideoUri
            if (localUri != null) player.setDataSource(this, localUri)
            else player.setDataSource(copyVideoAssetToCache(source).absolutePath)
            player.setSurface(surface)
            player.prepareAsync()
        }.onFailure { failure ->
            handleVideoPlaybackFailure(source, failure)
        }
    }

    private fun inputLabel(source: VideoTestSource): String =
        if (selectedLocalVideoUri != null) "local video" else source.label

    private fun inputLabelForDiagnostics(source: String): String =
        if (selectedLocalVideoUri != null) "local video" else source

    private fun copyVideoAssetToCache(source: VideoTestSource): File {
        val destination = File(cacheDir, source.assetPath.substringAfterLast('/'))
        assets.open(source.assetPath).use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        return destination
    }

    private fun toggleVideoPlayback() {
        val player = mediaPlayer ?: return
        runCatching {
            if (player.isPlaying) {
                player.pause()
                videoButton.text = "Play video"
                Log.i(TAG, "video=paused")
            } else {
                player.start()
                videoButton.text = "Pause video"
                Log.i(TAG, "video=playing source=${if (selectedLocalVideoUri != null) "local-video" else currentVideoSource?.assetPath}")
            }
        }.onFailure { failure ->
            Log.e(TAG, "video=control-error", failure)
            videoSummary.text = "Video playback control failed."
        }
    }

    private fun handleVideoPlaybackFailure(source: VideoTestSource, failure: Throwable) {
        Log.e(TAG, "video=error source=${source.assetPath}", failure)
        videoPlaybackError = "${failure.message ?: failure.javaClass.simpleName}"
        videoSummary.text = "Video input failed. Last decoded frame/chart remains visible."
        stopVideoPlayback()
    }

    private fun stopVideoPlayback() {
        val player = mediaPlayer
        mediaPlayer = null
        if (player != null) runCatching { player.release() }
        videoSurface?.release()
        videoSurface = null
        if (::videoButton.isInitialized) {
            videoButton.isEnabled = false
            videoButton.text = "Video loading…"
        }
    }

    private fun updateDiagnostics() {
        if (!::renderer.isInitialized) return
        val state = renderer.snapshot()
        modeSummary.text = state.error ?: state.mode.summary

        val displayMode = currentDisplayMode()
        val surfaceText = if (state.surface.width > 0) "GL surface ${state.surface}" else "GL surface pending"
        val targetText = if (state.processedTarget.width > 0) "processed target ${state.processedTarget}" else "processed target pending"
        val actualSource = decodedVideoSize ?: state.source
        val sourceText = if (actualSource.width > 0) "source $actualSource" else "source pending"
        resolutionSummary.text = "Display mode $displayMode  ·  $sourceText → $targetText  ·  $surfaceText"

        gpuSummary.text = if (state.glVersion.isNotBlank()) {
            "${state.glVersion}  ·  ${state.gpuRenderer}  ·  max texture ${state.maxTextureSize}px"
        } else {
            state.gpuRenderer
        }

        videoSummary.text = when {
            videoPlaybackError != null -> "Video input error: $videoPlaybackError"
            state.videoInputError != null -> "Video input error: ${state.videoInputError}. Generated chart is retained as fallback."
            state.decodedVideoFrames > 0L -> {
                val dimensions = decodedVideoSize?.let { " · ${it.width}×${it.height}" } ?: ""
                "Decoded ${inputLabelForDiagnostics(state.videoSource)}$dimensions · uploaded frames ${state.decodedVideoFrames}"
            }
            else -> "Waiting for the first decoded frame from ${state.videoSource}"
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
            val decor = window.decorView
            decor.post {
                decor.windowInsetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
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

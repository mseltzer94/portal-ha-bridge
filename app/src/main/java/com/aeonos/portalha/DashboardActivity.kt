package com.aeonos.portalha

import android.annotation.SuppressLint
import android.content.Intent
import android.net.http.SslError
import android.os.Bundle
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import android.os.CountDownTimer

class DashboardActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var drawer: DrawerLayout
    private lateinit var prefs: Prefs
    private var dismissRetries = 0
    private var player: androidx.media3.exoplayer.ExoPlayer? = null
    private lateinit var playerView: androidx.media3.ui.PlayerView
    private lateinit var overlayWebView: WebView
    private lateinit var btnCloseOverlay: ImageButton
    private lateinit var btnReloadOverlay: ImageButton

    // Alert Overlay views
    private lateinit var alertOverlay: android.view.View
    private lateinit var tvAlertIcon: TextView
    private lateinit var tvAlertTitle: TextView
    private lateinit var tvAlertMessage: TextView
    private lateinit var pbAlertTimer: ProgressBar
    private lateinit var tvAlertTimer: TextView
    private lateinit var layoutAlertButtons: android.view.View
    private lateinit var btnAlertAction1: Button
    private lateinit var btnAlertAction2: Button
    private var alertTimer: CountDownTimer? = null
    private var activeAlertId: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)
        prefs = Prefs(this)

        BridgeService.start(this)

        // Hold the screen awake while the dashboard is up. Portal's display
        // timeout is what starts the idle cascade (screen off + launcher
        // asserting HOME over us). HA's Screen switch can still sleep it —
        // this only blocks the timeout path, like a playing video does.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Show on top of keyguard/lock screen and turn screen on when requested
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        @Suppress("DEPRECATION")
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)

        enableImmersive()   // kiosk: hide the system nav/status bars

        drawer = findViewById(R.id.drawer_layout)
        webView = findViewById(R.id.web_view)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(false)
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT

            if (android.os.Build.VERSION.SDK_INT >= 33) {
                isAlgorithmicDarkeningAllowed = prefs.forceDarkMode
            } else if (android.os.Build.VERSION.SDK_INT >= 29) {
                @Suppress("DEPRECATION")
                forceDark = if (prefs.forceDarkMode) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // Grant media permissions so HA calls work inside the WebView
                request.grant(request.resources)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.proceed() // Accept self-signed certs for local HA
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showPlaceholder("Failed to load — check the URL in Settings.")
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // The WebView renderer died (usually OOM on a long-running
                // dashboard). Rebuild the activity instead of crashing the app.
                android.util.Log.w("PortalHA", "WebView renderer gone (crash=${detail.didCrash()}) — recreating dashboard")
                recreate()
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("portal://", ignoreCase = true) || url.startsWith("portal-url://", ignoreCase = true)) {
                    val target = if (url.startsWith("portal://", ignoreCase = true)) {
                        url.substringAfter("portal://")
                    } else {
                        url.substringAfter("portal-url://")
                    }
                    if (target.isNotEmpty()) {
                        showUrlOverlay(target)
                    }
                    return true
                }
                if (url.startsWith("http://") || url.startsWith("https://")) return false
                // intent:// and other app schemes — WebView drops these silently,
                // so hand them to Android (lets HA cards launch Portal apps).
                runCatching {
                    val intent =
                        if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        else Intent(Intent.ACTION_VIEW, request.url)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }.onFailure {
                    android.util.Log.w("PortalHA", "Could not launch $url: ${it.message}")
                }
                return true
            }
        }

        findViewById<Button>(R.id.btn_open_settings).setOnClickListener {
            drawer.closeDrawers()
            startActivity(Intent(this, MainActivity::class.java))
        }

        findViewById<Button>(R.id.btn_reload).setOnClickListener {
            drawer.closeDrawers()
            loadDashboard()
        }

        loadDashboard()

        playerView = findViewById(R.id.player_view)

        overlayWebView = findViewById(R.id.overlay_web_view)
        btnCloseOverlay = findViewById(R.id.btn_close_overlay)
        btnReloadOverlay = findViewById(R.id.btn_reload_overlay)

        overlayWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(false)
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        overlayWebView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }

        overlayWebView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.proceed()
            }
        }

        btnCloseOverlay.setOnClickListener {
            hideUrlOverlay()
        }

        btnReloadOverlay.setOnClickListener {
            overlayWebView.reload()
        }

        alertOverlay = findViewById(R.id.layout_alert_overlay)
        tvAlertIcon = findViewById(R.id.tv_alert_icon)
        tvAlertTitle = findViewById(R.id.tv_alert_title)
        tvAlertMessage = findViewById(R.id.tv_alert_message)
        pbAlertTimer = findViewById(R.id.pb_alert_timer)
        tvAlertTimer = findViewById(R.id.tv_alert_timer)
        layoutAlertButtons = findViewById(R.id.layout_alert_buttons)
        btnAlertAction1 = findViewById(R.id.btn_alert_action1)
        btnAlertAction2 = findViewById(R.id.btn_alert_action2)

        alertOverlay.setOnClickListener {
            if (layoutAlertButtons.visibility != android.view.View.VISIBLE) {
                hideAlertOverlay()
            }
        }

        handleIntent(intent)

        // First run (nothing configured yet): drop straight into Settings rather
        // than showing the empty dashboard placeholder. Only on a genuine fresh
        // create — savedInstanceState guards against config-change recreation,
        // and onCreate (not onResume) means backing out of Settings won't loop.
        if (savedInstanceState == null && prefs.haUrl.isBlank()) {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    // Hide the status/navigation bars for a full-screen kiosk view. STICKY so a
    // swipe only reveals them briefly, then they auto-hide. (Deprecated flags, but
    // these are the working API on the Portal's Android 9/10.)
    @Suppress("DEPRECATION")
    private fun enableImmersive() {
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    // Immersive-sticky drops after focus changes (dialogs, the drawer, app
    // switches) — re-assert it whenever we regain focus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersive()
            when {
                alertOverlay.visibility == android.view.View.VISIBLE -> alertOverlay.requestFocus()
                overlayWebView.visibility == android.view.View.VISIBLE -> overlayWebView.requestFocus()
                else -> webView.requestFocus()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        @Suppress("DEPRECATION")
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)

        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        enableImmersive()

        dismissRetries = 0
        dismissKeyguard()
        when {
            alertOverlay.visibility == android.view.View.VISIBLE -> alertOverlay.requestFocus()
            overlayWebView.visibility == android.view.View.VISIBLE -> overlayWebView.requestFocus()
            else -> webView.requestFocus()
        }

        // Re-acquire the camera if another app (e.g. the Portal launcher) took
        // it while we were in the background.
        BridgeService.ensureCamera(this)
        // Reload if URL changed in settings
        val url = prefs.haUrl
        val current = webView.url ?: ""
        if (url.isNotEmpty() && !current.startsWith(normalise(url).trimEnd('/'))) {
            loadDashboard()
        }

        // Resume video playback if an RTSP URL was active
        val rtspUrl = prefs.displayRtspUrl
        if (rtspUrl.isNotEmpty() && rtspUrl.uppercase() != "OFF") {
            playRtspStream(rtspUrl)
        }

        // Resume URL overlay if active
        val displayUrl = prefs.displayUrl
        if (displayUrl.isNotEmpty() && displayUrl.uppercase() != "OFF") {
            showUrlOverlay(displayUrl)
        }

        // Resume Alert overlay if active
        val displayAlert = prefs.displayAlertPayload
        if (displayAlert.isNotEmpty() && displayAlert.uppercase() != "OFF") {
            showAlertOverlay(displayAlert)
        }
    }

    private fun dismissKeyguard() {
        val km = getSystemService(android.app.KeyguardManager::class.java) ?: return
        if (!km.isKeyguardLocked) {
            android.util.Log.d("PortalHA", "Keyguard is not locked, no need to dismiss")
            dismissRetries = 0
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            km.requestDismissKeyguard(this, object : android.app.KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissError() {
                    android.util.Log.w("PortalHA", "Keyguard dismiss error (retry $dismissRetries/5)")
                    retryDismiss()
                }
                override fun onDismissSucceeded() {
                    android.util.Log.i("PortalHA", "Keyguard dismiss succeeded")
                    dismissRetries = 0
                }
                override fun onDismissCancelled() {
                    android.util.Log.w("PortalHA", "Keyguard dismiss cancelled (retry $dismissRetries/5)")
                    retryDismiss()
                }
            })
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }
    }

    private fun retryDismiss() {
        if (dismissRetries < 5) {
            dismissRetries++
            webView.postDelayed({ dismissKeyguard() }, 500L)
        } else {
            android.util.Log.e("PortalHA", "Keyguard dismiss failed after max retries")
            dismissRetries = 0
        }
    }

    private fun loadDashboard() {
        val url = prefs.haUrl.trim()
        if (url.isEmpty()) {
            showPlaceholder("Swipe from the left edge to open Settings\nand enter your Home Assistant URL.")
        } else {
            webView.loadUrl(normalise(url))
        }
    }

    private fun normalise(url: String) = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        else -> "http://$url"
    }

    private fun showPlaceholder(message: String) {
        // Must use loadDataWithBaseURL, not loadData: loadData treats the payload
        // like a URL and chokes on the '#' in hex colors, rendering a blank page.
        webView.loadDataWithBaseURL(
            null,
            """<html><body style="background:#1c1c1c;color:#ccc;font-family:sans-serif;
               display:flex;align-items:center;justify-content:center;
               height:100vh;margin:0;text-align:center;padding:40px;box-sizing:border-box;">
               <div><h2 style="color:#fff">Portal HA Bridge</h2><p>$message</p></div>
               </body></html>""",
            "text/html", "UTF-8", null
        )
    }

    override fun onBackPressed() {
        when {
            drawer.isDrawerOpen(GravityCompat.START) -> drawer.closeDrawer(GravityCompat.START)
            alertOverlay.visibility == android.view.View.VISIBLE -> hideAlertOverlay()
            overlayWebView.visibility == android.view.View.VISIBLE && overlayWebView.canGoBack() -> overlayWebView.goBack()
            overlayWebView.visibility == android.view.View.VISIBLE -> hideUrlOverlay()
            webView.canGoBack() -> webView.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onStop() {
        super.onStop()
        // Release player and network socket when activity goes out of view to save resources
        stopRtspStream()
    }

    override fun onDestroy() {
        stopRtspStream()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        val rtspUrl = intent?.getStringExtra("play_rtsp_url")
        if (rtspUrl != null) {
            if (rtspUrl.isNotEmpty() && rtspUrl.uppercase() != "OFF") {
                playRtspStream(rtspUrl)
            } else {
                stopRtspStream()
            }
        }
        val displayUrl = intent?.getStringExtra("display_url")
        if (displayUrl != null) {
            if (displayUrl.isNotEmpty() && displayUrl.uppercase() != "OFF") {
                showUrlOverlay(displayUrl)
            } else {
                hideUrlOverlay()
            }
        }
        val displayAlert = intent?.getStringExtra("display_alert")
        if (displayAlert != null) {
            if (displayAlert.isNotEmpty() && displayAlert.uppercase() != "OFF") {
                showAlertOverlay(displayAlert)
            } else {
                hideAlertOverlay()
            }
        }
    }

    private fun showAlertOverlay(payload: String) {
        alertTimer?.cancel()
        alertTimer = null

        try {
            val json = JSONObject(payload)
            activeAlertId = json.optString("id", "alert")

            tvAlertTitle.text = json.optString("title", "Alert")
            tvAlertMessage.text = json.optString("message", "")

            val icon = json.optString("icon", "🔔")
            tvAlertIcon.text = icon

            val bgColorStr = json.optString("bg_color", "#121212")
            runCatching {
                alertOverlay.setBackgroundColor(android.graphics.Color.parseColor(bgColorStr))
            }.onFailure {
                alertOverlay.setBackgroundColor(android.graphics.Color.parseColor("#121212"))
            }

            val accentColorStr = json.optString("accent_color", "#4CAF50")
            val accentColor = try {
                android.graphics.Color.parseColor(accentColorStr)
            } catch (e: Exception) {
                android.graphics.Color.parseColor("#4CAF50")
            }

            val buttonsArray = json.optJSONArray("buttons")
            if (buttonsArray != null && buttonsArray.length() > 0) {
                layoutAlertButtons.visibility = android.view.View.VISIBLE
                
                val btn1 = buttonsArray.optJSONObject(0)
                if (btn1 != null) {
                    btnAlertAction1.visibility = android.view.View.VISIBLE
                    btnAlertAction1.text = btn1.optString("label", "Action 1")
                    val btn1Action = btn1.optString("action", "minimize")
                    btnAlertAction1.setOnClickListener {
                        if (btn1Action == "minimize") {
                            hideAlertOverlay()
                        } else {
                            BridgeService.publishAlertAction(this, activeAlertId ?: "alert", btn1Action)
                            hideAlertOverlay()
                        }
                    }
                    btnAlertAction1.backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor)
                } else {
                    btnAlertAction1.visibility = android.view.View.GONE
                }

                val btn2 = buttonsArray.optJSONObject(1)
                if (btn2 != null) {
                    btnAlertAction2.visibility = android.view.View.VISIBLE
                    btnAlertAction2.text = btn2.optString("label", "Action 2")
                    val btn2Action = btn2.optString("action", "cancel")
                    btnAlertAction2.setOnClickListener {
                        if (btn2Action == "minimize") {
                            hideAlertOverlay()
                        } else {
                            BridgeService.publishAlertAction(this, activeAlertId ?: "alert", btn2Action)
                            hideAlertOverlay()
                        }
                    }
                    btnAlertAction2.backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor)
                } else {
                    btnAlertAction2.visibility = android.view.View.GONE
                }
            } else {
                layoutAlertButtons.visibility = android.view.View.GONE
            }

            val type = json.optString("type", "alert")
            val durationSeconds = json.optInt("duration_seconds", 0)

            if (durationSeconds > 0) {
                val totalMs = durationSeconds * 1000L
                if (type == "timer") {
                    pbAlertTimer.visibility = android.view.View.VISIBLE
                    tvAlertTimer.visibility = android.view.View.VISIBLE
                    pbAlertTimer.progressTintList = android.content.res.ColorStateList.valueOf(accentColor)

                    alertTimer = object : CountDownTimer(totalMs, 1000L) {
                        override fun onTick(millisUntilFinished: Long) {
                            val secs = millisUntilFinished / 1000
                            val minPart = secs / 60
                            val secPart = secs % 60
                            tvAlertTimer.text = "%02d:%02d".format(minPart, secPart)
                            
                            val progress = ((millisUntilFinished.toFloat() / totalMs.toFloat()) * 100).toInt()
                            pbAlertTimer.progress = progress
                        }

                        override fun onFinish() {
                            hideAlertOverlay()
                        }
                    }.start()
                } else {
                    pbAlertTimer.visibility = android.view.View.GONE
                    tvAlertTimer.visibility = android.view.View.GONE
                    
                    alertTimer = object : CountDownTimer(totalMs, totalMs) {
                        override fun onTick(millisUntilFinished: Long) {}
                        override fun onFinish() {
                            hideAlertOverlay()
                        }
                    }.start()
                }
            } else {
                pbAlertTimer.visibility = android.view.View.GONE
                tvAlertTimer.visibility = android.view.View.GONE
            }

            alertOverlay.visibility = android.view.View.VISIBLE
            alertOverlay.requestFocus()
        } catch (e: Exception) {
            android.util.Log.e("PortalHA", "Failed to parse alert payload: ${e.message}")
            hideAlertOverlay()
        }
    }

    private fun hideAlertOverlay() {
        alertTimer?.cancel()
        alertTimer = null
        activeAlertId = null

        alertOverlay.visibility = android.view.View.GONE
        pbAlertTimer.visibility = android.view.View.GONE
        tvAlertTimer.visibility = android.view.View.GONE
        
        when {
            overlayWebView.visibility == android.view.View.VISIBLE -> overlayWebView.requestFocus()
            else -> webView.requestFocus()
        }

        BridgeService.setDisplayAlert(this, "OFF")
    }

    private fun showUrlOverlay(url: String) {
        overlayWebView.visibility = android.view.View.VISIBLE
        btnCloseOverlay.visibility = android.view.View.VISIBLE
        btnReloadOverlay.visibility = android.view.View.VISIBLE
        overlayWebView.requestFocus()

        val current = overlayWebView.url ?: ""
        if (!current.startsWith(url.trimEnd('/'))) {
            overlayWebView.loadUrl(url)
        }

        // Report the state update back to HA via BridgeService
        BridgeService.setDisplayUrl(this, url)
    }

    private fun hideUrlOverlay() {
        overlayWebView.visibility = android.view.View.GONE
        btnCloseOverlay.visibility = android.view.View.GONE
        btnReloadOverlay.visibility = android.view.View.GONE
        overlayWebView.loadUrl("about:blank")
        webView.requestFocus()

        // Report the state update back to HA via BridgeService
        BridgeService.setDisplayUrl(this, "OFF")
    }

    private fun playRtspStream(url: String) {
        stopRtspStream()
        playerView.visibility = android.view.View.VISIBLE

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 500,
                /* maxBufferMs = */ 1000,
                /* bufferForPlaybackMs = */ 200,
                /* bufferForPlaybackAfterRebufferMs = */ 200
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val newPlayer = androidx.media3.exoplayer.ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build()

        // Disable audio track decoding entirely to save network & CPU bandwidth
        val trackSelectionParameters = newPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true)
            .build()
        newPlayer.trackSelectionParameters = trackSelectionParameters
        newPlayer.volume = 0f

        val mediaItem = androidx.media3.common.MediaItem.fromUri(url)
        // Force TCP transport to improve network stability and avoid UDP dropouts
        val mediaSource = androidx.media3.exoplayer.rtsp.RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setDebugLoggingEnabled(true)
            .createMediaSource(mediaItem)

        newPlayer.setMediaSource(mediaSource)
        newPlayer.prepare()
        newPlayer.playWhenReady = true

        newPlayer.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("PortalHA", "ExoPlayer playback error: ${error.message}")
                stopRtspStream()
            }
        })

        player = newPlayer
        playerView.player = newPlayer
    }

    private fun stopRtspStream() {
        player?.let {
            it.stop()
            it.release()
        }
        player = null
        playerView.player = null
        playerView.visibility = android.view.View.GONE
    }
}

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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.json.JSONArray
import android.os.CountDownTimer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

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

    // Mealie Sidebar & Timers views
    private lateinit var rightDrawer: android.view.View
    private lateinit var layoutRecipeList: android.view.View
    private lateinit var rvRecipes: RecyclerView
    private lateinit var layoutRecipeDetail: android.view.View
    private lateinit var btnRecipeBack: ImageButton
    private lateinit var btnRefreshRecipes: ImageButton
    private lateinit var tvDetailTitle: TextView
    private lateinit var ivDetailImage: ImageView
    private lateinit var tvDetailDescription: TextView
    private lateinit var layoutDetailIngredients: android.view.ViewGroup
    private lateinit var layoutDetailInstructions: android.view.ViewGroup

    // Minimized Timer views
    private lateinit var layoutMinimizedTimer: android.view.View
    private lateinit var tvMinimizedTimerText: TextView

    // Native Timer state
    private var nativeCountDownTimer: CountDownTimer? = null
    private var nativeTimerTotalMs: Long = 0L
    private var nativeTimerRemainingMs: Long = 0L
    private var nativeTimerTitle: String = ""
    private var nativeTimerMessage: String = ""
    private var nativeTimerIsMinimized: Boolean = false

    private lateinit var recipeAdapter: RecipeAdapter
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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

        // Initialize Mealie Sidebar & Minimized Timer views
        rightDrawer = findViewById(R.id.right_drawer)
        layoutRecipeList = findViewById(R.id.layout_recipe_list)
        rvRecipes = findViewById(R.id.rv_recipes)
        layoutRecipeDetail = findViewById(R.id.layout_recipe_detail)
        btnRecipeBack = findViewById(R.id.btn_recipe_back)
        btnRefreshRecipes = findViewById(R.id.btn_refresh_recipes)
        tvDetailTitle = findViewById(R.id.tv_detail_title)
        ivDetailImage = findViewById(R.id.iv_detail_image)
        tvDetailDescription = findViewById(R.id.tv_detail_description)
        layoutDetailIngredients = findViewById(R.id.layout_detail_ingredients)
        layoutDetailInstructions = findViewById(R.id.layout_detail_instructions)

        layoutMinimizedTimer = findViewById(R.id.layout_minimized_timer)
        tvMinimizedTimerText = findViewById(R.id.tv_minimized_timer_text)

        // Restore maximized timer if the minimized badge is clicked
        layoutMinimizedTimer.setOnClickListener {
            nativeTimerIsMinimized = false
            updateTimerUI()
        }

        btnRecipeBack.setOnClickListener {
            showRecipeList()
        }

        btnRefreshRecipes.setOnClickListener {
            loadRecipes()
        }

        rvRecipes.layoutManager = LinearLayoutManager(this)
        recipeAdapter = RecipeAdapter(prefs.mealieUrl) { recipe ->
            loadRecipeDetails(recipe)
        }
        rvRecipes.adapter = recipeAdapter

        drawer.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: android.view.View, slideOffset: Float) {}
            override fun onDrawerOpened(drawerView: android.view.View) {
                if (drawerView == rightDrawer) {
                    loadRecipes()
                }
            }
            override fun onDrawerClosed(drawerView: android.view.View) {}
            override fun onDrawerStateChanged(newState: Int) {}
        })

        loadRecipes()

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
                    if (target.equals("recipes", ignoreCase = true)) {
                        openRecipesSidebar()
                        return true
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

        // Reload recipes on resume
        loadRecipes()
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
            drawer.isDrawerOpen(GravityCompat.END) -> {
                if (layoutRecipeDetail.visibility == android.view.View.VISIBLE) {
                    showRecipeList()
                } else {
                    drawer.closeDrawer(GravityCompat.END)
                }
            }
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
        activityScope.cancel()
        nativeCountDownTimer?.cancel()
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

            val type = json.optString("type", "alert")
            val durationSeconds = json.optInt("duration_seconds", 0)

            if (type == "timer" && durationSeconds > 0) {
                val title = json.optString("title", "Timer")
                val message = json.optString("message", "")
                startNativeTimer(title, message, durationSeconds, activeAlertId ?: "alert")
                return
            }

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

            if (durationSeconds > 0) {
                val totalMs = durationSeconds * 1000L
                pbAlertTimer.visibility = android.view.View.GONE
                tvAlertTimer.visibility = android.view.View.GONE
                
                alertTimer = object : CountDownTimer(totalMs, totalMs) {
                    override fun onTick(millisUntilFinished: Long) {}
                    override fun onFinish() {
                        hideAlertOverlay()
                    }
                }.start()
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

        nativeCountDownTimer?.cancel()
        nativeCountDownTimer = null
        nativeTimerRemainingMs = 0
        layoutMinimizedTimer.visibility = android.view.View.GONE

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

    private fun openRecipesSidebar() {
        drawer.openDrawer(GravityCompat.END)
        loadRecipes()
    }

    private fun showRecipeList() {
        layoutRecipeDetail.visibility = android.view.View.GONE
        layoutRecipeList.visibility = android.view.View.VISIBLE
    }

    private fun loadRecipes() {
        val url = prefs.mealieUrl.trim()
        val token = prefs.mealieToken.trim()
        if (url.isEmpty() || token.isEmpty()) {
            recipeAdapter.setRecipes(emptyList())
            return
        }
        activityScope.launch {
            val endpoint = "${url.trimEnd('/')}/api/recipes?per_page=150"
            val response = getJsonFromUrl(endpoint, token)
            if (response != null) {
                try {
                    val json = JSONObject(response)
                    val itemsArray = json.optJSONArray("items")
                    val list = mutableListOf<JSONObject>()
                    if (itemsArray != null) {
                        for (i in 0 until itemsArray.length()) {
                            val item = itemsArray.optJSONObject(i)
                            if (item != null) list.add(item)
                        }
                    }
                    recipeAdapter.setRecipes(list)
                } catch (e: Exception) {
                    android.util.Log.e("PortalHA", "Failed to parse recipes: ${e.message}")
                }
            }
        }
    }

    private fun loadRecipeDetails(recipe: JSONObject) {
        val slug = recipe.optString("slug", "")
        val url = prefs.mealieUrl.trim()
        val token = prefs.mealieToken.trim()
        if (slug.isEmpty() || url.isEmpty() || token.isEmpty()) return

        // Show detail layout, hide list layout
        layoutRecipeList.visibility = android.view.View.GONE
        layoutRecipeDetail.visibility = android.view.View.VISIBLE

        // Set title and placeholder image
        tvDetailTitle.text = recipe.optString("name", "")
        ivDetailImage.setImageResource(android.R.drawable.ic_menu_gallery)
        tvDetailDescription.text = recipe.optString("description", "")
        layoutDetailIngredients.removeAllViews()
        layoutDetailInstructions.removeAllViews()

        // Load image banner
        val id = recipe.optString("id", "")
        if (id.isNotEmpty()) {
            val imageUrl = "${url.trimEnd('/')}/api/media/recipes/$id/images/original.webp"
            Glide.with(this)
                .load(imageUrl)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .error(android.R.drawable.ic_menu_gallery)
                .into(ivDetailImage)
        }

        // Fetch details
        activityScope.launch {
            val endpoint = "${url.trimEnd('/')}/api/recipes/$slug"
            val response = getJsonFromUrl(endpoint, token)
            if (response != null) {
                try {
                    val detailedJson = JSONObject(response)
                    renderRecipeDetails(detailedJson)
                } catch (e: Exception) {
                    android.util.Log.e("PortalHA", "Failed to parse recipe details: ${e.message}")
                }
            }
        }
    }

    private fun renderRecipeDetails(json: JSONObject) {
        tvDetailDescription.text = json.optString("description", "")

        val ingredientsArray = json.optJSONArray("recipeIngredient")
        layoutDetailIngredients.removeAllViews()
        if (ingredientsArray != null) {
            for (i in 0 until ingredientsArray.length()) {
                val ing = ingredientsArray.optJSONObject(i) ?: continue
                val text = ing.optString("originalText", "").ifEmpty { ing.optString("display", "") }
                if (text.isNotEmpty()) {
                    val tv = TextView(this).apply {
                        this.text = "• $text"
                        this.setTextColor(android.graphics.Color.WHITE)
                        this.textSize = 15f
                        this.setPadding(0, 4, 0, 4)
                    }
                    layoutDetailIngredients.addView(tv)
                }
            }
        }

        val instructionsArray = json.optJSONArray("recipeInstructions")
        layoutDetailInstructions.removeAllViews()
        if (instructionsArray != null) {
            val recipeName = json.optString("name", "Recipe")
            for (i in 0 until instructionsArray.length()) {
                val step = instructionsArray.optJSONObject(i) ?: continue
                val text = step.optString("text", "")
                if (text.isNotEmpty()) {
                    val stepContainer = LinearLayout(this).apply {
                        this.orientation = LinearLayout.HORIZONTAL
                        this.setPadding(0, 8, 0, 8)
                        this.gravity = android.view.Gravity.CENTER_VERTICAL
                    }

                    val tvStep = TextView(this).apply {
                        this.text = "${i + 1}. $text"
                        this.setTextColor(android.graphics.Color.WHITE)
                        this.textSize = 15f
                        this.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    stepContainer.addView(tvStep)

                    val timers = parseTimersFromText(text)
                    if (timers.isNotEmpty()) {
                        val timerContainer = LinearLayout(this).apply {
                            this.orientation = LinearLayout.VERTICAL
                            this.setPadding(12, 0, 0, 0)
                            this.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        }

                        for (seconds in timers) {
                            val mins = seconds / 60
                            val label = if (mins >= 60) {
                                val hrs = mins / 60
                                val rem = mins % 60
                                if (rem > 0) "${hrs}h ${rem}m" else "${hrs}h"
                            } else {
                                "${mins}m"
                            }

                            val btnTimer = Button(this).apply {
                                this.text = "⏳ $label"
                                this.textSize = 12f
                                this.setTextColor(android.graphics.Color.WHITE)
                                this.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#444466"))
                                this.setOnClickListener {
                                    startNativeTimer(recipeName, "Step ${i + 1}: $text", seconds)
                                }
                            }
                            timerContainer.addView(btnTimer)
                        }
                        stepContainer.addView(timerContainer)
                    }

                    layoutDetailInstructions.addView(stepContainer)
                }
            }
        }
    }

    private fun parseTimersFromText(text: String): List<Int> {
        val secondsList = mutableListOf<Int>()
        val regex = Regex("""(?i)\b(?:(\d+(?:\.\d+)?)\s*-\s*)?(\d+(?:\.\d+)?)\s*(second|sec|minute|min|hour|hr)s?\b""")
        val matches = regex.findAll(text)
        for (match in matches) {
            val numStr = match.groupValues[2]
            val unitStr = match.groupValues[3].lowercase()
            val number = numStr.toDoubleOrNull() ?: continue
            val multiplier = when {
                unitStr.startsWith("sec") -> 1
                unitStr.startsWith("min") -> 60
                unitStr.startsWith("hour") || unitStr.startsWith("hr") -> 3600
                else -> 60
            }
            val lowerStr = match.groupValues[1]
            if (lowerStr.isNotEmpty()) {
                val lowerNum = lowerStr.toDoubleOrNull()
                if (lowerNum != null) {
                    secondsList.add((lowerNum * multiplier).toInt())
                }
            }
            secondsList.add((number * multiplier).toInt())
        }
        
        if (secondsList.isEmpty()) {
            val clean = text.lowercase()
            if (clean.contains("an hour") || clean.contains("a hour")) {
                secondsList.add(3600)
            } else if (clean.contains("a minute") || clean.contains("one minute")) {
                secondsList.add(60)
            }
        }
        return secondsList
    }

    private fun startNativeTimer(title: String, message: String, durationSeconds: Int, alertId: String = "alert") {
        nativeCountDownTimer?.cancel()
        nativeCountDownTimer = null

        activeAlertId = alertId
        nativeTimerTitle = title
        nativeTimerMessage = message
        nativeTimerTotalMs = durationSeconds * 1000L
        nativeTimerRemainingMs = nativeTimerTotalMs
        nativeTimerIsMinimized = false

        tvAlertTitle.text = title
        tvAlertMessage.text = message
        tvAlertIcon.text = "⏳"
        alertOverlay.setBackgroundColor(android.graphics.Color.parseColor("#121212"))
        
        layoutAlertButtons.visibility = android.view.View.VISIBLE
        btnAlertAction1.visibility = android.view.View.VISIBLE
        btnAlertAction1.text = "Minimize"
        btnAlertAction1.setOnClickListener {
            minimizeNativeTimer()
        }
        
        btnAlertAction2.visibility = android.view.View.VISIBLE
        btnAlertAction2.text = "Cancel Timer"
        btnAlertAction2.setOnClickListener {
            cancelNativeTimer()
        }

        pbAlertTimer.visibility = android.view.View.VISIBLE
        tvAlertTimer.visibility = android.view.View.VISIBLE
        pbAlertTimer.progressTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF9800"))

        nativeCountDownTimer = object : CountDownTimer(nativeTimerTotalMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                nativeTimerRemainingMs = millisUntilFinished
                updateTimerUI()
            }

            override fun onFinish() {
                nativeTimerRemainingMs = 0
                updateTimerUI()
                onNativeTimerFinished()
            }
        }.start()

        alertOverlay.visibility = android.view.View.VISIBLE
        layoutMinimizedTimer.visibility = android.view.View.GONE
        alertOverlay.requestFocus()
    }

    private fun minimizeNativeTimer() {
        nativeTimerIsMinimized = true
        updateTimerUI()
        webView.requestFocus()
    }

    private fun cancelNativeTimer() {
        nativeCountDownTimer?.cancel()
        nativeCountDownTimer = null
        nativeTimerRemainingMs = 0
        layoutMinimizedTimer.visibility = android.view.View.GONE
        alertOverlay.visibility = android.view.View.GONE
        webView.requestFocus()
        BridgeService.setDisplayAlert(this, "OFF")
    }

    private fun updateTimerUI() {
        val secs = nativeTimerRemainingMs / 1000
        val minPart = secs / 60
        val secPart = secs % 60
        val timeStr = "%02d:%02d".format(minPart, secPart)

        if (nativeTimerIsMinimized) {
            tvMinimizedTimerText.text = timeStr
            layoutMinimizedTimer.visibility = android.view.View.VISIBLE
            alertOverlay.visibility = android.view.View.GONE
        } else {
            tvAlertTimer.text = timeStr
            val progress = if (nativeTimerTotalMs > 0) {
                ((nativeTimerRemainingMs.toFloat() / nativeTimerTotalMs.toFloat()) * 100).toInt()
            } else 0
            pbAlertTimer.progress = progress
            alertOverlay.visibility = android.view.View.VISIBLE
            layoutMinimizedTimer.visibility = android.view.View.GONE
        }
    }

    private fun onNativeTimerFinished() {
        nativeTimerIsMinimized = false
        updateTimerUI()
        
        tvAlertTitle.text = "Timer Finished!"
        tvAlertMessage.text = "$nativeTimerTitle\n$nativeTimerMessage"
        tvAlertTimer.text = "00:00"
        pbAlertTimer.progress = 0
        
        btnAlertAction1.visibility = android.view.View.GONE
        btnAlertAction2.text = "Dismiss"
        btnAlertAction2.setOnClickListener {
            cancelNativeTimer()
        }
        
        alertOverlay.visibility = android.view.View.VISIBLE
        alertOverlay.requestFocus()
        
        TonePlayer.play("alert")
    }

    private suspend fun getJsonFromUrl(urlString: String, token: String): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            if (token.isNotEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                response.toString()
            } else {
                android.util.Log.e("PortalHA", "HTTP Error $responseCode fetching $urlString")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("PortalHA", "Error fetching $urlString: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
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

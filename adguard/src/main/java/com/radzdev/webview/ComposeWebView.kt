@file:Suppress("DEPRECATION")

package com.radzdev.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.RelativeLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.lang.ref.WeakReference
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import android.media.MediaPlayer
import android.util.AttributeSet
import android.view.SurfaceView
import android.view.MotionEvent
import android.widget.VideoView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.radzdev.adguard.AdGuardEngine
import com.radzdev.adguard.ContentType

@SuppressLint("ContextCastToActivity")
@Composable
fun ComposeWebView(
    url: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current as ComponentActivity
    var isLoading by remember { mutableStateOf(true) }
    var isFullscreen by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<VideoEnabledWebView?>(null) }
    var webChromeClient by remember { mutableStateOf<VideoEnabledWebChromeClient?>(null) }
    var mainDomain by remember { mutableStateOf("") }
    val godUrl = remember { url } // Store the EXACT URL from MainActivity - this is GOD!
    var isRefreshing by remember { mutableStateOf(false) }
    var wakeLock by remember { mutableStateOf<PowerManager.WakeLock?>(null) }

    // 📊 PROGRESS TRACKING: Filter download progress
    var downloadProgress by remember { mutableStateOf(0) }
    var downloadMessage by remember { mutableStateOf("Initializing...") }
    var estimatedTimeLeft by remember { mutableStateOf("") }
    var isDownloadingFilters by remember { mutableStateOf(true) }

    val onProgressUpdate: (Int, String, String) -> Unit = { progress, message, timeLeft ->
        downloadProgress = progress
        downloadMessage = message
        estimatedTimeLeft = timeLeft

        if (progress >= 100) {
            isDownloadingFilters = false
            // Small delay to show completion message
            CoroutineScope(Dispatchers.Main).launch {
                delay(1500)
                isLoading = false
            }
        }
    }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    // Memory management
    val memoryManager = remember { WebViewMemoryManager() }

    // Crash prevention - setup global exception handler
    LaunchedEffect(Unit) {
        setupCrashPrevention(context)
    }

    // AdGuard engine - let AdGuard handle all blocking logic
    var adGuardEngine by remember { mutableStateOf<AdGuardEngine?>(null) }
    val scope = rememberCoroutineScope()

    // Initialize AdGuard engine, main domain, and WakeLock
    LaunchedEffect(url) {
        mainDomain = extractDomain(url)

        // Initialize AdGuard engine only once and reuse - MOVED TO BACKGROUND THREAD
        try {
            if (adGuardEngine == null) {
                // Move initialization to background thread to prevent UI freezing
                withContext(Dispatchers.IO) {
                    adGuardEngine?.destroy() // Clean up any existing instance
                    val engine = AdGuardEngine(context)
                    // 📊 PROGRESS TRACKING: Show download progress
                    engine.setProgressCallback { progress, message, timeLeft ->
                        CoroutineScope(Dispatchers.Main).launch {
                            onProgressUpdate(progress, message, timeLeft)
                        }
                    }

                    engine.initialize()
                    // 👑 Set the GOD URL that should never be blocked
                    engine.setGodUrl(godUrl)

                    // Switch back to main thread to update UI state
                    withContext(Dispatchers.Main) {
                        adGuardEngine = engine
                        memoryManager.registerAdGuardEngine(engine) // Register with memory manager
                    }

                    // 🔍 DEBUG: Check filter loading status
                    val stats = engine.getStats()
                    Log.i("AdGuard", "AdGuard engine initialized successfully in ComposeWebView")
                    Log.i("AdGuard", "📊 Filter stats: $stats")

                    // Test blocking on known ad domains
                    val testUrls = listOf(
                        "https://faqirsgoliard.top/test",
                        "https://aroundcommoditysway.com/test",
                        "https://topworkredbay.shop/test"
                    )
                    testUrls.forEach { testUrl ->
                        val shouldBlock = engine.shouldBlock(testUrl, "https://vide0.net")
                        Log.w("AdGuard", "🧪 Test blocking $testUrl: shouldBlock=$shouldBlock")
                    }
                }
            } else {
                // If engine already exists, make sure GOD URL is set
                adGuardEngine?.setGodUrl(godUrl)
            }
        } catch (e: Exception) {
            Log.e("AdGuard", "Failed to initialize AdGuard engine in ComposeWebView", e)
        }

        // Initialize WakeLock with shorter duration and partial wake lock
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, // Use partial wake lock instead of full
            "ComposeWebView:WakeLock"
        )
        wakeLock?.acquire(5*60*1000L /*5 minutes instead of 10*/)
    }

    // Handle pull to refresh
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            webView?.reload()
            delay(1000) // Small delay for better UX
            isRefreshing = false
        }
    }

    // Handle back button for fullscreen
    BackHandler(enabled = isFullscreen) {
        webChromeClient?.let { chromeClient ->
            if (chromeClient.isVideoFullscreen) {
                chromeClient.onBackPressed()
                isFullscreen = false
            }
        }
    }

    // Cleanup when composable is disposed
    DisposableEffect(Unit) {
        onDispose {
            // Comprehensive cleanup
            try {
                webView?.let { wv ->
                    memoryManager.cleanupWebView(wv)
                }
                adGuardEngine?.destroy()
                adGuardEngine = null
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
                wakeLock = null
                memoryManager.cleanup()
                Log.i("WebView", "ComposeWebView cleanup completed")
            } catch (e: Exception) {
                Log.e("WebView", "Error during cleanup", e)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Error boundary
        if (hasError) {
            ErrorScreen(
                errorMessage = errorMessage,
                onRetry = {
                    hasError = false
                    errorMessage = ""
                    webView?.reload()
                }
            )
            return@Box
        }

        // Show loading screen until AdGuard is initialized to prevent timing issues
        if (adGuardEngine == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = Color.Blue
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "🛡️ Initializing AdGuard Protection...",
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
            }
            return@Box
        }
        // Main layout structure matching radzadblocker
        AndroidView(
            factory = { ctx ->
                // Create the root layout
                RelativeLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    // Create VideoEnabledWebView first
                    val webViewInstance = VideoEnabledWebView(ctx).apply {
                        webView = this
                        layoutParams = RelativeLayout.LayoutParams(
                            RelativeLayout.LayoutParams.MATCH_PARENT,
                            RelativeLayout.LayoutParams.MATCH_PARENT
                        )

                        // Setup WebView with memory management
                        setupWebView(this, mainDomain, godUrl, adGuardEngine, { loading ->
                            isLoading = loading
                        }, context, memoryManager)

                        // Register with memory manager
                        memoryManager.registerWebView(this)

                        // Load URL only after AdGuard is ready
                        Log.i("WebView", "🚀 Loading main URL after AdGuard initialization: $url")
                        Log.i("WebView", "👑 GOD URL set to: $godUrl")
                        Log.i("WebView", "🛡️ AdGuard engine ready: ${adGuardEngine != null}")
                        Log.i("WebView", "📍 Current main URL from MainActivity: https://vide0.net/e/xlerkprs9quw")
                        loadUrl(url)
                    }

                    // Create CustomSwipeRefreshLayout
                    val swipeRefreshLayout = CustomSwipeRefreshLayout(ctx).apply {
                        id = View.generateViewId()
                        layoutParams = RelativeLayout.LayoutParams(
                            RelativeLayout.LayoutParams.MATCH_PARENT,
                            RelativeLayout.LayoutParams.MATCH_PARENT
                        )

                        // Create nonVideoLayout
                        val nonVideoLayout = RelativeLayout(ctx).apply {
                            id = View.generateViewId()
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            addView(webViewInstance)
                        }

                        addView(nonVideoLayout)
                        setWebView(webViewInstance)
                        setOnRefreshListener {
                            isRefreshing = true
                            webViewInstance.reload()
                        }
                    }

                    // Create videoLayout for fullscreen
                    val videoLayout = RelativeLayout(ctx).apply {
                        id = View.generateViewId()
                        layoutParams = RelativeLayout.LayoutParams(
                            RelativeLayout.LayoutParams.MATCH_PARENT,
                            RelativeLayout.LayoutParams.MATCH_PARENT
                        )
                        visibility = View.GONE
                    }

                    // Setup VideoEnabledWebChromeClient exactly like radzadblocker
                    webView?.let { wv ->
                        val loadingView = FrameLayout(ctx) // Simple loading view instead of Lottie
                        webChromeClient = VideoEnabledWebChromeClient(
                            swipeRefreshLayout.getChildAt(0), // nonVideoLayout
                            videoLayout,
                            loadingView,
                            wv
                        ).apply {
                            setOnToggledFullscreen(object : VideoEnabledWebChromeClient.ToggledFullscreenCallback {
                                override fun toggledFullscreen(fullscreen: Boolean) {
                                    isFullscreen = fullscreen

                                    if (fullscreen) {
                                        // Keep screen on during fullscreen
                                        if (wakeLock?.isHeld != true) {
                                            wakeLock?.acquire(10*60*1000L /*10 minutes*/)
                                        }
                                        // Set landscape orientation
                                        context.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                    } else {
                                        // Return to portrait
                                        context.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                    }
                                }
                            })
                        }
                        wv.webChromeClient = webChromeClient
                    }

                    addView(swipeRefreshLayout)
                    addView(videoLayout)
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { rootLayout ->
                // Update refresh state
                val swipeRefreshLayout = rootLayout.getChildAt(0) as? CustomSwipeRefreshLayout
                swipeRefreshLayout?.isRefreshing = isRefreshing
            }
        )

        // Enhanced Loading overlay with filter download progress
        AnimatedVisibility(
            visible = isLoading || isDownloadingFilters,
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(300))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    if (isDownloadingFilters) {
                        // Filter download progress
                        FilterDownloadProgress(
                            progress = downloadProgress,
                            message = downloadMessage,
                            estimatedTimeLeft = estimatedTimeLeft
                        )
                    } else {
                        // Regular page loading
                        CustomLoadingIndicator()
                        AnimatedLoadingText()
                    }
                }
            }
        }
    }

    // Method to access AdGuard engine for cosmetic filtering
    fun getAdGuardEngine(): AdGuardEngine? = adGuardEngine
}

private fun extractDomain(url: String): String {
    return try {
        val uri = URL(url)
        uri.host ?: ""
    } catch (e: Exception) {
        ""
    }
}

/**
 * Get user agent string exactly like radzadblocker Tools.getUserAgent
 */
private fun getUserAgent(context: Context?, desktopMode: Boolean): String {
    val mobilePrefix = "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + ")"
    val desktopPrefix = "Mozilla/5.0 (X11; Linux " + System.getProperty("os.arch") + ")"

    var newUserAgent = WebSettings.getDefaultUserAgent(context)
    val prefix = newUserAgent.substring(0, newUserAgent.indexOf(")") + 1)

    if (desktopMode) {
        try {
            newUserAgent = newUserAgent.replace(prefix, desktopPrefix)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    } else {
        try {
            newUserAgent = newUserAgent.replace(prefix, mobilePrefix)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    return newUserAgent
}

@SuppressLint("SetJavaScriptEnabled")
private fun setupWebView(
    webView: VideoEnabledWebView,
    mainDomain: String,
    godUrl: String,
    adGuardEngine: AdGuardEngine?,
    onLoadingChanged: (Boolean) -> Unit,
    context: Context,
    memoryManager: WebViewMemoryManager
) {
    // Configure WebSettings with performance optimizations
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false // Allow auto-play for video content
        builtInZoomControls = true
        setSupportZoom(true)
        displayZoomControls = false
        useWideViewPort = true // Enable wide viewport for better video display
        loadWithOverviewMode = true // Enable overview mode for better video display
        userAgentString = getUserAgent(context, false) // CRITICAL: Same as radzadblocker!
        cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
        allowFileAccess = true
        allowContentAccess = true
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // Performance optimizations
        setRenderPriority(WebSettings.RenderPriority.HIGH)
        databaseEnabled = true

        // Memory optimizations
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            setLayoutAlgorithm(WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING)
        }

        // Reduce memory usage
        loadsImagesAutomatically = true
        blockNetworkImage = false
        blockNetworkLoads = false

        // Additional settings for video content support
        allowFileAccessFromFileURLs = true
        allowUniversalAccessFromFileURLs = true
    }

    // Enable hardware acceleration for video
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    // VideoEnabledWebView automatically adds the JavaScript interface
    // No need to manually add it as it's handled by VideoEnabledWebView

    // Use the same WebViewClient as radzadblocker with memory management and error handling
    webView.webViewClient = ComposeWebViewClient(
        mainDomain,
        godUrl,
        adGuardEngine,
        onLoadingChanged,
        memoryManager
    ) { errorMsg ->
        // This will be set when we have access to the error state setters
        Log.e("WebView", "Error callback: $errorMsg")
    }
}

private fun setupFullscreenSupport(
    webView: WebView,
    activity: ComponentActivity,
    onFullscreenChanged: (Boolean) -> Unit
) {
    webView.webChromeClient = object : WebChromeClient() {
        private var customView: View? = null
        private var customViewCallback: CustomViewCallback? = null

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (customView != null) {
                onHideCustomView()
                return
            }

            customView = view
            customViewCallback = callback

            // Enter fullscreen
            enterFullscreen(activity)
            onFullscreenChanged(true)

            // Add custom view to activity
            val decorView = activity.window.decorView as ViewGroup
            decorView.addView(
                customView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        override fun onHideCustomView() {
            if (customView == null) return

            // Remove custom view
            val decorView = activity.window.decorView as ViewGroup
            decorView.removeView(customView)

            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null

            // Exit fullscreen
            exitFullscreen(activity, webView)
            onFullscreenChanged(false)
        }
    }
}

private fun enterFullscreen(activity: ComponentActivity) {
    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
}

private fun exitFullscreen(activity: ComponentActivity, webView: WebView) {
    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}

private class ComposeWebViewClient(
    private val mainDomain: String,
    val godUrl: String, // Make public so cosmetic filtering can access it
    private val adGuardEngine: AdGuardEngine?,
    private val onLoadingChanged: (Boolean) -> Unit,
    private val memoryManager: WebViewMemoryManager,
    private val onError: (String) -> Unit = {}
) : WebViewClient() {

    // Declare a variable to hold the fetched ad hosts (like radzadblocker)
    private var adHosts = mutableListOf<String>()

    // Thread-safe variable to store current document URL
    @Volatile
    private var currentDocumentUrl: String = ""

    private val redirectPattern = """(redirect|ref|click|url|go|jump|forward|redir)\?url=([a-zA-Z0-9\-._~:/?#\\@!$&'()*+,;%=]+)"""

    init {
        // Fetch ad hosts asynchronously to prevent blocking
        CoroutineScope(Dispatchers.IO).launch {
            try {
                "https://raw.githubusercontent.com/Radzdevteam/test/refs/heads/main/adhost".fetchAdHosts()
            } catch (e: Exception) {
                Log.e("AdHosts", "Failed to fetch ad hosts", e)
            }
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        // Update current document URL in a thread-safe way
        currentDocumentUrl = url ?: ""
        onLoadingChanged(true)

        // 1DM-style: Dynamically load filters for this website
        url?.let { pageUrl ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    adGuardEngine?.loadFiltersForWebsite(pageUrl)
                } catch (e: Exception) {
                    Log.w("ComposeWebView", "Failed to load dynamic filters for $pageUrl", e)
                }
            }
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        try {
            // 👑 ABSOLUTE GOD MODE: The exact main URL from MainActivity always finishes loading!
            if (url == godUrl) {
                Log.d("WebView", "👑 GOD URL - ALWAYS FINISH LOADING: $url")
                super.onPageFinished(view, url)
                Log.d("WebView", "Page finished loading: $url")

                // 👑 GOD URL gets NO filtering - works like regular browser
                Log.d("WebView", "👑 GOD URL - NO FILTERING APPLIED (regular browser mode): $url")

                onLoadingChanged(false)
                return
            }

            // 👑 GOD MODE EXTENDED: Handle video streaming redirects in onPageFinished
            if (url != null) {
                val videoStreamingDomains = listOf("mixdrop", "vide0", "bigwarp", "streamtape", "doodstream", "upstream")
                val godDomain = try { java.net.URI(godUrl).host?.lowercase() ?: "" } catch (e: Exception) { "" }
                val currentDomain = try { java.net.URI(url).host?.lowercase() ?: "" } catch (e: Exception) { "" }
                val godPath = try { java.net.URI(godUrl).path } catch (e: Exception) { "" }
                val currentPath = try { java.net.URI(url).path } catch (e: Exception) { "" }

                val isGodVideoSite = videoStreamingDomains.any { godDomain.contains(it) }
                val isCurrentVideoSite = videoStreamingDomains.any { currentDomain.contains(it) }

                if (isGodVideoSite && isCurrentVideoSite && godPath.isNotEmpty() && currentPath == godPath) {
                    Log.d("WebView", "👑 GOD URL VIDEO REDIRECT - ALWAYS FINISH LOADING: $url (redirect from: $godUrl)")
                    super.onPageFinished(view, url)
                    Log.d("WebView", "Page finished loading: $url")

                    // 👑 GOD URL video redirects get NO filtering - work like regular browser
                    Log.d("WebView", "👑 GOD URL VIDEO REDIRECT - NO FILTERING APPLIED (regular browser mode): $url")

                    onLoadingChanged(false)
                    return
                }
            }

            // Ensure the page finishes loading only if it's not a blocked URL (like radzadblocker)
            if (url != null && (adHosts.any { url.contains(it) } || Regex(redirectPattern).containsMatchIn(url))) {
                return
            }
            super.onPageFinished(view, url)
            Log.d("WebView", "Page finished loading: $url")

            // Apply cosmetic filtering and aggressive popup blocking
            url?.let {
                try {
                    applyCosmeticFiltering(view, it)
                    applyAggressivePopupBlocking(view, it, godUrl)
                } catch (e: Exception) {
                    Log.e("WebView", "Error applying cosmetic filtering", e)
                }
            }

            onLoadingChanged(false)
            // Note: SwipeRefreshLayout refresh state is handled in the update block
        } catch (e: Exception) {
            Log.e("WebView", "Error in onPageFinished", e)
            onLoadingChanged(false)
        }
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val urlString = request?.url?.toString()
        return processWebRequest(view, urlString)
    }

    private fun processWebRequest(view: WebView?, url: String?): WebResourceResponse? {
        if (url == null) return null

        // 👑 ABSOLUTE GOD MODE: The exact main URL from MainActivity is NEVER EVER blocked!
        // This must be the FIRST check before any other filtering logic
        if (url == godUrl) {
            Log.d("WebView", "👑 ABSOLUTE GOD URL - NEVER BLOCKED: $url")
            return null
        }

        // 👑 GOD MODE EXTENDED: Also allow the main URL with different protocols or slight variations
        val godUrlNormalized = godUrl.lowercase().replace("https://", "").replace("http://", "")
        val currentUrlNormalized = url.lowercase().replace("https://", "").replace("http://", "")
        if (currentUrlNormalized == godUrlNormalized) {
            Log.d("WebView", "👑 GOD URL (normalized) - NEVER BLOCKED: $url")
            return null
        }

        // 👑 SMART GOD MODE FOR RESOURCES: If the document URL is the GOD URL, allow essential web assets but filter ads
        val documentUrl = currentDocumentUrl
        if (documentUrl == godUrl || documentUrl.lowercase().replace("https://", "").replace("http://", "") == godUrlNormalized) {
            val godDomain = try { java.net.URI(godUrl).host?.lowercase() ?: "" } catch (e: Exception) { "" }
            val resourceDomain = try { java.net.URI(url).host?.lowercase() ?: "" } catch (e: Exception) { "" }

            // Always allow same-domain resources
            if (resourceDomain == godDomain) {
                Log.d("WebView", "👑 GOD URL SAME-DOMAIN RESOURCE - ALLOWED: $url")
                return null
            }

            // Allow essential web assets (JS, CSS, fonts, images, videos) but not obvious ads
            val urlLower = url.lowercase()
            val isEssentialAsset = urlLower.contains(".js") ||  // Allows .js, .js?, .js/124s=, etc.
                                 urlLower.contains(".css") || // Allows .css, .css?, .css/version=, etc.
                                 urlLower.contains(".woff") ||
                                 urlLower.contains(".woff2") ||
                                 urlLower.contains(".ttf") ||
                                 urlLower.contains(".otf") ||
                                 urlLower.contains(".jpg") ||
                                 urlLower.contains(".jpeg") ||
                                 urlLower.contains(".png") ||
                                 urlLower.contains(".gif") ||
                                 urlLower.contains(".webp") ||
                                 // Video formats with flexible matching
                                 urlLower.contains(".mp4") ||  // Allows .mp4, .mp4?, .mp4=abcsc.012232, etc.
                                 urlLower.contains(".webm") || // Allows .webm, .webm?, .webm=xyz123, etc.
                                 urlLower.contains(".m4v") ||  // Allows .m4v, .m4v?, .m4v=params, etc.
                                 urlLower.contains(".mkv") ||  // Allows .mkv, .mkv?, .mkv=abcsc.012232, etc.
                                 urlLower.contains(".avi") ||  // Allows .avi, .avi?, .avi=params, etc.
                                 urlLower.contains(".mov") ||  // Allows .mov, .mov?, .mov=params, etc.
                                 urlLower.contains(".wmv") ||  // Allows .wmv, .wmv?, .wmv=params, etc.
                                 urlLower.contains(".flv") ||  // Allows .flv, .flv?, .flv=params, etc.
                                 urlLower.contains(".3gp") ||  // Allows .3gp, .3gp?, .3gp=params, etc.
                                 urlLower.contains(".ts") ||   // Allows .ts, .ts?, .ts=params, etc.
                                 urlLower.contains(".m3u8") || // Allows .m3u8, .m3u8?, .m3u8=params, etc.
                                 // Audio formats
                                 urlLower.contains(".mp3") ||  // Allows .mp3, .mp3?, .mp3=params, etc.
                                 urlLower.contains(".aac") ||  // Allows .aac, .aac?, .aac=params, etc.
                                 urlLower.contains(".ogg") ||  // Allows .ogg, .ogg?, .ogg=params, etc.
                                 urlLower.contains(".wav") ||  // Allows .wav, .wav?, .wav=params, etc.
                                 // CDN and asset paths
                                 urlLower.contains("/cdn/") ||
                                 urlLower.contains("/assets/") ||
                                 urlLower.contains("/static/")

            // Check if it's an obvious ad domain (these should be blocked even if they're JS files)
            val isObviousAd = urlLower.contains("moduliretina.shop") ||
                            urlLower.contains("aroundcommoditysway.com") ||
                            urlLower.contains("commoditysway.com") ||
                            urlLower.contains("workredbay.shop") ||
                            urlLower.contains("faqirsgoliard.top") ||
                            urlLower.contains("goliard.top") ||
                            urlLower.contains("lunatazetas.top") ||
                            urlLower.contains("bereave.shop") ||
                            urlLower.contains("/gd/") ||
                            urlLower.contains("/sn/bg/") ||
                            urlLower.contains("doubleclick") ||
                            urlLower.contains("googleads") ||
                            urlLower.contains("googlesyndication")

            if (isEssentialAsset && !isObviousAd) {
                Log.d("WebView", "👑 GOD URL ESSENTIAL ASSET - ALLOWED: $url")
                return null
            } else {
                Log.d("WebView", "FILTERED URL: $url")
                // Continue to normal AdGuard filtering for ads and other external resources
            }
        }

        // Log only potential ad URLs to reduce noise
        if (url.contains("ad") || url.contains("popup") || url.contains("banner") ||
            url.contains("track") || url.contains("analytics") || url.contains("doubleclick")) {
            Log.d("WebView", "🔍 Checking potential ad URL: $url")
        }

        // Extract domain once for efficiency
        val urlDomain = extractDomain(url)

        // ⚠️ REMOVED: Don't bypass AdGuard for main domain - this was allowing ads!
        // We need to filter ALL requests, including from main domain, to block ads properly

        // Whitelist of essential domains that should never be blocked
        val essentialDomains = listOf(
            "code.jquery.com",
            "ajax.googleapis.com",
            "www.google.com",
            "www.gstatic.com",
            "fonts.googleapis.com",
            "fonts.gstatic.com",
            "cdnjs.cloudflare.com",
            "cdn.jsdelivr.net",
            "unpkg.com"
        )

        if (essentialDomains.any { urlDomain.contains(it) }) {
            Log.d("WebView", "Allowing essential resource: $url")
            return null
        }

        // Whitelist for video content patterns (CDNs, video files, subtitles, thumbnails)
        val videoContentPatterns = listOf(
            ".mp4", ".webm", ".avi", ".mkv", ".mov", ".m4v",  // Video files
            ".vtt", ".srt", ".ass", ".ssa",                   // Subtitle files
            ".jpg", ".jpeg", ".png", ".webp", ".gif",         // Image/thumbnail files
            "mxcontent.net",                                  // MixDrop CDN
            "/delivery/",                                     // Specific CDN pattern
            "/cdn/",                                         // Specific CDN pattern
            "/stream/",                                      // Specific streaming pattern
            "/player/",                                      // Specific video player resources
            "/video/",                                       // Specific video-related resources
            "/media/"                                        // Specific media files
            // ⚠️ REMOVED: "content", "assets" - too broad, was allowing ads!
        )

        // Allow video content patterns
        if (videoContentPatterns.any { url.contains(it, ignoreCase = true) }) {
            Log.d("WebView", "Allowing video content: $url")
            return null
        }

        // Removed custom fast blocking - rely only on AdGuard filters

        // Block URLs that start with 'intent://'
        if (url.startsWith("intent://")) {
            Log.d("WebView", "Blocked intent URL: $url")
            return adGuardEngine?.createBlockedResponse()
        }

        // 👑 GOD MODE EXTENDED: Handle redirects with same path (e.g., domain changes but same video)
        // This handles cases like bigwarp.io -> bigwarp.cc but same path
        val godPath = try { java.net.URI(godUrl).path } catch (e: Exception) { "" }
        val currentPath = try { java.net.URI(url).path } catch (e: Exception) { "" }

        if (godPath.isNotEmpty() && currentPath == godPath && currentPath.contains("/e/")) {
            Log.d("WebView", "👑 GOD URL PATH MATCH - NEVER BLOCKED: $url (same path as: $godUrl)")
            return null
        }

        // 👑 GOD MODE SUPER EXTENDED: Handle common video streaming redirects
        // If the original URL was a video streaming site, allow redirects to other video streaming domains
        val videoStreamingDomains = listOf("mixdrop", "vide0", "bigwarp", "streamtape", "doodstream", "upstream")
        val godDomain = try { java.net.URI(godUrl).host?.lowercase() ?: "" } catch (e: Exception) { "" }
        val currentDomain = try { java.net.URI(url).host?.lowercase() ?: "" } catch (e: Exception) { "" }

        val isGodVideoSite = videoStreamingDomains.any { godDomain.contains(it) }
        val isCurrentVideoSite = videoStreamingDomains.any { currentDomain.contains(it) }

        if (isGodVideoSite && isCurrentVideoSite && godPath.isNotEmpty() && currentPath == godPath) {
            Log.d("WebView", "👑 GOD URL VIDEO REDIRECT - NEVER BLOCKED: $url (video redirect from: $godUrl)")
            return null
        }

        // Use AdGuard engine for enhanced blocking (only for external domains/resources)
        try {
            // Use thread-safe stored document URL
            val documentUrl = currentDocumentUrl
            val contentType = adGuardEngine?.getContentType(url) ?: ContentType.OTHER

            if (adGuardEngine?.shouldBlock(url, documentUrl, contentType) == true) {
                Log.i("WebView", "🚫 BLOCKED by AdGuard: $url")
                return adGuardEngine.createBlockedResponse()
            } else {
                // Enhanced debugging for why AdGuard is allowing content
                val isThirdParty = adGuardEngine?.isThirdPartyRequest(url, documentUrl) ?: false
                val engineInitialized = adGuardEngine?.isInitialized ?: false

                // 🔍 DEBUG: Log suspicious URLs that AdGuard is allowing with detailed info
                if (url.contains("ad") || url.contains("popup") || url.contains("track") ||
                    url.contains("analytics") || url.contains("goliard") || url.contains("commoditysway") ||
                    url.contains("workredbay") || url.contains("bereave") || url.contains("lunatazetas") ||
                    url.contains("faqirsgoliard") || url.contains("nd.lunatazetas")) {
                    Log.w("WebView", "⚠️ SUSPICIOUS URL ALLOWED by AdGuard: $url")
                    Log.w("WebView", "   📊 Debug Info:")
                    Log.w("WebView", "   - Document URL: $documentUrl")
                    Log.w("WebView", "   - Content Type: $contentType")
                    Log.w("WebView", "   - Third Party: $isThirdParty")
                    Log.w("WebView", "   - Engine Initialized: $engineInitialized")

                    // Get engine stats for debugging
                    adGuardEngine?.let { engine ->
                        val stats = engine.getStats()
                        Log.w("WebView", "   - Blocking Rules: ${stats["blockingRules"]}")
                        Log.w("WebView", "   - Exception Rules: ${stats["exceptionRules"]}")
                        Log.w("WebView", "   - Total Matches: ${stats["totalMatches"]}")
                    }
                } else {
                    Log.d("WebView", "✅ ALLOWED by AdGuard: $url (3rdParty: $isThirdParty, Init: $engineInitialized)")
                }
            }
        } catch (e: Exception) {
            Log.w("WebView", "AdGuard engine error, falling back to legacy blocking", e)
        }

        // Fallback to legacy blocking methods (exactly like radzadblocker)

        // Enhanced popup blocking patterns (1DM-style)
        val popupPatterns = listOf(
            // Original patterns
            "^https?://(?:www\\.|[a-z0-9]{7,10}\\.)?[a-z0-9-]{5,}\\.(?:com|bid|link|live|online|top|club)//?(?:[a-z0-9]{2}/){2,3}[a-f0-9]{32}\\.js$",
            "^https?://(?:[a-z]{2}\\.)?[0-9a-z]{5,16}\\.[a-z]{3,7}/[a-z](?=[a-z]{0,25}[0-9A-Z])[0-9a-zA-Z]{3,26}/\\d{4,5}(?:\\?[_v]=\\d+)?$",

            // 🚫 AGGRESSIVE PATTERNS: Block the specific ad domains that AdGuard is missing
            ".*faqirsgoliard\\.top.*",
            ".*aroundcommoditysway\\.com.*",
            ".*topworkredbay\\.shop.*",
            ".*lunatazetas\\.top.*",
            ".*espinelbereave\\.shop.*",
            ".*goliard\\.top.*",
            ".*commoditysway\\.com.*",
            ".*workredbay\\.shop.*",
            ".*bereave\\.shop.*",

            // Additional 1DM-style patterns for video sites
            ".*popup.*",
            ".*overlay.*",
            ".*modal.*",
            ".*advertisement.*",
            ".*sponsor.*",
            ".*promo.*",
            ".*banner.*",
            ".*interstitial.*",
            ".*preroll.*",
            ".*midroll.*",
            ".*postroll.*",

            // Video site specific patterns
            ".*mixdrop.*popup.*",
            ".*mixdrop.*ad.*",
            ".*mixdrop.*sponsor.*",
            ".*claim.*reward.*",
            ".*download.*gift.*",
            ".*continue.*watching.*",

            // WhatsApp/Chat ad patterns
            ".*whatsapp.*earn.*",
            ".*whatsapp.*money.*",
            ".*earn.*100.*day.*",
            ".*financial.*freedom.*",
            ".*working.*comfort.*",
            ".*conversation.*reply.*"
        )

        // Block popups based on enhanced patterns
        for (pattern in popupPatterns) {
            try {
                if (url.matches(Regex(pattern, RegexOption.IGNORE_CASE))) {
                    Log.d("WebView", "🚫 Blocked popup URL (1DM-style): $url")
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream("".toByteArray()))
                }
            } catch (e: Exception) {
                // Ignore regex errors and continue
            }
        }

        // Block known ad hosts
        if (adHosts.any { url.contains(it) }) {
            Log.d("WebView", "Blocked ad host: $url")
            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream("".toByteArray()))
        }

        return null
    }

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        if (url != null) {
            // 👑 ABSOLUTE GOD MODE: The exact main URL from MainActivity is NEVER blocked for navigation!
            if (url == godUrl) {
                Log.d("WebView", "👑 ABSOLUTE GOD URL NAVIGATION - NEVER BLOCKED: $url")
                return false // Allow navigation
            }

            // 👑 GOD MODE EXTENDED: Also allow navigation to main URL with different protocols
            val godUrlNormalized = godUrl.lowercase().replace("https://", "").replace("http://", "")
            val currentUrlNormalized = url.lowercase().replace("https://", "").replace("http://", "")
            if (currentUrlNormalized == godUrlNormalized) {
                Log.d("WebView", "👑 GOD URL NAVIGATION (normalized) - NEVER BLOCKED: $url")
                return false // Allow navigation
            }

            // 👑 GOD MODE SUPER EXTENDED: Allow video streaming redirects
            val videoStreamingDomains = listOf("mixdrop", "vide0", "bigwarp", "streamtape", "doodstream", "upstream")
            val godDomain = try { java.net.URI(godUrl).host?.lowercase() ?: "" } catch (e: Exception) { "" }
            val currentDomain = try { java.net.URI(url).host?.lowercase() ?: "" } catch (e: Exception) { "" }
            val godPath = try { java.net.URI(godUrl).path } catch (e: Exception) { "" }
            val currentPath = try { java.net.URI(url).path } catch (e: Exception) { "" }

            val isGodVideoSite = videoStreamingDomains.any { godDomain.contains(it) }
            val isCurrentVideoSite = videoStreamingDomains.any { currentDomain.contains(it) }

            if (isGodVideoSite && isCurrentVideoSite && godPath.isNotEmpty() && currentPath == godPath) {
                Log.d("WebView", "👑 GOD URL VIDEO NAVIGATION - NEVER BLOCKED: $url (video redirect from: $godUrl)")
                return false // Allow navigation
            }

            val newDomain = extractDomain(url)

            // Block if the new domain does not match the main domain
            if (newDomain != mainDomain) {
                return true
            }

            // Additional checks for intent URLs and ad hosts
            if (url.startsWith("intent://")) {
                return true
            }

            if (adHosts.any { url.contains(it) } || Regex(redirectPattern).containsMatchIn(url)) {
                return true
            }
        }
        return super.shouldOverrideUrlLoading(view, url)
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        super.onReceivedError(view, request, error)
        val errorMsg = "Page load error: ${error?.description}"
        Log.e("WebView", errorMsg)
        onLoadingChanged(false)
        onError(errorMsg)
    }

    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
        super.onReceivedHttpError(view, request, errorResponse)
        val errorMsg = "HTTP error: ${errorResponse?.statusCode}"
        Log.e("WebView", errorMsg)
        if (errorResponse?.statusCode in 400..599) {
            onError(errorMsg)
        }
    }

    // Handle SSL (exactly like radzadblocker)
    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        val errorMsg = "SSL error: ${error?.toString()}"
        Log.w("WebView", errorMsg)
        // Proceed with SSL errors like the original implementation
        handler?.proceed()
    }

    // Extension function to fetch ad hosts (exactly like radzadblocker)
    private fun String.fetchAdHosts() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(this@fetchAdHosts)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val hosts = reader.readLines().filter { it.isNotBlank() }
                    adHosts.clear()
                    adHosts.addAll(hosts)
                    Log.d("AdHosts", "Loaded ${adHosts.size} ad hosts")
                } else {
                    Log.w("AdHosts", "Failed to fetch ad hosts: ${connection.responseCode}")
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e("AdHosts", "Error fetching ad hosts", e)
            }
        }
    }

    // Method to access AdGuard engine for cosmetic filtering
    fun getAdGuardEngine(): AdGuardEngine? = adGuardEngine
}

// Helper functions (exactly like radzadblocker)

private fun applyCosmeticFiltering(view: WebView?, url: String) {
    // Apply AdGuard cosmetic filtering with performance optimizations
    if (view == null) return

    // 👑 ABSOLUTE GOD MODE: The main URL from MainActivity gets ZERO filtering - works like regular browser
    // Get the GOD URL from the WebViewClient
    val client = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        view.webViewClient as? ComposeWebViewClient
    } else {
        TODO("VERSION.SDK_INT < O")
    }
    val godUrl = client?.godUrl ?: ""

    if (godUrl.isNotEmpty()) {
        // Check exact match first
        if (url == godUrl) {
            Log.d("AdGuard", "👑 GOD URL - NO COSMETIC FILTERING (regular browser mode): $url")
            return
        }

        // Check normalized versions (http vs https)
        val godNormalized = godUrl.lowercase().replace("https://", "").replace("http://", "")
        val urlNormalized = url.lowercase().replace("https://", "").replace("http://", "")
        if (urlNormalized == godNormalized) {
            Log.d("AdGuard", "👑 GOD URL (normalized) - NO COSMETIC FILTERING (regular browser mode): $url")
            return
        }
    }

    // Debounce cosmetic filtering to prevent excessive calls
    CoroutineScope(Dispatchers.Main).launch {
        delay(500) // Wait 500ms before applying cosmetic filtering

        try {
            val adGuardEngine = client?.getAdGuardEngine()

            if (adGuardEngine != null) {
                // Use the correct method name from AdGuard engine with performance limits
                val cosmeticRules = adGuardEngine.getCosmeticRules(url)
                if (cosmeticRules.isEmpty()) return@launch

                val cssSelectors = cosmeticRules
                    .filter { it.cssSelector.isNotEmpty() }
                    .map { it.cssSelector }
                    .take(50) // Limit to 50 selectors for performance

                if (cssSelectors.isNotEmpty()) {
                    val css = cssSelectors.joinToString(", ") + " { display: none !important; }"
                    val javascript = """
                        (function() {
                            try {
                                var style = document.createElement('style');
                                style.type = 'text/css';
                                style.innerHTML = '$css';
                                document.head.appendChild(style);
                            } catch(e) {
                                console.log('AdGuard cosmetic filtering error:', e);
                            }
                        })();
                    """.trimIndent()

                    view.evaluateJavascript(javascript) { result ->
                        Log.d("AdGuard", "Applied cosmetic filtering: ${cssSelectors.size} rules")
                    }
                }

                // Apply JavaScript rules with safety limits
                val jsRules = adGuardEngine.getJavaScriptRules(url).take(10) // Max 10 JS rules
                jsRules.forEach { rule ->
                    if (rule.jsCode.isNotEmpty() && rule.jsCode.length < 10000) { // Limit script size
                        val safeScript = """
                            (function() {
                                try {
                                    ${rule.jsCode}
                                } catch(e) {
                                    console.log('AdGuard JS rule error:', e);
                                }
                            })();
                        """.trimIndent()

                        view.evaluateJavascript(safeScript) { result ->
                            Log.d("AdGuard", "Applied JavaScript rule")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AdGuard", "Error applying cosmetic filtering", e)
        }
    }
}

/**
 * CustomSwipeRefreshLayout - Custom SwipeRefreshLayout that works with WebView
 */
class CustomSwipeRefreshLayout : SwipeRefreshLayout {

    // Reference to the WebView to check if it's at the top
    private var webView: WebView? = null

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    // Set the WebView when it's available
    fun setWebView(webView: WebView) {
        this.webView = webView
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Only allow swipe refresh if the WebView is at the top
        if (isWebViewAtTop()) {
            // Check if the user is pressing near the center of the screen
            val centerY = height / 2f
            val touchY = ev.y

            // Define the area where pull-to-refresh should be disabled (near the center)
            val centerAreaThreshold = 0.4f // Adjust this value to control the center area (40% of the height)

            // Prevent refresh if touch is near the center of the screen
            val minY = centerY - (centerAreaThreshold * centerY)
            val maxY = centerY + (centerAreaThreshold * centerY)
            if (touchY in minY..maxY) {
                return false
            }
            return super.onInterceptTouchEvent(ev)
        }
        return false // Block the gesture if WebView is not at the top
    }

    // Function to check if the WebView is at the top of the page
    private fun isWebViewAtTop(): Boolean {
        return webView?.scrollY == 0
    }
}

/**
 * VideoEnabledWebView - Custom WebView with video support and JavaScript interface
 */
class VideoEnabledWebView : WebView {
    inner class JavascriptInterface {
        @android.webkit.JavascriptInterface
        @Suppress("unused")
        fun notifyVideoEnd() // Must match Javascript interface method of VideoEnabledWebChromeClient
        {
            Log.d("___", "GOT IT")

            // Notify the VideoEnabledWebChromeClient, and then the custom VideoEnabledWebChromeClient.ToggledFullscreenCallback
            if (videoEnabledWebChromeClient != null) {
                videoEnabledWebChromeClient!!.onHideCustomView()
            }
        }
    }

    private var videoEnabledWebChromeClient: VideoEnabledWebChromeClient? = null
    private var addedJavascriptInterface = false

    @Suppress("unused")
    constructor(context: Context?) : super(context!!)

    @Suppress("unused")
    constructor(context: Context?, attrs: AttributeSet?) : super(context!!, attrs)

    @Suppress("unused")
    constructor(context: Context?, attrs: AttributeSet?, defStyle: Int) : super(
        context!!,
        attrs,
        defStyle
    )

    /**
     * Indicates if the video is being displayed using a custom view (typically full-screen)
     * @return true it the video is being displayed using a custom view (typically full-screen)
     */
    @get:Suppress("unused")
    val isVideoFullscreen: Boolean
        get() = videoEnabledWebChromeClient != null && videoEnabledWebChromeClient!!.isVideoFullscreen

    /**
     * Pass only a VideoEnabledWebChromeClient instance.
     */
    @SuppressLint("SetJavaScriptEnabled")
    override fun setWebChromeClient(client: WebChromeClient?) {
        settings.javaScriptEnabled = true
        if (client is VideoEnabledWebChromeClient) {
            this.videoEnabledWebChromeClient = client
        }
        super.setWebChromeClient(client)
    }

    override fun loadData(data: String, mimeType: String?, encoding: String?) {
        addJavascriptInterface()
        super.loadData(data, mimeType, encoding)
    }

    override fun loadDataWithBaseURL(
        baseUrl: String?,
        data: String,
        mimeType: String?,
        encoding: String?,
        historyUrl: String?
    ) {
        addJavascriptInterface()
        super.loadDataWithBaseURL(baseUrl, data, mimeType, encoding, historyUrl)
    }

    override fun loadUrl(url: String) {
        addJavascriptInterface()
        super.loadUrl(url)
    }

    override fun loadUrl(url: String, additionalHttpHeaders: Map<String, String>) {
        addJavascriptInterface()
        super.loadUrl(url, additionalHttpHeaders)
    }

    private fun addJavascriptInterface() {
        if (!addedJavascriptInterface) {
            // Add javascript interface to be called when the video ends (must be done before page load)
            addJavascriptInterface(
                JavascriptInterface(),
                "_VideoEnabledWebView"
            ) // Must match Javascript interface name of VideoEnabledWebChromeClient
            addedJavascriptInterface = true
        }
    }
}

/**
 * VideoEnabledWebChromeClient - Custom WebChromeClient for video support with fullscreen handling
 */
class VideoEnabledWebChromeClient : WebChromeClient, MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener,
    MediaPlayer.OnErrorListener {
    private var getScrollY = 0

    interface ToggledFullscreenCallback {
        fun toggledFullscreen(fullscreen: Boolean)
    }

    private var activityNonVideoView: View? = null
    private var activityVideoView: ViewGroup? = null
    private var loadingView: View? = null
    private var webView: VideoEnabledWebView? = null

    /**
     * Indicates if the video is being displayed using a custom view (typically full-screen)
     * @return true it the video is being displayed using a custom view (typically full-screen)
     */
    var isVideoFullscreen: Boolean =
        false // Indicates if the video is being displayed using a custom view (typically full-screen)
        private set
    private var videoViewContainer: FrameLayout? = null
    private var videoViewCallback: CustomViewCallback? = null

    private var toggledFullscreenCallback: ToggledFullscreenCallback? = null

    /**
     * Never use this constructor alone.
     * This constructor allows this class to be defined as an inline inner class in which the user can override methods
     */
    @Suppress("unused")
    constructor()

    /**
     * Builds a video enabled WebChromeClient.
     * @param activityNonVideoView A View in the activity's layout that contains every other view that should be hidden when the video goes full-screen.
     * @param activityVideoView A ViewGroup in the activity's layout that will display the video. Typically you would like this to fill the whole layout.
     */
    @Suppress("unused")
    constructor(activityNonVideoView: View?, activityVideoView: ViewGroup?) {
        this.activityNonVideoView = activityNonVideoView
        this.activityVideoView = activityVideoView
        this.loadingView = null
        this.webView = null
        this.isVideoFullscreen = false
    }

    /**
     * Builds a video enabled WebChromeClient.
     * @param activityNonVideoView A View in the activity's layout that contains every other view that should be hidden when the video goes full-screen.
     * @param activityVideoView A ViewGroup in the activity's layout that will display the video. Typically you would like this to fill the whole layout.
     * @param loadingView A View to be shown while the video is loading (typically only used in API level <11). Must be already inflated and not attached to a parent view.
     */
    @Suppress("unused")
    constructor(activityNonVideoView: View?, activityVideoView: ViewGroup?, loadingView: View?) {
        this.activityNonVideoView = activityNonVideoView
        this.activityVideoView = activityVideoView
        this.loadingView = loadingView
        this.webView = null
        this.isVideoFullscreen = false
    }

    /**
     * Builds a video enabled WebChromeClient.
     * @param activityNonVideoView A View in the activity's layout that contains every other view that should be hidden when the video goes full-screen.
     * @param activityVideoView A ViewGroup in the activity's layout that will display the video. Typically you would like this to fill the whole layout.
     * @param loadingView A View to be shown while the video is loading (typically only used in API level <11). Must be already inflated and not attached to a parent view.
     * @param webView The owner VideoEnabledWebView. Passing it will enable the VideoEnabledWebChromeClient to detect the HTML5 video ended event and exit full-screen.
     * Note: The web page must only contain one video tag in order for the HTML5 video ended event to work. This could be improved by using Javascript to detect the video end event and calling a Javascript interface method.
     */
    constructor(
        activityNonVideoView: View?,
        activityVideoView: ViewGroup?,
        loadingView: View?,
        webView: VideoEnabledWebView?
    ) {
        this.activityNonVideoView = activityNonVideoView
        this.activityVideoView = activityVideoView
        this.loadingView = loadingView
        this.webView = webView
        this.isVideoFullscreen = false
    }

    /**
     * Set a callback that will be fired when the video starts or finishes displaying using a custom view (typically full-screen)
     * @param callback A VideoEnabledWebChromeClient.ToggledFullscreenCallback callback
     */
    fun setOnToggledFullscreen(callback: ToggledFullscreenCallback?) {
        this.toggledFullscreenCallback = callback
    }

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        if (view is FrameLayout) {
            // A video wants to be shown
            val frameLayout = view
            val focusedChild = frameLayout.focusedChild

            // Save video related variables
            this.isVideoFullscreen = true
            this.videoViewContainer = frameLayout
            this.videoViewCallback = callback

            // Hide the non-video view, add the video view, and show it
            activityNonVideoView!!.visibility = View.INVISIBLE
            activityVideoView!!.addView(
                videoViewContainer,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            activityVideoView!!.visibility = View.VISIBLE

            if (focusedChild is VideoView) {
                // VideoView (typically API level <11)
                val videoView = focusedChild

                // Handle all the required events
                videoView.setOnPreparedListener(this)
                videoView.setOnCompletionListener(this)
                videoView.setOnErrorListener(this)
            } else {
                // Other classes, including:
                // - android.webkit.HTML5VideoFullScreen$VideoSurfaceView, which inherits from android.view.SurfaceView (typically API level 11-18)
                // - android.webkit.HTML5VideoFullScreen$VideoTextureView, which inherits from android.view.TextureView (typically API level 11-18)
                // - com.android.org.chromium.content.browser.ContentVideoView$VideoSurfaceView, which inherits from android.view.SurfaceView (typically API level 19+)

                // Handle HTML5 video ended event only if the class is a SurfaceView
                // Test case: TextureView of Sony Xperia T API level 16 doesn't work fullscreen when loading the javascript below
                if (webView != null && webView!!.settings.javaScriptEnabled && focusedChild is SurfaceView) {
                    // Run javascript code that detects the video end and notifies the Javascript interface
                    var js = "javascript:"
                    js += "var _ytrp_html5_video_last;"
                    js += "var _ytrp_html5_video = document.getElementsByTagName('video')[0];"
                    js += "if (_ytrp_html5_video != undefined && _ytrp_html5_video != _ytrp_html5_video_last) {"
                    run {
                        js += "_ytrp_html5_video_last = _ytrp_html5_video;"
                        js += "function _ytrp_html5_video_ended() {"
                        run {
                            js += "_VideoEnabledWebView.notifyVideoEnd();" // Must match Javascript interface name and method of VideoEnableWebView
                        }
                        js += "}"
                        js += "_ytrp_html5_video.addEventListener('ended', _ytrp_html5_video_ended);"
                    }
                    js += "}"
                    webView!!.loadUrl(js)
                }
            }

            // Notify full-screen change
            if (toggledFullscreenCallback != null) {
                getScrollY = webView!!.scrollY
                toggledFullscreenCallback!!.toggledFullscreen(true)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("deprecation")
    override fun onShowCustomView(
        view: View,
        requestedOrientation: Int,
        callback: CustomViewCallback
    ) // Available in API level 14+, deprecated in API level 18+
    {
        onShowCustomView(view, callback)
    }

    override fun onHideCustomView() {
        // This method should be manually called on video end in all cases because it's not always called automatically.
        // This method must be manually called on back key press (from this class' onBackPressed() method).

        if (isVideoFullscreen) {
            // Hide the video view, remove it, and show the non-video view
            activityVideoView!!.visibility = View.INVISIBLE
            activityVideoView!!.removeView(videoViewContainer)
            activityNonVideoView!!.visibility = View.VISIBLE

            // Call back (only in API level <19, because in API level 19+ with chromium webview it crashes)
            if (videoViewCallback != null && !videoViewCallback!!.javaClass.name.contains(".chromium.")) {
                videoViewCallback!!.onCustomViewHidden()
            }

            // Reset video related variables
            isVideoFullscreen = false
            videoViewContainer = null
            videoViewCallback = null

            // Notify full-screen change
            if (toggledFullscreenCallback != null) {
                toggledFullscreenCallback!!.toggledFullscreen(false)
                webView!!.scrollTo(0, getScrollY)
            }
        }
    }

    /**
     * Notifies the class that the back key has been pressed by the user.
     * This must be called from the Activity's onBackPressed(), and if it returns true, the activity itself should not handle the event
     * @return true if the event was handled, and false if was not (video view is not visible)
     */
    fun onBackPressed(): Boolean {
        if (isVideoFullscreen) {
            onHideCustomView()
            return true
        }
        return false
    }

    override fun onPrepared(mp: MediaPlayer) {
        if (loadingView != null) {
            loadingView!!.visibility = View.GONE
        }
    }

    override fun onCompletion(mp: MediaPlayer) {
        onHideCustomView()
    }

    override fun onError(mp: MediaPlayer, what: Int, extra: Int): Boolean {
        return false // By returning false, onCompletion() will be called
    }
}

/**
 * WebViewMemoryManager - Manages WebView memory usage and prevents crashes
 */
class WebViewMemoryManager {
    private var webViewRef: WeakReference<VideoEnabledWebView>? = null
    private var adGuardEngineRef: WeakReference<AdGuardEngine>? = null
    private val memoryThreshold = 0.75 // 75% memory usage threshold
    private val criticalMemoryThreshold = 0.9 // 90% critical threshold
    private val runtime = Runtime.getRuntime()
    private var lastCleanupTime = 0L
    private val cleanupCooldown = 60000L // 1 minute cooldown between cleanups

    companion object {
        private const val TAG = "WebViewMemoryManager"
        private const val MEMORY_CHECK_INTERVAL = 15000L // 15 seconds
        private const val LOW_MEMORY_THRESHOLD = 50 * 1024 * 1024 // 50MB
    }

    fun registerWebView(webView: VideoEnabledWebView) {
        webViewRef = WeakReference(webView)
        startMemoryMonitoring()
    }

    fun registerAdGuardEngine(engine: AdGuardEngine?) {
        adGuardEngineRef = engine?.let { WeakReference(it) }
    }

    private fun startMemoryMonitoring() {
        // Monitor memory usage periodically
        CoroutineScope(Dispatchers.IO).launch {
            while (webViewRef?.get() != null) {
                try {
                    checkMemoryUsage()
                    delay(MEMORY_CHECK_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "Error monitoring memory", e)
                    break
                }
            }
        }
    }

    private fun checkMemoryUsage() {
        val maxMemory = runtime.maxMemory()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        val availableMemory = maxMemory - usedMemory
        val memoryUsage = usedMemory.toDouble() / maxMemory.toDouble()

        Log.d(TAG, "Memory usage: ${(memoryUsage * 100).toInt()}% (${usedMemory / 1024 / 1024}MB / ${maxMemory / 1024 / 1024}MB)")

        val currentTime = System.currentTimeMillis()
        val canCleanup = currentTime - lastCleanupTime > cleanupCooldown

        when {
            memoryUsage > criticalMemoryThreshold -> {
                Log.e(TAG, "Critical memory usage detected! Performing emergency cleanup")
                if (canCleanup) {
                    performEmergencyCleanup()
                    lastCleanupTime = currentTime
                }
            }
            memoryUsage > memoryThreshold -> {
                Log.w(TAG, "High memory usage detected, triggering cleanup")
                if (canCleanup) {
                    performMemoryCleanup()
                    lastCleanupTime = currentTime
                }
            }
            availableMemory < LOW_MEMORY_THRESHOLD -> {
                Log.w(TAG, "Low available memory detected")
                if (canCleanup) {
                    performLightCleanup()
                    lastCleanupTime = currentTime
                }
            }
        }
    }

    private fun performLightCleanup() {
        webViewRef?.get()?.let { webView ->
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    // Light cleanup - only clear form data and force GC
                    webView.clearFormData()
                    System.gc()
                    Log.i(TAG, "Light memory cleanup completed")
                } catch (e: Exception) {
                    Log.e(TAG, "Error during light cleanup", e)
                }
            }
        }
    }

    private fun performMemoryCleanup() {
        webViewRef?.get()?.let { webView ->
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    // Standard cleanup
                    webView.clearCache(false) // Don't include disk files
                    webView.clearFormData()

                    // Force garbage collection
                    System.gc()

                    Log.i(TAG, "Memory cleanup completed")
                } catch (e: Exception) {
                    Log.e(TAG, "Error during memory cleanup", e)
                }
            }
        }
    }

    private fun performEmergencyCleanup() {
        webViewRef?.get()?.let { webView ->
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    // Aggressive cleanup
                    webView.clearCache(true) // Include disk files
                    webView.clearHistory()
                    webView.clearFormData()
                    webView.clearMatches()

                    // Clear AdGuard caches if available
                    try {
                        adGuardEngineRef?.get()?.clearCaches()
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not clear AdGuard caches", e)
                    }

                    // Multiple GC calls for emergency
                    repeat(3) {
                        System.gc()
                        delay(100)
                    }

                    Log.i(TAG, "Emergency cleanup completed")
                } catch (e: Exception) {
                    Log.e(TAG, "Error during emergency cleanup", e)
                }
            }
        }
    }

    fun cleanupWebView(webView: VideoEnabledWebView) {
        try {
            // Stop loading
            webView.stopLoading()

            // Clear all data
            webView.clearCache(true)
            webView.clearHistory()
            webView.clearFormData()
            webView.clearMatches()

            // Remove all views
            webView.removeAllViews()

            // Destroy WebView
            webView.destroy()

            Log.i(TAG, "WebView cleanup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up WebView", e)
        }
    }

    fun cleanup() {
        webViewRef?.clear()
        webViewRef = null
    }
}

/**
 * Crash Prevention Functions
 */
private fun setupCrashPrevention(context: Context) {
    // Set up uncaught exception handler for WebView crashes
    val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
        Log.e("CrashPrevention", "Uncaught exception in thread ${thread.name}", exception)

        // Handle WebView specific crashes
        when {
            exception.message?.contains("WebView") == true -> {
                Log.e("CrashPrevention", "WebView crash detected, attempting recovery")
                // Don't crash the app for WebView errors
                return@setDefaultUncaughtExceptionHandler
            }
            exception is OutOfMemoryError -> {
                Log.e("CrashPrevention", "Out of memory error, triggering cleanup")
                System.gc()
                return@setDefaultUncaughtExceptionHandler
            }
            else -> {
                // Let the default handler handle other exceptions
                defaultHandler?.uncaughtException(thread, exception)
            }
        }
    }
}

@Composable
private fun ErrorScreen(
    errorMessage: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "WebView Error",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = errorMessage.ifEmpty { "An error occurred while loading the page" },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}

/**
 * Filter Download Progress - Shows download progress with percentage and time estimate
 */
@Composable
private fun FilterDownloadProgress(
    progress: Int,
    message: String,
    estimatedTimeLeft: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Progress Circle
        Box(
            modifier = Modifier.size(120.dp),
            contentAlignment = Alignment.Center
        ) {
            // Background circle
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.2f),
                    style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Progress circle
            Canvas(modifier = Modifier.fillMaxSize()) {
                val sweepAngle = (progress / 100f) * 360f
                drawArc(
                    color = androidx.compose.ui.graphics.Color(0xFF4CAF50),
                    startAngle = -90f,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Progress text
            Text(
                text = "$progress%",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        // Status message
        Text(
            text = message,
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 16.sp
            ),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        // Time estimate
        if (estimatedTimeLeft.isNotEmpty() && estimatedTimeLeft != "0s") {
            Text(
                text = "⏱️ $estimatedTimeLeft remaining",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp
                )
            )
        }

        // Info text
        Text(
            text = "📥 Downloading latest ad blocking filters\n🛡️ This ensures maximum protection against ads",
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp
            ),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

/**
 * Custom animated loading indicator with rotating circles
 */
@Composable
fun CustomLoadingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "loading")

    // Rotation animation
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = androidx.compose.animation.core.LinearEasing)
        ),
        label = "rotation"
    )

    // Scale animation for pulsing effect
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        modifier = Modifier.size(80.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outer rotating ring
        Canvas(
            modifier = Modifier
                .size(70.dp)
                .rotate(rotation)
        ) {
            drawArc(
                color = Color(0xFF4CAF50),
                startAngle = 0f,
                sweepAngle = 120f,
                useCenter = false,
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
            )
            drawArc(
                color = Color(0xFF2196F3),
                startAngle = 140f,
                sweepAngle = 100f,
                useCenter = false,
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
            )
            drawArc(
                color = Color(0xFFFF9800),
                startAngle = 260f,
                sweepAngle = 80f,
                useCenter = false,
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // Inner pulsing circle
        Canvas(
            modifier = Modifier.size((30 * scale).dp)
        ) {
            drawCircle(
                color = Color.White.copy(alpha = 0.8f),
                radius = size.minDimension / 2
            )
        }
    }
}

/**
 * Animated loading text with typing effect
 */
@Composable
fun AnimatedLoadingText() {
    val infiniteTransition = rememberInfiniteTransition(label = "text")

    // Dots animation
    val dotCount by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "dots"
    )

    // Text opacity animation
    val textAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "alpha"
    )

    val dots = ".".repeat(dotCount.toInt())

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Loading$dots",
            color = Color.White.copy(alpha = textAlpha),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 20.sp
            )
        )

        Text(
            text = "Please wait while we prepare your content",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp
            )
        )
    }
}



/**
 * Apply aggressive popup blocking (1DM-style) - DISABLED to preserve website functionality
 * AdGuard filter-based blocking still works for all URLs
 */
private fun applyAggressivePopupBlocking(view: WebView?, url: String, godUrl: String) {
    if (view == null) return

    // DISABLED: JavaScript popup blocking interferes with website functionality
    // AdGuard filter-based ad blocking still works for all URLs including GOD URL
    Log.d("PopupBlocker", "🛡️ JavaScript popup blocking DISABLED - using AdGuard filters only: $url")
}


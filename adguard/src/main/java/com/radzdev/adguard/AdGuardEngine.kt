package com.radzdev.adguard

import android.content.Context
import android.util.Log
import android.webkit.WebResourceResponse
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Main AdGuard filtering engine
 */
class AdGuardEngine(private val context: Context) {

    companion object {
        private const val TAG = "AdGuardEngine"
    }

    private val parser = AdGuardParser()
    private val filterManager = FilterManager(context)
    private val blockingMatcher = RuleMatcher()
    private val exceptionMatcher = RuleMatcher()
    private val cosmeticRules = mutableListOf<FilterRule>()
    private val jsRules = mutableListOf<FilterRule>()

    // Cache for cosmetic rules
    private val cosmeticCache = ConcurrentHashMap<String, List<FilterRule>>()

    var isInitialized = false
        private set

    // GOD URL - the main URL that should NEVER be blocked from loading
    private var godUrl: String = ""
    private var godDomain: String = ""
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Progress callback for filter downloading
    private var onProgressUpdate: ((progress: Int, message: String, estimatedTimeLeft: String) -> Unit)? = null

    /**
     * Initialize the engine with full filter download and progress tracking
     */
    suspend fun initialize() {
        if (isInitialized) return

        Log.i(TAG, "🔄 Initializing AdGuard engine with full filter download...")

        try {
            // 📊 PROGRESS TRACKING: Download all filters with progress updates
            onProgressUpdate?.invoke(0, "Starting filter download...", "Calculating...")

            val startTime = System.currentTimeMillis()
            val success = filterManager.updateCoreFiltersWithProgress { progress, currentFilter, totalFilters ->
                val percentage = (progress * 100) / totalFilters
                val elapsed = System.currentTimeMillis() - startTime
                val estimatedTotal = if (progress > 0) (elapsed * totalFilters) / progress else 0
                val remaining = estimatedTotal - elapsed
                val timeLeft = if (remaining > 0) "${remaining / 1000}s" else "Almost done..."

                onProgressUpdate?.invoke(
                    percentage,
                    "Downloading $currentFilter ($progress/$totalFilters)",
                    timeLeft
                )
            }

            if (success) {
                onProgressUpdate?.invoke(90, "Processing filters...", "5s")

                // Load all filter contents
                val allFilterContent = filterManager.getCoreFilterContents()
                if (allFilterContent.isNotEmpty()) {
                    parseAndAddRules(allFilterContent)
                }
            } else {
                onProgressUpdate?.invoke(0, "Download failed, using basic filters", "0s")
                // Fallback to basic embedded filters
                val basicContent = filterManager.getCoreFilterContents()
                if (basicContent.isNotEmpty()) {
                    parseAndAddRules(basicContent)
                }
            }

            onProgressUpdate?.invoke(100, "Filters ready!", "0s")

            isInitialized = true
            val blockingCount = blockingMatcher.getRuleCounts()["totalRules"] ?: 0
            val exceptionCount = exceptionMatcher.getRuleCounts()["totalRules"] ?: 0
            Log.i(TAG, "✅ AdGuard engine initialized with $blockingCount blocking rules, " +
                    "$exceptionCount exception rules, ${cosmeticRules.size} cosmetic rules")

            // Debug: Log enabled filters
            val enabledFilters = filterManager.getFilterLists().filter { it.enabled }
            Log.i(TAG, "Enabled filters: ${enabledFilters.map { "${it.name} (${it.ruleCount} rules)" }}")

            // Start background optimization (1DM-style)
            startBackgroundOptimization()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AdGuard engine", e)
            onProgressUpdate?.invoke(0, "Initialization failed", "0s")
        }
    }
    
    /**
     * Dynamically load additional filters for a website (1DM-style)
     */
    suspend fun loadFiltersForWebsite(url: String) {
        if (!isInitialized) return

        val domain = extractDomain(url)
        if (domain.isEmpty()) return

        try {
            Log.i(TAG, "Loading dynamic filters for domain: $domain")
            val loadedFilters = filterManager.loadFiltersForDomain(domain)

            if (loadedFilters.isNotEmpty()) {
                // Get newly loaded dynamic filter content
                val dynamicContent = filterManager.getDynamicFilterContents()
                if (dynamicContent.isNotEmpty()) {
                    parseAndAddRules(dynamicContent)

                    val blockingCount = blockingMatcher.getRuleCounts()["totalRules"] ?: 0
                    val exceptionCount = exceptionMatcher.getRuleCounts()["totalRules"] ?: 0
                    Log.i(TAG, "Dynamic filters loaded for $domain: $blockingCount total blocking rules, " +
                            "$exceptionCount exception rules, ${cosmeticRules.size} cosmetic rules")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load dynamic filters for $domain", e)
        }
    }

    /**
     * Reload filters from FilterManager
     */
    suspend fun reloadFilters() {
        Log.i(TAG, "Reloading filters...")

        // Clear existing rules
        blockingMatcher.clear()
        exceptionMatcher.clear()
        cosmeticRules.clear()
        jsRules.clear()
        clearCaches()

        // Reload from FilterManager (core + dynamic)
        val allFilterContent = filterManager.getAllEnabledFilterContents()
        if (allFilterContent.isNotEmpty()) {
            parseAndAddRules(allFilterContent)
        }

        val blockingCount = blockingMatcher.getRuleCounts()["totalRules"] ?: 0
        val exceptionCount = exceptionMatcher.getRuleCounts()["totalRules"] ?: 0
        Log.i(TAG, "Filters reloaded: $blockingCount blocking rules, " +
                "$exceptionCount exception rules, ${cosmeticRules.size} cosmetic rules")
    }
    
    /**
     * Parse and add rules to the engine
     */
    private fun parseAndAddRules(content: String) {
        Log.d(TAG, "Parsing filter content: ${content.length} characters")
        val rules = parser.parseRules(content)
        Log.d(TAG, "Parsed ${rules.size} total rules")

        val blockingRules = mutableListOf<FilterRule>()
        val exceptionRules = mutableListOf<FilterRule>()
        var invalidRules = 0
        var commentRules = 0

        rules.forEach { rule ->
            when (rule.ruleType) {
                RuleType.BLOCKING -> blockingRules.add(rule)
                RuleType.EXCEPTION -> exceptionRules.add(rule)
                RuleType.COSMETIC_HIDING, RuleType.COSMETIC_CSS -> cosmeticRules.add(rule)
                RuleType.JAVASCRIPT -> jsRules.add(rule)
                RuleType.COMMENT -> commentRules++
                RuleType.INVALID -> invalidRules++
                else -> { /* Ignore other rule types */ }
            }
        }

        Log.d(TAG, "Rule breakdown: ${blockingRules.size} blocking, ${exceptionRules.size} exception, " +
                "${cosmeticRules.size} cosmetic, ${jsRules.size} JS, $commentRules comments, $invalidRules invalid")

        // Add rules to optimized matchers
        if (blockingRules.isNotEmpty()) {
            blockingMatcher.addRules(blockingRules)
        }
        if (exceptionRules.isNotEmpty()) {
            exceptionMatcher.addRules(exceptionRules)
        }

        // Warn if no blocking rules were added
        if (blockingRules.isEmpty()) {
            Log.w(TAG, "⚠️ WARNING: No blocking rules were parsed from the filter content!")
        }
    }

    /**
     * Set the GOD URL that should never be blocked from loading
     */
    fun setGodUrl(url: String) {
        godUrl = url
        godDomain = extractDomain(url)
        Log.i(TAG, "Main URL set: $godUrl (domain: $godDomain)")
    }

    /**
     * Set callback for progress updates during filter download
     */
    fun setProgressCallback(callback: (progress: Int, message: String, estimatedTimeLeft: String) -> Unit) {
        onProgressUpdate = callback
        Log.i(TAG, "📊 Progress callback registered")
    }

    /**
     * Check if a URL should be blocked using optimized matchers
     */
    fun shouldBlock(
        url: String,
        documentUrl: String = "",
        contentType: ContentType = ContentType.OTHER
    ): Boolean {
        if (!isInitialized) return false

        // 👑 ABSOLUTE GOD MODE: The GOD URL is NEVER blocked by AdGuard
        if (godUrl.isNotEmpty()) {
            if (url == godUrl) {
                Log.d(TAG, "👑 GOD URL - NEVER BLOCKED by AdGuard: $url")
                return false
            }

            // Also check normalized versions (http vs https)
            val godNormalized = godUrl.lowercase().replace("https://", "").replace("http://", "")
            val urlNormalized = url.lowercase().replace("https://", "").replace("http://", "")
            if (urlNormalized == godNormalized) {
                Log.d(TAG, "👑 GOD URL (normalized) - NEVER BLOCKED by AdGuard: $url")
                return false
            }

            // Also protect the GOD domain
            if (extractDomain(url) == godDomain) {
                Log.d(TAG, "👑 GOD DOMAIN - NEVER BLOCKED by AdGuard: $url")
                return false
            }
        }

        val isThirdParty = isThirdPartyRequest(url, documentUrl)

        // Check exception rules first (they have higher priority)
        val hasException = exceptionMatcher.shouldBlock(url, documentUrl, contentType, isThirdParty)
        if (hasException) {
            // Debug suspicious URLs that have exception rules
            if (url.contains("ad") || url.contains("popup") || url.contains("track") ||
                url.contains("analytics") || url.contains("goliard") || url.contains("commoditysway") ||
                url.contains("workredbay") || url.contains("bereave") || url.contains("lunatazetas") ||
                url.contains("faqirsgoliard") || url.contains("nd.lunatazetas")) {
                Log.w(TAG, "🔓 EXCEPTION RULE matched for suspicious URL: $url")
                Log.w(TAG, "   - Document: $documentUrl, Type: $contentType, 3rdParty: $isThirdParty")
            }
            return false
        }

        // Check blocking rules
        val shouldBlock = blockingMatcher.shouldBlock(url, documentUrl, contentType, isThirdParty)

        // Debug suspicious URLs that are not being blocked
        if (!shouldBlock && (url.contains("ad") || url.contains("popup") || url.contains("track") ||
            url.contains("analytics") || url.contains("goliard") || url.contains("commoditysway") ||
            url.contains("workredbay") || url.contains("bereave") || url.contains("lunatazetas") ||
            url.contains("faqirsgoliard") || url.contains("nd.lunatazetas"))) {
            Log.w(TAG, "❌ NO BLOCKING RULE found for suspicious URL: $url")
            Log.w(TAG, "   - Document: $documentUrl, Type: $contentType, 3rdParty: $isThirdParty")
        }

        return shouldBlock
    }
    
    /**
     * Get cosmetic rules for a domain
     */
    fun getCosmeticRules(documentUrl: String): List<FilterRule> {
        if (!isInitialized) return emptyList()
        
        cosmeticCache[documentUrl]?.let { return it }
        
        val matchingRules = cosmeticRules.filter { rule ->
            rule.matches("", documentUrl)
        }
        
        cosmeticCache[documentUrl] = matchingRules
        return matchingRules
    }
    
    /**
     * Get JavaScript rules for a domain
     */
    fun getJavaScriptRules(documentUrl: String): List<FilterRule> {
        if (!isInitialized) return emptyList()
        
        return jsRules.filter { rule ->
            rule.matches("", documentUrl)
        }
    }
    
    /**
     * Create a blocked response
     */
    fun createBlockedResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            ByteArrayInputStream("".toByteArray())
        )
    }
    
    /**
     * Determine content type from URL and context
     */
    fun getContentType(url: String, acceptHeader: String? = null): ContentType {
        val lowerUrl = url.lowercase()
        
        return when {
            lowerUrl.contains(".css") || acceptHeader?.contains("text/css") == true -> ContentType.STYLESHEET
            lowerUrl.contains(".js") || acceptHeader?.contains("javascript") == true -> ContentType.SCRIPT
            lowerUrl.matches(Regex(".*\\.(jpg|jpeg|png|gif|webp|svg|ico).*")) -> ContentType.IMAGE
            lowerUrl.matches(Regex(".*\\.(woff|woff2|ttf|otf|eot).*")) -> ContentType.FONT
            lowerUrl.matches(Regex(".*\\.(mp4|webm|ogg|mp3|wav|flac).*")) -> ContentType.MEDIA
            lowerUrl.contains("websocket") -> ContentType.WEBSOCKET
            acceptHeader?.contains("text/html") == true -> ContentType.DOCUMENT
            else -> ContentType.OTHER
        }
    }
    
    /**
     * Check if request is third-party
     */
    fun isThirdPartyRequest(url: String, documentUrl: String): Boolean {
        if (documentUrl.isEmpty()) return false
        
        val urlDomain = extractDomain(url)
        val documentDomain = extractDomain(documentUrl)
        
        if (urlDomain.isEmpty() || documentDomain.isEmpty()) return false
        
        // Consider subdomains as same party
        return !urlDomain.endsWith(documentDomain) && !documentDomain.endsWith(urlDomain)
    }
    
    /**
     * Extract domain from URL
     */
    private fun extractDomain(url: String): String {
        return try {
            val cleanUrl = if (url.startsWith("//")) "http:$url" else url
            val uri = URL(cleanUrl)
            uri.host?.lowercase() ?: ""
        } catch (e: Exception) {
            ""
        }
    }
    
    /**
     * Clear caches to free memory
     */
    fun clearCaches() {
        cosmeticCache.clear()
        blockingMatcher.optimizeMemory()
        exceptionMatcher.optimizeMemory()
    }
    
    /**
     * Get engine statistics including performance metrics
     */
    fun getStats(): Map<String, Any> {
        val blockingStats = blockingMatcher.getRuleCounts()
        val exceptionStats = exceptionMatcher.getRuleCounts()
        val blockingPerf = blockingMatcher.getPerformanceStats()
        val exceptionPerf = exceptionMatcher.getPerformanceStats()

        val engineStats = mapOf(
            "blockingRules" to (blockingStats["totalRules"] ?: 0),
            "exceptionRules" to (exceptionStats["totalRules"] ?: 0),
            "cosmeticRules" to cosmeticRules.size,
            "jsRules" to jsRules.size,
            "cosmeticCacheSize" to cosmeticCache.size,
            "blockingCacheHitRate" to (blockingPerf["cacheHitRate"] ?: "0%"),
            "exceptionCacheHitRate" to (exceptionPerf["cacheHitRate"] ?: "0%"),
            "totalMatches" to ((blockingPerf["totalMatches"] as? Int ?: 0) + (exceptionPerf["totalMatches"] as? Int ?: 0))
        )

        val filterStats = filterManager.getUpdateStats()

        return engineStats + filterStats
    }

    /**
     * Get filter manager for external access
     */
    fun getFilterManager(): FilterManager = filterManager
    
    /**
     * Start background optimization (1DM-style performance)
     */
    private fun startBackgroundOptimization() {
        scope.launch {
            while (isInitialized) {
                try {
                    delay(30000) // Every 30 seconds

                    // Optimize memory usage
                    clearCaches()

                    // Force garbage collection if memory is low
                    val runtime = Runtime.getRuntime()
                    val usedMemory = runtime.totalMemory() - runtime.freeMemory()
                    val maxMemory = runtime.maxMemory()

                    if (usedMemory > maxMemory * 0.8) {
                        Log.i(TAG, "Memory usage high (${usedMemory / 1024 / 1024}MB), optimizing...")
                        blockingMatcher.optimizeMemory()
                        exceptionMatcher.optimizeMemory()
                        System.gc()
                    }

                } catch (e: Exception) {
                    Log.w(TAG, "Background optimization error", e)
                }
            }
        }
    }

    /**
     * Clean up resources
     */
    fun destroy() {
        scope.cancel()
        clearCaches()
        blockingMatcher.clear()
        exceptionMatcher.clear()
        cosmeticRules.clear()
        jsRules.clear()
        filterManager.destroy()
        isInitialized = false
    }
}

package com.radzdev.adguard

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages filter lists and their updates
 */
class FilterManager(private val context: Context) {
    
    companion object {
        private const val TAG = "FilterManager"
        private const val PREFS_NAME = "adguard_filters"
        private const val KEY_LAST_UPDATE = "last_update_"
        private const val KEY_ENABLED = "enabled_"
        private const val KEY_DYNAMIC_LOADED = "dynamic_loaded_"
        private const val UPDATE_INTERVAL_HOURS = 24 // Update every 24 hours
        
        // Core filter lists (1DM-style: loaded initially - now from offline assets)
        val CORE_FILTERS = listOf(
            FilterListInfo(
                id = "1dm_custom",
                name = "1DM Custom Filter",
                description = "Custom filter list for 1DM-style ad blocking",
                assetPath = "adguard_filters/1DM_Custom_Filter.txt",
                enabled = true
            ),
            FilterListInfo(
                id = "easylist",
                name = "EasyList (Main)",
                description = "Primary filter list that removes most adverts from international webpages",
                assetPath = "adguard_filters/EasyList_Main_.txt",
                enabled = true
            ),
            FilterListInfo(
                id = "ublock_filters",
                name = "uBlock Filters (Main)",
                description = "uBlock Origin's own filters for enhanced blocking",
                assetPath = "adguard_filters/uBlock_Filters_Main_.txt",
                enabled = true
            ),
            FilterListInfo(
                id = "adguard_base_without_easylist",
                name = "AdGuard Base (without EasyList)",
                description = "AdGuard Base filter without duplicating EasyList rules",
                assetPath = "adguard_filters/AdGuard_Base_without_EasyList_.txt",
                enabled = true
            ),
            FilterListInfo(
                id = "easyprivacy",
                name = "EasyPrivacy",
                description = "Blocks tracking scripts and information collectors",
                assetPath = "adguard_filters/EasyPrivacy.txt",
                enabled = true
            )
        )

        // Dynamic filter lists (1DM-style: ALL the filters 1DM uses, loaded on-demand - now from offline assets)
        val DYNAMIC_FILTERS = listOf(
            // Anti-adblock and bypass filters
            FilterListInfo(
                id = "peter_lowe_hosts",
                name = "YoYo Hosts Ad Server Blocklist",
                description = "Comprehensive ad server blocking list in hosts format",
                assetPath = "adguard_filters/YoYo_Hosts_Ad_Server_Blocklist.txt",
                enabled = false
            ),
            FilterListInfo(
                id = "anti_adblock_killer",
                name = "Anti-Adblock Killer (by Reek)",
                description = "Bypasses anti-adblock detection",
                assetPath = "adguard_filters/Anti_Adblock_Killer_by_Reek_.txt",
                enabled = false
            ),
            FilterListInfo(
                id = "easylist_antiadblock",
                name = "Anti-Adblock Filters (Adblock Plus)",
                description = "Blocks anti-adblock scripts",
                assetPath = "adguard_filters/Anti_Adblock_Filters_Adblock_Plus_.txt",
                enabled = false
            ),
            FilterListInfo(
                id = "adguard_mobile",
                name = "AdGuard Mobile Ads Filter",
                description = "Blocks ads in mobile apps and websites",
                assetPath = "adguard_filters/AdGuard_Mobile_Ads_Filter.txt",
                enabled = false
            ),
            FilterListInfo(
                id = "ublock_resource_abuse",
                name = "uBlock Resource Abuse",
                description = "Blocks cryptocurrency miners and resource abuse",
                assetPath = "adguard_filters/uBlock_Resource_Abuse.txt",
                enabled = false
            ),
            FilterListInfo(
                id = "adguard_annoyances_full",
                name = "AdGuard Annoyances Filter",
                description = "Comprehensive annoyance blocking including cookie notices",
                assetPath = "adguard_filters/AdGuard_Annoyances_Filter.txt",
                enabled = false
            ),
            FilterListInfo(
                id = "fanboy_annoyance",
                name = "Fanboy's Annoyance List",
                description = "Blocks social media widgets, in-page pop-ups and other annoyances",
                assetPath = "adguard_filters/Fanboy_s_Annoyance_List.txt",
                enabled = false
            ),
            FilterListInfo(
                id = "ublock_annoyances",
                name = "uBlock Annoyances",
                description = "uBlock Origin's annoyance filters",
                assetPath = "adguard_filters/uBlock_Annoyances.txt",
                enabled = false
            ),
            FilterListInfo(
                id = "fanboy_indian",
                name = "Fanboy's Indian List",
                description = "Blocks ads on Indian websites",
                assetPath = "adguard_filters/Fanboy_s_Indian_List.txt",
                enabled = false
            ),
            FilterListInfo(
                id = "adguard_social_media",
                name = "AdGuard Social Media Filter (Windows)",
                description = "Blocks social media widgets and tracking",
                assetPath = "adguard_filters/AdGuard_Social_Media_Filter_Windows_.txt",
                enabled = false
            ),
            FilterListInfo(
                id = "abpindo",
                name = "ABPindo (Indonesian Rules)",
                description = "Indonesian ad blocking rules",
                assetPath = "adguard_filters/ABPindo_Indonesian_Rules_.txt",
                enabled = false
            ),
            FilterListInfo(
                id = "adguard_base_windows",
                name = "AdGuard Base Filter (Windows)",
                description = "Windows-specific AdGuard base filter",
                assetPath = "adguard_filters/AdGuard_Base_Filter_Windows_.txt",
                enabled = false
            ),
            FilterListInfo(
                id = "adguard_spyware",
                name = "AdGuard Spyware Filter",
                description = "Blocks spyware, adware and tracking",
                assetPath = "adguard_filters/AdGuard_Spyware_Filter.txt",
                enabled = false
            ),
            FilterListInfo(
                id = "easylist_germany",
                name = "EasyList Germany",
                description = "German supplement for EasyList",
                assetPath = "adguard_filters/EasyList_Germany.txt",
                enabled = false
            ),
            FilterListInfo(
                id = "oisd_big",
                name = "OISD Blocklist (abp.oisd.nl)",
                description = "Comprehensive domain blocking list",
                assetPath = "adguard_filters/OISD_Blocklist_abp_oisd_nl_.txt",
                enabled = false
            ),
            FilterListInfo(
                id = "fuckfuckadblock",
                name = "FuckFuckAdBlock (Anti-Anti-Adblock)",
                description = "Advanced anti-adblock circumvention",
                assetPath = "adguard_filters/FuckFuckAdBlock_Anti_Anti_Adblock_.txt",
                enabled = false
            )
        )

        // All filters combined
        val ALL_FILTERS = CORE_FILTERS + DYNAMIC_FILTERS
    }
    
    data class FilterListInfo(
        val id: String,
        val name: String,
        val description: String,
        val assetPath: String, // Path to filter file in assets (offline mode)
        var enabled: Boolean = true,
        var lastUpdated: Long = 0,
        var ruleCount: Int = 0,
        val isDynamic: Boolean = false // 1DM-style dynamic loading flag
    )
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val filtersDir = File(context.filesDir, "adguard_filters")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        // Create filters directory if it doesn't exist
        if (!filtersDir.exists()) {
            filtersDir.mkdirs()
        }
    }
    
    /**
     * Get all available filter lists with their current status
     */
    fun getFilterLists(): List<FilterListInfo> {
        return ALL_FILTERS.map { filter ->
            filter.copy(
                enabled = prefs.getBoolean(KEY_ENABLED + filter.id, filter.enabled),
                lastUpdated = prefs.getLong(KEY_LAST_UPDATE + filter.id, 0),
                ruleCount = getFilterRuleCount(filter.id)
            )
        }
    }

    /**
     * Get only core filters (1DM-style: loaded initially)
     */
    fun getCoreFilters(): List<FilterListInfo> {
        return CORE_FILTERS.map { filter ->
            filter.copy(
                enabled = prefs.getBoolean(KEY_ENABLED + filter.id, filter.enabled),
                lastUpdated = prefs.getLong(KEY_LAST_UPDATE + filter.id, 0),
                ruleCount = getFilterRuleCount(filter.id)
            )
        }
    }
    
    /**
     * Enable or disable a filter list
     */
    fun setFilterEnabled(filterId: String, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED + filterId, enabled).apply()
        Log.i(TAG, "Filter $filterId ${if (enabled) "enabled" else "disabled"}")
    }
    
    /**
     * Check if a filter is enabled
     */
    fun isFilterEnabled(filterId: String): Boolean {
        val defaultEnabled = ALL_FILTERS.find { it.id == filterId }?.enabled ?: false
        return prefs.getBoolean(KEY_ENABLED + filterId, defaultEnabled)
    }

    /**
     * Dynamically load filters based on website domain (1DM-style: intelligent filter selection)
     */
    suspend fun loadFiltersForDomain(domain: String): List<String> = withContext(Dispatchers.IO) {
        Log.i("FilterManager", "🔄 Loading filters for domain: $domain")
        val loadedFilters = mutableListOf<String>()

        // 1DM-style intelligent filter selection based on domain characteristics
        val filtersToLoad = mutableListOf<String>()

        // Always load anti-adblock for any site (1DM does this)
        filtersToLoad.addAll(listOf("anti_adblock_killer", "easylist_antiadblock", "fuckfuckadblock"))

        // Regional filters based on domain
        when {
            domain.contains(".de") || domain.contains("german") -> {
                filtersToLoad.add("easylist_germany")
            }
            domain.contains(".in") || domain.contains("indian") -> {
                filtersToLoad.add("fanboy_indian")
            }
            domain.contains(".ru") || domain.contains("russian") -> {
                filtersToLoad.add("adguard_russian")
            }
            domain.contains(".id") || domain.contains("indonesia") -> {
                filtersToLoad.add("abpindo")
            }
        }

        // Content-type based filters (1DM's smart detection)
        when {
            // Video/streaming sites
            domain.contains("youtube") || domain.contains("video") || domain.contains("stream") ||
            domain.contains("movie") || domain.contains("tv") || domain.contains("netflix") ||
            domain.contains("twitch") || domain.contains("dailymotion") -> {
                filtersToLoad.addAll(listOf("ublock_resource_abuse", "fanboy_annoyance", "ublock_annoyances"))
            }
            // Social media sites
            domain.contains("facebook") || domain.contains("twitter") || domain.contains("instagram") ||
            domain.contains("social") || domain.contains("linkedin") -> {
                filtersToLoad.addAll(listOf("fanboy_annoyance", "ublock_annoyances"))
            }
            // Mobile/app sites
            domain.contains("mobile") || domain.contains("app") || domain.contains("android") -> {
                filtersToLoad.add("adguard_mobile")
            }
            // Suspicious/ad-heavy sites
            domain.contains("free") || domain.contains("download") || domain.contains("torrent") ||
            domain.contains("crack") || domain.contains("serial") -> {
                filtersToLoad.addAll(listOf("peter_lowe_hosts", "oisd_big", "adguard_spyware"))
            }
            // General sites - load comprehensive protection
            else -> {
                filtersToLoad.addAll(listOf("peter_lowe_hosts", "adguard_annoyances_full"))
            }
        }

        // Load each required filter
        for (filterId in filtersToLoad.distinct()) {
            if (!isDynamicFilterLoaded(filterId)) {
                val filter = DYNAMIC_FILTERS.find { it.id == filterId }
                if (filter != null) {
                    try {
                        Log.i(TAG, "1DM-style loading: ${filter.name} for domain: $domain")
                        val success = loadFilterFromAssets(filter)
                        if (success) {
                            markDynamicFilterLoaded(filterId)
                            loadedFilters.add(filterId)
                            Log.i(TAG, "✅ Loaded: ${filter.name}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to load: ${filter.name}", e)
                    }
                }
            }
        }

        Log.i(TAG, "🎯 1DM-style loading complete for $domain: ${loadedFilters.size} new filters loaded")
        loadedFilters
    }

    /**
     * Check if a dynamic filter has been loaded
     */
    private fun isDynamicFilterLoaded(filterId: String): Boolean {
        return prefs.getBoolean(KEY_DYNAMIC_LOADED + filterId, false)
    }

    /**
     * Mark a dynamic filter as loaded
     */
    private fun markDynamicFilterLoaded(filterId: String) {
        prefs.edit().putBoolean(KEY_DYNAMIC_LOADED + filterId, true).apply()
    }
    
    /**
     * Load and update core filters only from assets (1DM-style: fast startup)
     */
    suspend fun updateCoreFilters(): Boolean = withContext(Dispatchers.IO) {
        var allSuccess = true

        for (filter in CORE_FILTERS) {
            if (isFilterEnabled(filter.id)) {
                try {
                    if (shouldUpdateFilter(filter.id)) {
                        Log.i(TAG, "Updating core filter: ${filter.name}")
                        val success = loadFilterFromAssets(filter)
                        if (success) {
                            prefs.edit().putLong(KEY_LAST_UPDATE + filter.id, System.currentTimeMillis()).apply()
                            Log.i(TAG, "Successfully updated core filter: ${filter.name}")
                        } else {
                            allSuccess = false
                            Log.w(TAG, "Failed to update core filter: ${filter.name}")
                        }
                    }
                } catch (e: Exception) {
                    allSuccess = false
                    Log.e(TAG, "Error updating core filter ${filter.name}", e)
                }
            }
        }

        allSuccess
    }

    /**
     * Load and update core filters from assets with progress tracking
     */
    suspend fun updateCoreFiltersWithProgress(
        onProgress: (current: Int, filterName: String, total: Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var allSuccess = true
        val enabledFilters = CORE_FILTERS.filter { isFilterEnabled(it.id) }
        val totalFilters = enabledFilters.size
        var currentIndex = 0

        for (filter in enabledFilters) {
            try {
                currentIndex++
                onProgress(currentIndex, filter.name, totalFilters)

                if (shouldUpdateFilter(filter.id)) {
                    Log.i(TAG, "📥 Loading filter: ${filter.name} ($currentIndex/$totalFilters)")
                    val success = loadFilterFromAssets(filter)
                    if (success) {
                        prefs.edit().putLong(KEY_LAST_UPDATE + filter.id, System.currentTimeMillis()).apply()
                        Log.i(TAG, "✅ Loaded: ${filter.name}")
                    } else {
                        allSuccess = false
                        Log.w(TAG, "❌ Failed: ${filter.name}")
                    }
                } else {
                    Log.i(TAG, "⏭️ Skipped (up to date): ${filter.name}")
                }
            } catch (e: Exception) {
                allSuccess = false
                Log.e(TAG, "❌ Error downloading ${filter.name}", e)
            }
        }

        allSuccess
    }



    /**
     * Load and update all enabled filter lists from assets (including dynamic)
     */
    suspend fun updateAllFilters(): Boolean = withContext(Dispatchers.IO) {
        var allSuccess = true

        for (filter in ALL_FILTERS) {
            if (isFilterEnabled(filter.id)) {
                try {
                    if (shouldUpdateFilter(filter.id)) {
                        Log.i(TAG, "Updating filter: ${filter.name}")
                        val success = loadFilterFromAssets(filter)
                        if (success) {
                            prefs.edit().putLong(KEY_LAST_UPDATE + filter.id, System.currentTimeMillis()).apply()
                            Log.i(TAG, "Successfully updated filter: ${filter.name}")
                        } else {
                            allSuccess = false
                            Log.w(TAG, "Failed to update filter: ${filter.name}")
                        }
                    }
                } catch (e: Exception) {
                    allSuccess = false
                    Log.e(TAG, "Error updating filter ${filter.name}", e)
                }
            }
        }

        allSuccess
    }
    
    /**
     * Force load a specific filter from assets
     */
    suspend fun updateFilter(filterId: String): Boolean = withContext(Dispatchers.IO) {
        val filter = ALL_FILTERS.find { it.id == filterId } ?: return@withContext false

        try {
            val success = loadFilterFromAssets(filter)
            if (success) {
                prefs.edit().putLong(KEY_LAST_UPDATE + filterId, System.currentTimeMillis()).apply()
                Log.i(TAG, "Successfully updated filter: ${filter.name}")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error updating filter ${filter.name}", e)
            false
        }
    }
    
    /**
     * Get the content of a filter list
     */
    fun getFilterContent(filterId: String): String? {
        val file = File(filtersDir, "$filterId.txt")
        return if (file.exists()) {
            try {
                val content = file.readText()
                Log.d(TAG, "Filter $filterId: ${content.length} chars, ${content.lines().size} lines")
                content
            } catch (e: Exception) {
                Log.e(TAG, "Error reading filter file: $filterId", e)
                null
            }
        } else {
            Log.w(TAG, "Filter file not found: $filterId (${file.absolutePath})")
            null
        }
    }
    
    /**
     * Get core filter contents only (1DM-style: fast startup)
     */
    fun getCoreFilterContents(): String {
        val contents = mutableListOf<String>()

        // 🚀 INSTANT PROTECTION: Add basic embedded filters for first-time users
        val hasAnyFilters = CORE_FILTERS.any { isFilterEnabled(it.id) && getFilterContent(it.id) != null }
        if (!hasAnyFilters) {
            Log.i(TAG, "🚀 First-time user: Adding basic embedded filters for instant protection")
            contents.add(getEmbeddedBasicFilters())
        }

        for (filter in CORE_FILTERS) {
            if (isFilterEnabled(filter.id)) {
                getFilterContent(filter.id)?.let { content ->
                    contents.add("! Core Filter: ${filter.name}\n$content")
                }
            }
        }

        return contents.joinToString("\n\n")
    }

    /**
     * Basic embedded filters for instant protection on first install
     */
    private fun getEmbeddedBasicFilters(): String {
        return """
! Embedded Basic Filters - Instant Protection
! Common ad domains that your app encounters
||moduliretina.shop^
||aroundcommoditysway.com^
||commoditysway.com^
||workredbay.shop^
||topworkredbay.shop^
||faqirsgoliard.top^
||goliard.top^
||lunatazetas.top^
||nd.lunatazetas.top^
||bereave.shop^
||espinelbereave.shop^
||doubleclick.net^
||googleadservices.com^
||googlesyndication.com^
||googletagmanager.com^
||google-analytics.com^
||facebook.com/tr^
||connect.facebook.net^
||scorecardresearch.com^
||quantserve.com^
||outbrain.com^
||taboola.com^
||adsystem.com^
||popads.net^
||popcash.net^
||propellerads.com^
||revcontent.com^
||mgid.com^
||criteo.com^
||amazon-adsystem.com^
||adsafeprotected.com^
||moatads.com^
||ads.yahoo.com^
||advertising.com^
! Block common ad paths
/ads/*
/advertisement/*
/advert/*
/adsystem/*
/adnxs/*
/doubleclick/*
/googleads/*
/googlesyndication/*
! Block popup scripts
||*/popup.js
||*/popunder.js
||*/pop.js
        """.trimIndent()
    }

    /**
     * Get dynamic filter contents for loaded filters
     */
    fun getDynamicFilterContents(): String {
        val contents = mutableListOf<String>()

        for (filter in DYNAMIC_FILTERS) {
            if (isDynamicFilterLoaded(filter.id)) {
                getFilterContent(filter.id)?.let { content ->
                    contents.add("! Dynamic Filter: ${filter.name}\n$content")
                }
            }
        }

        return contents.joinToString("\n\n")
    }

    /**
     * Get all enabled filter contents combined (core + dynamic)
     */
    fun getAllEnabledFilterContents(): String {
        val coreContent = getCoreFilterContents()
        val dynamicContent = getDynamicFilterContents()

        // Debug logging
        Log.d(TAG, "Core content length: ${coreContent.length} chars")
        Log.d(TAG, "Dynamic content length: ${dynamicContent.length} chars")

        val result = if (coreContent.isNotEmpty() && dynamicContent.isNotEmpty()) {
            "$coreContent\n\n$dynamicContent"
        } else if (coreContent.isNotEmpty()) {
            coreContent
        } else {
            dynamicContent
        }

        Log.d(TAG, "Total filter content length: ${result.length} chars")
        if (result.isEmpty()) {
            Log.w(TAG, "⚠️ WARNING: No filter content loaded! Check filter downloads.")
        }

        return result
    }
    
    /**
     * Check if a filter needs loading from assets (offline mode)
     * For offline mode, we only load once unless forced
     */
    private fun shouldUpdateFilter(filterId: String): Boolean {
        val lastUpdate = prefs.getLong(KEY_LAST_UPDATE + filterId, 0)

        // For offline mode: load once on first install, then use cached version
        return lastUpdate == 0L
    }
    
    /**
     * Load a filter list from assets (offline mode)
     */
    private suspend fun loadFilterFromAssets(filter: FilterListInfo): Boolean = withContext(Dispatchers.IO) {
        try {
            // Read filter content from assets
            val content = context.assets.open(filter.assetPath).bufferedReader().readText()

            // Save to local file for consistency with existing logic
            val file = File(filtersDir, "${filter.id}.txt")
            FileWriter(file).use { writer ->
                writer.write(content)
            }

            Log.d(TAG, "Loaded filter ${filter.name} from assets: ${content.lines().size} lines")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error loading filter ${filter.name} from assets: ${filter.assetPath}", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error loading filter ${filter.name}", e)
            false
        }
    }
    
    /**
     * Get the number of rules in a filter
     */
    private fun getFilterRuleCount(filterId: String): Int {
        return getFilterContent(filterId)?.lines()?.count { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !trimmed.startsWith("!")
        } ?: 0
    }
    
    /**
     * Get filter update statistics (1DM-style: shows core + dynamic breakdown)
     */
    fun getUpdateStats(): Map<String, Any> {
        val stats = mutableMapOf<String, Any>()
        var totalRules = 0
        var coreRules = 0
        var dynamicRules = 0
        var enabledFilters = 0
        var dynamicFiltersLoaded = 0
        var lastUpdate = 0L

        // Count core filters
        for (filter in CORE_FILTERS) {
            if (isFilterEnabled(filter.id)) {
                enabledFilters++
                val ruleCount = getFilterRuleCount(filter.id)
                coreRules += ruleCount
                totalRules += ruleCount
                val filterLastUpdate = prefs.getLong(KEY_LAST_UPDATE + filter.id, 0)
                if (filterLastUpdate > lastUpdate) {
                    lastUpdate = filterLastUpdate
                }
            }
        }

        // Count dynamic filters
        for (filter in DYNAMIC_FILTERS) {
            if (isDynamicFilterLoaded(filter.id)) {
                dynamicFiltersLoaded++
                val ruleCount = getFilterRuleCount(filter.id)
                dynamicRules += ruleCount
                totalRules += ruleCount
                val filterLastUpdate = prefs.getLong(KEY_LAST_UPDATE + filter.id, 0)
                if (filterLastUpdate > lastUpdate) {
                    lastUpdate = filterLastUpdate
                }
            }
        }

        stats["totalRules"] = totalRules
        stats["coreRules"] = coreRules
        stats["dynamicRules"] = dynamicRules
        stats["enabledFilters"] = enabledFilters
        stats["dynamicFiltersLoaded"] = dynamicFiltersLoaded
        stats["totalFilters"] = ALL_FILTERS.size
        stats["lastUpdate"] = if (lastUpdate > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastUpdate))
        } else {
            "Never"
        }
        
        return stats
    }
    
    /**
     * Clear all cached filter data
     */
    fun clearCache() {
        try {
            filtersDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".txt")) {
                    file.delete()
                }
            }
            
            // Clear preferences
            val editor = prefs.edit()
            for (filter in ALL_FILTERS) {
                editor.remove(KEY_LAST_UPDATE + filter.id)
            }
            editor.apply()
            
            Log.i(TAG, "Cleared all filter cache")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache", e)
        }
    }
    
    /**
     * Clean up resources
     */
    fun destroy() {
        scope.cancel()
    }
}

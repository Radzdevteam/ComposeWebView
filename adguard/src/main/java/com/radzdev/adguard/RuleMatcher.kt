package com.radzdev.adguard

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance rule matcher with optimizations
 */
class RuleMatcher {
    
    companion object {
        private const val TAG = "RuleMatcher"
        private const val MAX_CACHE_SIZE = 10000
        private const val CACHE_CLEANUP_THRESHOLD = 12000
        private const val HOT_RULES_LIMIT = 500 // Most frequently used rules
    }
    
    // Optimized rule storage
    private val domainRules = ConcurrentHashMap<String, MutableList<FilterRule>>()
    private val genericRules = mutableListOf<FilterRule>()
    private val regexRules = mutableListOf<FilterRule>()
    private val simplePatternRules = mutableListOf<FilterRule>()
    
    // Performance caches
    private val matchCache = ConcurrentHashMap<String, Boolean>()
    private val domainCache = ConcurrentHashMap<String, String>()

    // Hot rules cache (1DM-style optimization)
    private val hotRules = mutableListOf<FilterRule>()
    private val ruleHitCount = ConcurrentHashMap<String, Int>()

    // Statistics
    private var cacheHits = 0
    private var cacheMisses = 0
    private var totalMatches = 0
    
    /**
     * Add rules to the matcher with optimization
     */
    fun addRules(rules: List<FilterRule>) {
        Log.d(TAG, "Adding ${rules.size} rules to matcher")
        
        for (rule in rules) {
            when {
                rule.domains.isNotEmpty() -> {
                    // Domain-specific rules for faster lookup
                    for (domain in rule.domains) {
                        domainRules.getOrPut(domain) { mutableListOf() }.add(rule)
                    }
                }
                rule.isRegex -> {
                    regexRules.add(rule)
                }
                rule.pattern.contains("*") || rule.pattern.contains("^") -> {
                    simplePatternRules.add(rule)
                }
                else -> {
                    genericRules.add(rule)
                }
            }
        }
        
        // Sort rules by priority within each category
        genericRules.sortByDescending { it.priority }
        regexRules.sortByDescending { it.priority }
        simplePatternRules.sortByDescending { it.priority }
        
        domainRules.values.forEach { ruleList ->
            ruleList.sortByDescending { it.priority }
        }
        
        Log.d(TAG, "Rules organized: ${domainRules.size} domain groups, " +
                "${genericRules.size} generic, ${regexRules.size} regex, " +
                "${simplePatternRules.size} pattern rules")
    }
    
    /**
     * Check if URL should be blocked with optimized matching
     */
    fun shouldBlock(
        url: String,
        documentUrl: String,
        contentType: ContentType,
        isThirdParty: Boolean
    ): Boolean {
        totalMatches++
        
        // Check cache first
        val cacheKey = "$url|$documentUrl|$contentType|$isThirdParty"
        matchCache[cacheKey]?.let { result ->
            cacheHits++
            return result
        }
        
        cacheMisses++
        
        val result = performMatching(url, documentUrl, contentType, isThirdParty)
        
        // Cache the result (with size limit)
        if (matchCache.size < MAX_CACHE_SIZE) {
            matchCache[cacheKey] = result
        } else if (matchCache.size >= CACHE_CLEANUP_THRESHOLD) {
            // Clear cache when it gets too large (1DM-style)
            clearOldCacheEntries()
        }
        
        return result
    }
    
    private fun performMatching(
        url: String,
        documentUrl: String,
        contentType: ContentType,
        isThirdParty: Boolean
    ): Boolean {
        val domain = extractDomain(documentUrl)

        // 0. Check hot rules first (1DM-style optimization)
        for (rule in hotRules) {
            if (rule.matches(url, documentUrl, contentType, isThirdParty)) {
                trackRuleUsage(rule)
                return rule.ruleType == RuleType.BLOCKING
            }
        }

        // 1. Check domain-specific rules first (fastest)
        if (domain.isNotEmpty()) {
            // Check exact domain match
            domainRules[domain]?.let { rules ->
                for (rule in rules) {
                    if (rule.matches(url, documentUrl, contentType, isThirdParty)) {
                        trackRuleUsage(rule)
                        return rule.ruleType == RuleType.BLOCKING
                    }
                }
            }
            
            // Check parent domains
            val domainParts = domain.split(".")
            for (i in 1 until domainParts.size) {
                val parentDomain = domainParts.subList(i, domainParts.size).joinToString(".")
                domainRules[parentDomain]?.let { rules ->
                    for (rule in rules) {
                        if (rule.matches(url, documentUrl, contentType, isThirdParty)) {
                            return rule.ruleType == RuleType.BLOCKING
                        }
                    }
                }
            }
        }
        
        // 2. Check simple generic rules (fast string matching)
        for (rule in genericRules) {
            if (rule.matches(url, documentUrl, contentType, isThirdParty)) {
                return rule.ruleType == RuleType.BLOCKING
            }
        }
        
        // 3. Check pattern rules (medium speed)
        for (rule in simplePatternRules) {
            if (rule.matches(url, documentUrl, contentType, isThirdParty)) {
                return rule.ruleType == RuleType.BLOCKING
            }
        }
        
        // 4. Check regex rules last (slowest)
        for (rule in regexRules) {
            if (rule.matches(url, documentUrl, contentType, isThirdParty)) {
                return rule.ruleType == RuleType.BLOCKING
            }
        }
        
        return false
    }
    
    /**
     * Extract domain with caching
     */
    private fun extractDomain(url: String): String {
        if (url.isEmpty()) return ""
        
        return domainCache.getOrPut(url) {
            try {
                val cleanUrl = if (url.startsWith("//")) "http:$url" else url
                val uri = java.net.URL(cleanUrl)
                uri.host?.lowercase() ?: ""
            } catch (e: Exception) {
                ""
            }
        }
    }
    
    /**
     * Clear old cache entries to prevent memory issues
     */
    private fun clearOldCacheEntries() {
        val entriesToRemove = matchCache.size - MAX_CACHE_SIZE
        val iterator = matchCache.entries.iterator()
        var removed = 0
        
        while (iterator.hasNext() && removed < entriesToRemove) {
            iterator.next()
            iterator.remove()
            removed++
        }
        
        Log.d(TAG, "Cleared $removed cache entries")
    }
    
    /**
     * Get performance statistics
     */
    fun getPerformanceStats(): Map<String, Any> {
        val hitRate = if (totalMatches > 0) {
            (cacheHits.toDouble() / totalMatches * 100).toInt()
        } else 0
        
        return mapOf(
            "totalMatches" to totalMatches,
            "cacheHits" to cacheHits,
            "cacheMisses" to cacheMisses,
            "cacheHitRate" to "$hitRate%",
            "cacheSize" to matchCache.size,
            "domainGroups" to domainRules.size,
            "genericRules" to genericRules.size,
            "regexRules" to regexRules.size,
            "patternRules" to simplePatternRules.size
        )
    }
    
    /**
     * Clear all caches and rules
     */
    fun clear() {
        matchCache.clear()
        domainCache.clear()
        domainRules.clear()
        genericRules.clear()
        regexRules.clear()
        simplePatternRules.clear()
        
        // Reset statistics
        cacheHits = 0
        cacheMisses = 0
        totalMatches = 0
    }
    
    /**
     * Optimize memory usage
     */
    fun optimizeMemory() {
        // Clear caches if they're getting large
        if (matchCache.size > MAX_CACHE_SIZE) {
            matchCache.clear()
            Log.d(TAG, "Cleared match cache for memory optimization")
        }
        
        if (domainCache.size > MAX_CACHE_SIZE) {
            domainCache.clear()
            Log.d(TAG, "Cleared domain cache for memory optimization")
        }
        
        // Compact rule lists
        domainRules.values.forEach { ruleList ->
            if (ruleList is ArrayList) {
                ruleList.trimToSize()
            }
        }
        
        System.gc() // Suggest garbage collection
    }
    
    /**
     * Get rule count by category
     */
    fun getRuleCounts(): Map<String, Int> {
        val domainRuleCount = domainRules.values.sumOf { it.size }
        
        return mapOf(
            "domainRules" to domainRuleCount,
            "genericRules" to genericRules.size,
            "regexRules" to regexRules.size,
            "patternRules" to simplePatternRules.size,
            "hotRules" to hotRules.size,
            "totalRules" to (domainRuleCount + genericRules.size + regexRules.size + simplePatternRules.size)
        )
    }

    /**
     * Track rule usage for hot rules optimization (1DM-style)
     */
    private fun trackRuleUsage(rule: FilterRule) {
        val ruleKey = rule.originalRule
        val currentCount = ruleHitCount[ruleKey] ?: 0
        ruleHitCount[ruleKey] = currentCount + 1

        // Update hot rules every 100 matches
        if (totalMatches % 100 == 0) {
            updateHotRules()
        }
    }

    /**
     * Update hot rules based on usage statistics
     */
    private fun updateHotRules() {
        // Get top rules by hit count
        val topRules = ruleHitCount.entries
            .sortedByDescending { it.value }
            .take(HOT_RULES_LIMIT)
            .map { it.key }
            .toSet()

        // Rebuild hot rules list
        hotRules.clear()

        // Add from domain rules
        domainRules.values.forEach { rules ->
            rules.filter { it.originalRule in topRules }.forEach { hotRules.add(it) }
        }

        // Add from other rule types
        (genericRules + simplePatternRules + regexRules)
            .filter { it.originalRule in topRules }
            .forEach { hotRules.add(it) }

        // Sort by hit count (most used first)
        hotRules.sortByDescending { ruleHitCount[it.originalRule] ?: 0 }
    }
}

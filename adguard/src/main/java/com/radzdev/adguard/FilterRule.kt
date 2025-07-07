package com.radzdev.adguard

/**
 * Represents different types of filter rules
 */
enum class RuleType {
    BLOCKING,           // Basic blocking rule
    EXCEPTION,          // Exception rule (starts with @@)
    COSMETIC_HIDING,    // Element hiding rule (##)
    COSMETIC_CSS,       // CSS injection rule (#$#)
    JAVASCRIPT,         // JavaScript rule (#%#)
    COMMENT,            // Comment line (starts with !)
    INVALID             // Invalid or unsupported rule
}

/**
 * Represents different content types for filtering
 */
enum class ContentType {
    DOCUMENT,
    SUBDOCUMENT,
    STYLESHEET,
    SCRIPT,
    IMAGE,
    FONT,
    OBJECT,
    MEDIA,
    XMLHTTPREQUEST,
    WEBSOCKET,
    PING,
    OTHER
}

/**
 * Represents a parsed filter rule with all its components
 */
data class FilterRule(
    val originalRule: String,
    val ruleType: RuleType,
    val pattern: String = "",
    val isRegex: Boolean = false,
    val domains: Set<String> = emptySet(),
    val excludedDomains: Set<String> = emptySet(),
    val contentTypes: Set<ContentType> = emptySet(),
    val excludedContentTypes: Set<ContentType> = emptySet(),
    val isThirdParty: Boolean? = null,
    val isImportant: Boolean = false,
    val isMatchCase: Boolean = false,
    val cssSelector: String = "",
    val jsCode: String = "",
    val priority: Int = 0
) {
    
    /**
     * Checks if this rule matches the given URL and context
     */
    fun matches(
        url: String,
        documentUrl: String = "",
        contentType: ContentType = ContentType.OTHER,
        isThirdParty: Boolean = false
    ): Boolean {
        when (ruleType) {
            RuleType.BLOCKING, RuleType.EXCEPTION -> {
                return matchesNetworkRule(url, documentUrl, contentType, isThirdParty)
            }
            RuleType.COSMETIC_HIDING, RuleType.COSMETIC_CSS -> {
                return matchesCosmeticRule(documentUrl)
            }
            RuleType.JAVASCRIPT -> {
                return matchesJavaScriptRule(documentUrl)
            }
            else -> return false
        }
    }
    
    private fun matchesNetworkRule(
        url: String,
        documentUrl: String,
        contentType: ContentType,
        isThirdParty: Boolean
    ): Boolean {
        // Check content type restrictions
        if (contentTypes.isNotEmpty() && contentType !in contentTypes) {
            return false
        }
        if (excludedContentTypes.isNotEmpty() && contentType in excludedContentTypes) {
            return false
        }
        
        // Check third-party restrictions
        this.isThirdParty?.let { requiredThirdParty ->
            if (requiredThirdParty != isThirdParty) {
                return false
            }
        }
        
        // Check domain restrictions
        if (domains.isNotEmpty() || excludedDomains.isNotEmpty()) {
            val domain = extractDomain(documentUrl)
            if (domains.isNotEmpty() && !domains.any { domain.endsWith(it) }) {
                return false
            }
            if (excludedDomains.isNotEmpty() && excludedDomains.any { domain.endsWith(it) }) {
                return false
            }
        }
        
        // Check URL pattern
        return matchesPattern(url)
    }
    
    private fun matchesCosmeticRule(documentUrl: String): Boolean {
        if (domains.isEmpty() && excludedDomains.isEmpty()) {
            return true // Generic cosmetic rule
        }
        
        val domain = extractDomain(documentUrl)
        if (excludedDomains.isNotEmpty() && excludedDomains.any { domain.endsWith(it) }) {
            return false
        }
        
        return domains.isEmpty() || domains.any { domain.endsWith(it) }
    }
    
    private fun matchesJavaScriptRule(documentUrl: String): Boolean {
        return matchesCosmeticRule(documentUrl)
    }
    
    private fun matchesPattern(url: String): Boolean {
        if (pattern.isEmpty()) return true
        
        return if (isRegex) {
            try {
                Regex(pattern).containsMatchIn(url)
            } catch (e: Exception) {
                false
            }
        } else {
            matchesBasicPattern(url, pattern)
        }
    }
    
    private fun matchesBasicPattern(url: String, pattern: String): Boolean {
        var urlToMatch = if (isMatchCase) url else url.lowercase()
        var patternToMatch = if (isMatchCase) pattern else pattern.lowercase()
        
        // Handle special characters
        when {
            patternToMatch.startsWith("||") -> {
                // Domain anchor
                patternToMatch = patternToMatch.substring(2)
                val domain = extractDomain(url)
                return domain.endsWith(patternToMatch.removeSuffix("^"))
            }
            patternToMatch.startsWith("|") -> {
                // Start anchor
                patternToMatch = patternToMatch.substring(1)
                return urlToMatch.startsWith(patternToMatch)
            }
            patternToMatch.endsWith("|") -> {
                // End anchor
                patternToMatch = patternToMatch.removeSuffix("|")
                return urlToMatch.endsWith(patternToMatch)
            }
            else -> {
                // Simple substring match with wildcards
                return matchesWithWildcards(urlToMatch, patternToMatch)
            }
        }
    }
    
    private fun matchesWithWildcards(text: String, pattern: String): Boolean {
        if (!pattern.contains("*")) {
            return text.contains(pattern)
        }
        
        val parts = pattern.split("*")
        var currentIndex = 0
        
        for (i in parts.indices) {
            val part = parts[i]
            if (part.isEmpty()) continue
            
            val foundIndex = text.indexOf(part, currentIndex)
            if (foundIndex == -1) return false
            
            if (i == 0 && foundIndex != 0) return false // First part must be at start
            
            currentIndex = foundIndex + part.length
        }
        
        return true
    }
    
    private fun extractDomain(url: String): String {
        return try {
            val cleanUrl = if (url.startsWith("//")) "http:$url" else url
            val uri = java.net.URL(cleanUrl)
            uri.host?.lowercase() ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}

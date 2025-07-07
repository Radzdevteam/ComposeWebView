package com.radzdev.adguard

import android.util.Log

/**
 * Parser for AdGuard filter rules
 */
class AdGuardParser {
    
    companion object {
        private const val TAG = "AdGuardParser"
        
        // Content type mappings
        private val CONTENT_TYPE_MAP = mapOf(
            "document" to ContentType.DOCUMENT,
            "subdocument" to ContentType.SUBDOCUMENT,
            "stylesheet" to ContentType.STYLESHEET,
            "script" to ContentType.SCRIPT,
            "image" to ContentType.IMAGE,
            "font" to ContentType.FONT,
            "object" to ContentType.OBJECT,
            "media" to ContentType.MEDIA,
            "xmlhttprequest" to ContentType.XMLHTTPREQUEST,
            "websocket" to ContentType.WEBSOCKET,
            "ping" to ContentType.PING,
            "other" to ContentType.OTHER
        )
    }
    
    /**
     * Parses a single filter rule line
     */
    fun parseRule(line: String): FilterRule? {
        val trimmedLine = line.trim()
        
        // Skip empty lines
        if (trimmedLine.isEmpty()) return null
        
        // Handle comments
        if (trimmedLine.startsWith("!")) {
            return FilterRule(
                originalRule = trimmedLine,
                ruleType = RuleType.COMMENT
            )
        }
        
        // Handle cosmetic rules
        when {
            trimmedLine.contains("##") -> return parseCosmeticHidingRule(trimmedLine)
            trimmedLine.contains("#$#") -> return parseCosmeticCSSRule(trimmedLine)
            trimmedLine.contains("#%#") -> return parseJavaScriptRule(trimmedLine)
        }
        
        // Handle network rules (blocking and exception)
        return parseNetworkRule(trimmedLine)
    }
    
    /**
     * Parses multiple filter rules from text
     */
    fun parseRules(text: String): List<FilterRule> {
        return text.lines()
            .mapNotNull { parseRule(it) }
            .filter { it.ruleType != RuleType.COMMENT && it.ruleType != RuleType.INVALID }
    }
    
    private fun parseNetworkRule(rule: String): FilterRule {
        try {
            val isException = rule.startsWith("@@")
            val ruleWithoutException = if (isException) rule.substring(2) else rule
            
            // Split pattern and modifiers
            val parts = ruleWithoutException.split("$", limit = 2)
            val pattern = parts[0]
            val modifiersString = if (parts.size > 1) parts[1] else ""
            
            // Parse modifiers
            val modifiers = parseModifiers(modifiersString)
            
            // Determine if pattern is regex
            val isRegex = pattern.startsWith("/") && pattern.endsWith("/")
            val cleanPattern = if (isRegex) pattern.substring(1, pattern.length - 1) else pattern
            
            return FilterRule(
                originalRule = rule,
                ruleType = if (isException) RuleType.EXCEPTION else RuleType.BLOCKING,
                pattern = cleanPattern,
                isRegex = isRegex,
                domains = modifiers.domains,
                excludedDomains = modifiers.excludedDomains,
                contentTypes = modifiers.contentTypes,
                excludedContentTypes = modifiers.excludedContentTypes,
                isThirdParty = modifiers.isThirdParty,
                isImportant = modifiers.isImportant,
                isMatchCase = modifiers.isMatchCase,
                priority = calculatePriority(modifiers, isException)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse network rule: $rule", e)
            return FilterRule(
                originalRule = rule,
                ruleType = RuleType.INVALID
            )
        }
    }
    
    private fun parseCosmeticHidingRule(rule: String): FilterRule {
        try {
            val parts = rule.split("##", limit = 2)
            if (parts.size != 2) {
                return FilterRule(originalRule = rule, ruleType = RuleType.INVALID)
            }
            
            val domainPart = parts[0]
            val selector = parts[1]
            
            val (domains, excludedDomains) = parseDomainList(domainPart)
            
            return FilterRule(
                originalRule = rule,
                ruleType = RuleType.COSMETIC_HIDING,
                domains = domains,
                excludedDomains = excludedDomains,
                cssSelector = selector
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cosmetic hiding rule: $rule", e)
            return FilterRule(originalRule = rule, ruleType = RuleType.INVALID)
        }
    }
    
    private fun parseCosmeticCSSRule(rule: String): FilterRule {
        try {
            val parts = rule.split("#\$#", limit = 2)
            if (parts.size != 2) {
                return FilterRule(originalRule = rule, ruleType = RuleType.INVALID)
            }
            
            val domainPart = parts[0]
            val cssCode = parts[1]
            
            val (domains, excludedDomains) = parseDomainList(domainPart)
            
            return FilterRule(
                originalRule = rule,
                ruleType = RuleType.COSMETIC_CSS,
                domains = domains,
                excludedDomains = excludedDomains,
                cssSelector = cssCode
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cosmetic CSS rule: $rule", e)
            return FilterRule(originalRule = rule, ruleType = RuleType.INVALID)
        }
    }
    
    private fun parseJavaScriptRule(rule: String): FilterRule {
        try {
            val parts = rule.split("#%#", limit = 2)
            if (parts.size != 2) {
                return FilterRule(originalRule = rule, ruleType = RuleType.INVALID)
            }
            
            val domainPart = parts[0]
            val jsCode = parts[1]
            
            val (domains, excludedDomains) = parseDomainList(domainPart)
            
            return FilterRule(
                originalRule = rule,
                ruleType = RuleType.JAVASCRIPT,
                domains = domains,
                excludedDomains = excludedDomains,
                jsCode = jsCode
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse JavaScript rule: $rule", e)
            return FilterRule(originalRule = rule, ruleType = RuleType.INVALID)
        }
    }
    
    private fun parseDomainList(domainPart: String): Pair<Set<String>, Set<String>> {
        if (domainPart.isEmpty()) {
            return Pair(emptySet(), emptySet())
        }
        
        val domains = mutableSetOf<String>()
        val excludedDomains = mutableSetOf<String>()
        
        domainPart.split(",").forEach { domain ->
            val trimmed = domain.trim()
            if (trimmed.startsWith("~")) {
                excludedDomains.add(trimmed.substring(1))
            } else {
                domains.add(trimmed)
            }
        }
        
        return Pair(domains, excludedDomains)
    }
    
    private data class ParsedModifiers(
        val domains: Set<String> = emptySet(),
        val excludedDomains: Set<String> = emptySet(),
        val contentTypes: Set<ContentType> = emptySet(),
        val excludedContentTypes: Set<ContentType> = emptySet(),
        val isThirdParty: Boolean? = null,
        val isImportant: Boolean = false,
        val isMatchCase: Boolean = false
    )
    
    private fun parseModifiers(modifiersString: String): ParsedModifiers {
        if (modifiersString.isEmpty()) {
            return ParsedModifiers()
        }
        
        val domains = mutableSetOf<String>()
        val excludedDomains = mutableSetOf<String>()
        val contentTypes = mutableSetOf<ContentType>()
        val excludedContentTypes = mutableSetOf<ContentType>()
        var isThirdParty: Boolean? = null
        var isImportant = false
        var isMatchCase = false
        
        modifiersString.split(",").forEach { modifier ->
            val trimmed = modifier.trim()
            
            when {
                trimmed.startsWith("domain=") -> {
                    val domainList = trimmed.substring(7)
                    val (d, ed) = parseDomainList(domainList)
                    domains.addAll(d)
                    excludedDomains.addAll(ed)
                }
                trimmed == "third-party" -> isThirdParty = true
                trimmed == "~third-party" -> isThirdParty = false
                trimmed == "important" -> isImportant = true
                trimmed == "match-case" -> isMatchCase = true
                trimmed.startsWith("~") -> {
                    val contentType = CONTENT_TYPE_MAP[trimmed.substring(1)]
                    contentType?.let { excludedContentTypes.add(it) }
                }
                else -> {
                    val contentType = CONTENT_TYPE_MAP[trimmed]
                    contentType?.let { contentTypes.add(it) }
                }
            }
        }
        
        return ParsedModifiers(
            domains = domains,
            excludedDomains = excludedDomains,
            contentTypes = contentTypes,
            excludedContentTypes = excludedContentTypes,
            isThirdParty = isThirdParty,
            isImportant = isImportant,
            isMatchCase = isMatchCase
        )
    }
    
    private fun calculatePriority(modifiers: ParsedModifiers, isException: Boolean): Int {
        var priority = 0
        
        // Base priority for exceptions
        if (isException) priority += 100000
        
        // Important rules get higher priority
        if (modifiers.isImportant) priority += 10000
        
        // Domain-specific rules get higher priority
        if (modifiers.domains.isNotEmpty()) priority += 1000
        
        // Content type specific rules get higher priority
        if (modifiers.contentTypes.isNotEmpty()) priority += 100
        
        // Third-party specific rules get higher priority
        if (modifiers.isThirdParty != null) priority += 10
        
        return priority
    }
}

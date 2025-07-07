// Quick test to debug AdGuard blocking logic
// This file can be used to manually test the AdGuard engine

fun testAdGuardBlocking() {
    // Test URLs that should be blocked
    val testUrls = listOf(
        "https://faqirsgoliard.top/ads.js",
        "https://nd.lunatazetas.top/popup.js",
        "https://goliard.com/tracker.js",
        "https://commoditysway.com/analytics.js",
        "https://workredbay.com/ads.html",
        "https://bereave.com/popup.html",
        "https://example.com/ads/banner.jpg",
        "https://doubleclick.net/ads.js",
        "https://googleadservices.com/pagead.js"
    )
    
    // Test document URLs
    val documentUrls = listOf(
        "https://example.com",
        "https://bigwarp.io",
        "https://mixdrop.co"
    )
    
    println("=== AdGuard Blocking Test ===")
    
    for (documentUrl in documentUrls) {
        println("\nTesting with document URL: $documentUrl")
        
        for (testUrl in testUrls) {
            // This would call the actual AdGuard engine
            // val shouldBlock = adGuardEngine.shouldBlock(testUrl, documentUrl, ContentType.SCRIPT)
            println("  URL: $testUrl")
            println("    Expected: BLOCKED")
            // println("    Actual: ${if (shouldBlock) "BLOCKED" else "ALLOWED"}")
        }
    }
}

// Common ad/tracking patterns that should be blocked
val suspiciousPatterns = listOf(
    "ad", "ads", "popup", "track", "analytics", 
    "goliard", "commoditysway", "workredbay", 
    "bereave", "lunatazetas", "faqirsgoliard"
)

// Check if a URL contains suspicious patterns
fun containsSuspiciousPattern(url: String): Boolean {
    return suspiciousPatterns.any { pattern ->
        url.contains(pattern, ignoreCase = true)
    }
}

// Manual rule testing
fun testFilterRules() {
    val testRules = listOf(
        "||doubleclick.net^",
        "||googleadservices.com^",
        "||googlesyndication.com^",
        "/ads/*",
        "/popup/*",
        "||faqirsgoliard.top^",
        "||nd.lunatazetas.top^"
    )
    
    println("\n=== Filter Rule Test ===")
    
    for (rule in testRules) {
        println("Rule: $rule")
        // This would test if the rule is parsed correctly
        // val parsedRule = parser.parseRule(rule)
        // println("  Parsed: ${parsedRule?.ruleType}")
        // println("  Pattern: ${parsedRule?.pattern}")
    }
}

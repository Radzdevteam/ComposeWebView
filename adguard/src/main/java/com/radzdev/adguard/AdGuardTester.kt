package com.radzdev.adguard

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

/**
 * Test utility for validating AdGuard implementation
 */
class AdGuardTester(private val context: Context) {
    
    companion object {
        private const val TAG = "AdGuardTester"
        
        // Test URLs for validation
        private val TEST_CASES = listOf(
            TestCase(
                name = "Google Ads",
                url = "https://googleads.g.doubleclick.net/pagead/ads",
                shouldBlock = true,
                contentType = ContentType.SCRIPT
            ),
            TestCase(
                name = "Facebook Tracking",
                url = "https://www.facebook.com/tr/",
                shouldBlock = true,
                contentType = ContentType.XMLHTTPREQUEST
            ),
            TestCase(
                name = "Google Analytics",
                url = "https://www.google-analytics.com/analytics.js",
                shouldBlock = true,
                contentType = ContentType.SCRIPT
            ),
            TestCase(
                name = "Amazon CDN (should not block)",
                url = "https://images-na.ssl-images-amazon.com/images/I/image.jpg",
                shouldBlock = false,
                contentType = ContentType.IMAGE
            ),
            TestCase(
                name = "YouTube Video (should not block)",
                url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                shouldBlock = false,
                contentType = ContentType.DOCUMENT
            ),
            TestCase(
                name = "AdNexus",
                url = "https://ib.adnxs.com/seg",
                shouldBlock = true,
                contentType = ContentType.XMLHTTPREQUEST
            ),
            TestCase(
                name = "Outbrain Widget",
                url = "https://widgets.outbrain.com/outbrain.js",
                shouldBlock = true,
                contentType = ContentType.SCRIPT
            ),
            TestCase(
                name = "Regular CSS (should not block)",
                url = "https://example.com/styles.css",
                shouldBlock = false,
                contentType = ContentType.STYLESHEET
            )
        )
    }
    
    data class TestCase(
        val name: String,
        val url: String,
        val shouldBlock: Boolean,
        val contentType: ContentType,
        val documentUrl: String = "https://example.com"
    )
    
    data class TestResult(
        val testCase: TestCase,
        val actualResult: Boolean,
        val passed: Boolean,
        val executionTime: Long
    )
    
    /**
     * Run comprehensive tests on the AdGuard engine
     */
    suspend fun runTests(): TestSummary = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting AdGuard tests...")
        
        val engine = AdGuardEngine(context)
        val results = mutableListOf<TestResult>()
        
        try {
            // Initialize engine
            val initStart = System.currentTimeMillis()
            engine.initialize()
            val initTime = System.currentTimeMillis() - initStart
            Log.i(TAG, "Engine initialization took ${initTime}ms")
            
            // Wait a bit for initialization to complete
            delay(2000)
            
            // Run test cases
            for (testCase in TEST_CASES) {
                val result = runSingleTest(engine, testCase)
                results.add(result)
                
                Log.d(TAG, "Test '${testCase.name}': ${if (result.passed) "PASS" else "FAIL"} " +
                        "(expected: ${testCase.shouldBlock}, actual: ${result.actualResult}, " +
                        "time: ${result.executionTime}ms)")
            }
            
            // Performance tests
            val perfResults = runPerformanceTests(engine)
            
            // Get engine statistics
            val stats = engine.getStats()
            Log.i(TAG, "Engine stats: $stats")
            
            TestSummary(
                results = results,
                performanceResults = perfResults,
                engineStats = stats,
                initializationTime = initTime
            )
            
        } finally {
            engine.destroy()
        }
    }
    
    private suspend fun runSingleTest(engine: AdGuardEngine, testCase: TestCase): TestResult {
        val startTime = System.currentTimeMillis()
        
        val actualResult = engine.shouldBlock(
            url = testCase.url,
            documentUrl = testCase.documentUrl,
            contentType = testCase.contentType
        )
        
        val executionTime = System.currentTimeMillis() - startTime
        val passed = actualResult == testCase.shouldBlock
        
        return TestResult(
            testCase = testCase,
            actualResult = actualResult,
            passed = passed,
            executionTime = executionTime
        )
    }
    
    private suspend fun runPerformanceTests(engine: AdGuardEngine): PerformanceResults {
        Log.i(TAG, "Running performance tests...")
        
        val testUrls = listOf(
            "https://googleads.g.doubleclick.net/pagead/ads",
            "https://www.google-analytics.com/analytics.js",
            "https://connect.facebook.net/en_US/fbevents.js",
            "https://example.com/normal-content.js",
            "https://cdn.jsdelivr.net/npm/bootstrap@5.1.3/dist/css/bootstrap.min.css"
        )
        
        val iterations = 1000
        val startTime = System.currentTimeMillis()
        
        // Test blocking performance
        repeat(iterations) { i ->
            val url = testUrls[i % testUrls.size]
            engine.shouldBlock(url, "https://example.com", ContentType.SCRIPT)
        }
        
        val totalTime = System.currentTimeMillis() - startTime
        val avgTime = totalTime.toDouble() / iterations
        
        Log.i(TAG, "Performance test: $iterations requests in ${totalTime}ms (avg: ${String.format("%.2f", avgTime)}ms per request)")
        
        return PerformanceResults(
            totalRequests = iterations,
            totalTime = totalTime,
            averageTime = avgTime,
            requestsPerSecond = (iterations * 1000.0 / totalTime).toInt()
        )
    }
    
    /**
     * Test cosmetic filtering
     */
    suspend fun testCosmeticFiltering(): CosmeticTestResult = withContext(Dispatchers.IO) {
        val engine = AdGuardEngine(context)
        
        try {
            engine.initialize()
            delay(1000) // Wait for initialization
            
            val testDomains = listOf(
                "https://example.com",
                "https://google.com",
                "https://facebook.com",
                "https://youtube.com"
            )
            
            val results = mutableMapOf<String, Int>()
            
            for (domain in testDomains) {
                val cosmeticRules = engine.getCosmeticRules(domain)
                val jsRules = engine.getJavaScriptRules(domain)
                results[domain] = cosmeticRules.size + jsRules.size
                
                Log.d(TAG, "Domain $domain: ${cosmeticRules.size} cosmetic rules, ${jsRules.size} JS rules")
            }
            
            CosmeticTestResult(
                domainResults = results,
                totalRules = results.values.sum()
            )
            
        } finally {
            engine.destroy()
        }
    }
    
    data class TestSummary(
        val results: List<TestResult>,
        val performanceResults: PerformanceResults,
        val engineStats: Map<String, Any>,
        val initializationTime: Long
    ) {
        val passedTests = results.count { it.passed }
        val totalTests = results.size
        val passRate = if (totalTests > 0) (passedTests * 100 / totalTests) else 0
        val averageExecutionTime = if (results.isNotEmpty()) {
            results.map { it.executionTime }.average()
        } else 0.0
    }
    
    data class PerformanceResults(
        val totalRequests: Int,
        val totalTime: Long,
        val averageTime: Double,
        val requestsPerSecond: Int
    )
    
    data class CosmeticTestResult(
        val domainResults: Map<String, Int>,
        val totalRules: Int
    )
    
    /**
     * Generate a test report
     */
    fun generateReport(summary: TestSummary): String {
        val report = StringBuilder()
        
        report.appendLine("=== AdGuard Engine Test Report ===")
        report.appendLine()
        report.appendLine("Initialization Time: ${summary.initializationTime}ms")
        report.appendLine("Test Results: ${summary.passedTests}/${summary.totalTests} passed (${summary.passRate}%)")
        report.appendLine("Average Execution Time: ${String.format("%.2f", summary.averageExecutionTime)}ms")
        report.appendLine()
        
        report.appendLine("Performance Results:")
        report.appendLine("  Total Requests: ${summary.performanceResults.totalRequests}")
        report.appendLine("  Total Time: ${summary.performanceResults.totalTime}ms")
        report.appendLine("  Average Time: ${String.format("%.2f", summary.performanceResults.averageTime)}ms")
        report.appendLine("  Requests/Second: ${summary.performanceResults.requestsPerSecond}")
        report.appendLine()
        
        report.appendLine("Engine Statistics:")
        summary.engineStats.forEach { (key, value) ->
            report.appendLine("  $key: $value")
        }
        report.appendLine()
        
        report.appendLine("Individual Test Results:")
        summary.results.forEach { result ->
            val status = if (result.passed) "PASS" else "FAIL"
            report.appendLine("  [${status}] ${result.testCase.name}: " +
                    "expected ${result.testCase.shouldBlock}, got ${result.actualResult} " +
                    "(${result.executionTime}ms)")
        }
        
        return report.toString()
    }
}

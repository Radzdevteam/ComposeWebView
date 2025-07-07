# Keep WebView classes
-keep class com.radzdev.webview.** { *; }
-keep class com.radzdev.adguard.** { *; }

# Keep WebView JavaScript interface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep WebView related classes
-keep class android.webkit.** { *; }
-keep class androidx.webkit.** { *; }

# Keep Compose WebView components
-keep class androidx.compose.ui.viewinterop.** { *; }
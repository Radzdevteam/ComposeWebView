package com.radzdev.composewebview

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.radzdev.webview.ComposeWebViewActivity

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Launch the new Compose WebView instead of radzadblocker
        val intent = Intent(this, ComposeWebViewActivity::class.java)
        intent.putExtra("url", "https://bigwarp.io/embed-dehf07n6v4eo.html")
       // intent.putExtra("url", "https://vide0.net/e/xlerkprs9quw")
        startActivity(intent)
        finish()
    }
}

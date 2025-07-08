# 📱 ComposeWebView

A lightweight and customizable **Android WebView** built with Jetpack Compose — featuring popup/ad blocking, fullscreen video handling, and easy integration.

[![](https://jitpack.io/v/Radzdevteam/ComposeWebView.svg)](https://jitpack.io/#Radzdevteam/ComposeWebView)

---

## 🔧 Installation

Add **JitPack** to your root `settings.gradle` (or `settings.gradle.kts`):

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Then add the library to your **module-level** `build.gradle` or `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.Radzdevteam:ComposeWebView:Tag")
}
```

> 🔁 Replace `Tag` with the latest release version: [View on JitPack](https://jitpack.io/#Radzdevteam/ComposeWebView)

---

## 🚀 Usage Example

To launch the WebView from your `MainActivity`:

```kotlin
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Launch ComposeWebViewActivity with a URL
        val intent = Intent(this, ComposeWebViewActivity::class.java)
        intent.putExtra("url", "https://bigwarp.io/embed-dehf07n6v4eo.html")
        // Optional alternative URL:
        // intent.putExtra("url", "https://vide0.net/e/xlerkprs9quw")
        startActivity(intent)
        finish()
    }
}
```

---

## ✨ Features

- ✅ Jetpack Compose-based WebView UI
- 🔒 Ad & popup blocking
- 🎥 Fullscreen video support
- 🧠 Smart handling of `intent:` links
- ⚡ Fast, clean, and lightweight

---

## 📜 License

Licensed under the MIT License.  
See [`LICENSE`](LICENSE) for more details.

---

## 🙌 Contributions

Pull requests are welcome!  
For major changes, please open an issue first to discuss what you'd like to improve or add.

---

## 🔗 Author

Made with ❤️ by [Radzdevteam](https://github.com/Radzdevteam)  
📧 Contact: `radz.assistance@gmail.com`

````markdown
# 📱 ComposeWebView

A lightweight and customizable **Android WebView** built with Jetpack Compose — with advanced features like popup/ad blocking, fullscreen video handling, and simple integration.

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
````

Then add the library in your **module-level** `build.gradle` or `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.Radzdevteam:ComposeWebView:Tag")
}
```

> 🔁 Replace `Tag` with the latest version: [JitPack Releases](https://jitpack.io/#Radzdevteam/ComposeWebView)

---

## 🚀 Usage Example

To launch the WebView directly from your `MainActivity`:

```kotlin
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Launch ComposeWebViewActivity with a URL
        val intent = Intent(this, ComposeWebViewActivity::class.java)
        intent.putExtra("url", "https://bigwarp.io/embed-dehf07n6v4eo.html")
        // intent.putExtra("url", "https://vide0.net/e/xlerkprs9quw")
        startActivity(intent)
        finish()
    }
}
```

---

## ✨ Features

* ✅ Jetpack Compose-based WebView UI
* 🔒 Ad & popup blocking (optional integration)
* 🎥 Fullscreen video support
* 🧠 Smart handling of `intent:` links
* ⚡ Fast and lightweight

---

## 📜 License

This project is licensed under the MIT License.
See [`LICENSE`](LICENSE) for details.

---

## 🙌 Contributions

Pull requests are welcome! For major changes, please open an issue first to discuss what you would like to change or add.

---

## 🔗 Author

Made with ❤️ by [Radzdevteam](https://github.com/Radzdevteam)
Contact: `radz.assistance@gmail.com`

```

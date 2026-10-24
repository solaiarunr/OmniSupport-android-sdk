# OmniSupport Native Android Chat SDK

Reusable AI-powered customer support chat SDK for Native Android (Kotlin & Java) applications. Distributed via Gradle/Maven.

## Requirements
- Android SDK 23+ (Android 6.0+)
- Kotlin 1.9+
- Gradle 8+

## Installation via Gradle

In your app-level `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.omnisupport:chat-sdk:1.0.0")
    // or include project(":packages:android-sdk")
}
```

## Quick Start

### 1. Initialize in `Application` class or `MainActivity`
```kotlin
import io.omnisupport.sdk.OmniChatSDK

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize OmniSupport SDK with your public app key and backend URL
        OmniChatSDK.initialize(
            context = this,
            appKey = "widget_hitasoft_001",
            baseUrl = "http://192.168.29.11:3000"
        )
    }
}
```

### 2. Launch Chat Interface
```kotlin
import io.omnisupport.sdk.OmniChatSDK

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        findViewById<Button>(R.id.btn_support).setOnClickListener {
            OmniChatSDK.openChat(this)
        }
    }
}
```

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- 上架用嘅正式簽名 ---------------------------------------------------------
//
// keystore 同密碼一律唔入 repo（見 .gitignore）：
//   ~/.android/tt-release.keystore
//   ~/.android/tt-release.properties   →  storePassword / keyAlias / keyPassword
//
// **要行 `-Ptt.upload` 先會用呢條 key**（例：`./gradlew bundleRelease -Ptt.upload`）。
// 唔加就照用返 debug key —— dl／GitHub 度派嘅 `tt-v<N>.apk` 一路都係嗰條，
// 靜靜雞換咗 key 啲人就要 uninstall 咗先裝到新版（見 AGENTS.md）。
val useUploadKey = providers.gradleProperty("tt.upload").isPresent
val uploadStore = File(System.getProperty("user.home"), ".android/tt-release.keystore")
val uploadProps = File(System.getProperty("user.home"), ".android/tt-release.properties")
val uploadCreds = Properties().apply {
    if (useUploadKey) {
        require(uploadStore.exists()) { "-Ptt.upload 但係搵唔到 ${uploadStore.path}" }
        require(uploadProps.exists()) { "-Ptt.upload 但係搵唔到 ${uploadProps.path}" }
        uploadProps.inputStream().use { load(it) }
    }
}

android {
    namespace = "tt.ime.riverine"
    compileSdk = 36

    defaultConfig {
        applicationId = "tt.ime.riverine"
        minSdk = 26
        targetSdk = 36
        versionCode = 45
        versionName = "2.0.2"
    }

    signingConfigs {
        // side-load（dl／GitHub release 嗰啲 tt-v<N>.apk）一路都係簽返同 debug
        // 一樣嘅 key，換咗 key 啲人就要 uninstall 先裝到新版，所以呢條唔郁。
        //
        // 注意 2.0.0：applicationId 由舊嗰個改咗做 `tt.ime.riverine`，Android 當佢係
        // 另一個 app —— 就算 key 冇變，舊裝機一樣係 update 唔到，要自己再裝多次
        // （新舊可以並存）。Play Store 嗰邊亦都要開一個全新 listing。
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // 上架 Google Play 嗰條（-Ptt.upload 先會砌出嚟）
        if (useUploadKey) create("upload") {
            storeFile = uploadStore
            storePassword = uploadCreds.getProperty("storePassword")
            keyAlias = uploadCreds.getProperty("keyAlias")
            keyPassword = uploadCreds.getProperty("keyPassword")
                ?: uploadCreds.getProperty("storePassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (useUploadKey) "upload" else "release")
        }
    }

    buildFeatures {
        viewBinding = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        noCompress += listOf("db", "png")
    }

    packaging {
        resources.excludes += "META-INF/*"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
}

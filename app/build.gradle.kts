import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- 上架用嘅正式簽名 ---------------------------------------------------------
//
// keystore 同密碼一律唔入 repo（見 .gitignore）：
//   ~/.android/tq9-release.keystore
//   ~/.android/tq9-release.properties   →  storePassword / keyAlias / keyPassword
//
// **要行 `-Ptq9.upload` 先會用呢條 key**（例：`./gradlew bundleRelease -Ptq9.upload`）。
// 唔加就照用返 debug key —— dl／GitHub 度派嘅 `tq9-v<N>.apk` 一路都係嗰條，
// 靜靜雞換咗 key 啲人就要 uninstall 咗先裝到新版（見 AGENTS.md）。
val useUploadKey = providers.gradleProperty("tq9.upload").isPresent
val uploadStore = File(System.getProperty("user.home"), ".android/tq9-release.keystore")
val uploadProps = File(System.getProperty("user.home"), ".android/tq9-release.properties")
val uploadCreds = Properties().apply {
    if (useUploadKey) {
        require(uploadStore.exists()) { "-Ptq9.upload 但係搵唔到 ${uploadStore.path}" }
        require(uploadProps.exists()) { "-Ptq9.upload 但係搵唔到 ${uploadProps.path}" }
        uploadProps.inputStream().use { load(it) }
    }
}

android {
    namespace = "hk.tq9"
    compileSdk = 36

    defaultConfig {
        applicationId = "hk.tq9"
        minSdk = 26
        targetSdk = 36
        versionCode = 24
        versionName = "1.1.7"
    }

    signingConfigs {
        // side-load（dl／GitHub release 嗰啲 tq9-v<N>.apk）一路都係簽返同 debug 一樣嘅 key，
        // 換咗 key 啲人就要 uninstall 先裝到新版，所以呢條唔郁。
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // 上架 Google Play 嗰條（-Ptq9.upload 先會砌出嚟）
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

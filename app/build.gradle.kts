plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "hk.tq9"
    compileSdk = 36

    defaultConfig {
        applicationId = "hk.tq9"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "1.0.6"
    }

    signingConfigs {
        // release 要簽返同 debug 一樣嘅 key：dl 度嗰個 tq9-v1.apk 就係用呢個簽，
        // 換咗 key 啲人就要 uninstall 先裝到新版。
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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

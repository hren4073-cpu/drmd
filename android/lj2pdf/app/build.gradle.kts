plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.drmd.lj2pdf"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.drmd.lj2pdf"
        minSdk = 21
        // 33 (not 34) so the foreground service needs no Android-14 FGS type.
        targetSdk = 33
        versionCode = 5
        versionName = "3.1"
    }

    // A committed, fixed debug keystore so EVERY build (CI or local) is signed
    // with the SAME key. Android only lets an update install over an existing
    // app when signatures match, so this is what makes future updates install
    // over the top without uninstalling (and thus without losing data).
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.7.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // Pure-JVM port of Apache PDFBox for Android: merge + outline/bookmarks.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // HTML parser for the multithreaded HTTP download + content extraction.
    implementation("org.jsoup:jsoup:1.17.2")
    // Pooled HTTP/2 client — connection reuse + multiplexing for fast downloads.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

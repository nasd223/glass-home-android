import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Your weather location, kept out of git: copy glasshome.properties.example
// to glasshome.properties and fill it in.
val local = Properties().apply {
    rootProject.file("glasshome.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun localString(key: String, default: String) = "\"" + (local.getProperty(key) ?: default).replace("\"", "") + "\""
fun localDouble(key: String) = (local.getProperty(key)?.toDoubleOrNull() ?: 0.0).toString()

// The release signing key, kept out of git (see README, "Signing"). Without it,
// release builds are signed with your own debug key.
val keystore = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.glasshome.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.glasshome.tv"
        // Chromecast with Google TV runs Android TV 12 or later.
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.1.3"

        buildConfigField("String", "WEATHER_PLACE", localString("weather.place", ""))
        buildConfigField("double", "WEATHER_LATITUDE", localDouble("weather.latitude"))
        buildConfigField("double", "WEATHER_LONGITUDE", localDouble("weather.longitude"))
        buildConfigField("String", "WEATHER_TIMEZONE", localString("weather.timezone", "auto"))
        buildConfigField("boolean", "WEATHER_FAHRENHEIT", (local.getProperty("weather.fahrenheit") == "true").toString())
    }

    signingConfigs {
        if (keystore.getProperty("storeFile") != null) create("release") {
            storeFile = file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // Website icons and YouTube details for shortcuts: its Dns hook lets every
    // connection be limited to public addresses.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

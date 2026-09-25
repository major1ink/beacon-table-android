plugins {
    id("com.android.application")
}

// В CI версия приходит из тега: ./gradlew assembleRelease -PappVersion=0.1.0
val appVersion = (findProperty("appVersion") as String?) ?: "0.0.0-dev"
val appVersionCode = appVersion.substringBefore('-').split('.')
    .map { it.toIntOrNull() ?: 0 }
    .let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }
    .coerceAtLeast(1)

val keystore = System.getenv("ANDROID_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "ru.beacontable"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.beacontable.BeaconTable"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.webkit:webkit:1.14.0")
}

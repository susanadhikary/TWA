plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "np.com.narayanipauroti.kds"
    compileSdk = 35

    defaultConfig {
        applicationId = "np.com.narayanipauroti.kds"
        minSdk = 21
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"

        // URL of the PWA to load. Change here to point the app elsewhere.
        buildConfigField("String", "START_URL", "\"https://pos.narayanipauroti.com.np/kds\"")
    }

    // Optional release signing: provided via environment variables (e.g. GitHub Actions secrets).
    val keystorePath = System.getenv("KDS_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KDS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KDS_KEY_ALIAS")
                keyPassword = System.getenv("KDS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

base {
    archivesName.set("TapTill-KDS")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.12.1")
}

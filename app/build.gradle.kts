import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// App configuration lives in /kds.properties (URL, name, version).
// Any value can be overridden for a single build with a Gradle property,
// e.g.  ./gradlew assembleRelease -PKDS_URL=https://example.com/kds
// ---------------------------------------------------------------------------
val kdsConfig = Properties().apply {
    rootProject.file("kds.properties").reader(Charsets.UTF_8).use { load(it) }
}

fun kdsOptionalSetting(key: String): String? =
    ((findProperty(key) as String?) ?: kdsConfig.getProperty(key))?.trim()?.takeIf { it.isNotEmpty() }

fun kdsSetting(key: String): String =
    kdsOptionalSetting(key) ?: error("kds.properties: $key is missing or empty")

fun originOf(uri: URI): String =
    "${uri.scheme.lowercase()}://${uri.host.lowercase()}" + if (uri.port != -1) ":${uri.port}" else ""

val kdsUrl = kdsSetting("KDS_URL").also { url ->
    val uri = runCatching { URI(url) }.getOrNull()
    require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank()) {
        "kds.properties: KDS_URL must be a full https:// address (got \"$url\"). " +
            "Plain http:// is blocked by the app's network security config."
    }
}
// Escaped for Android string resources (apostrophes/quotes would otherwise break the build).
// Scope: pages under this prefix count as "the app" (native features enabled).
// Defaults to the whole site of KDS_URL, like a PWA manifest without "scope".
val kdsScope = (kdsOptionalSetting("KDS_SCOPE") ?: (originOf(URI(kdsUrl)) + "/")).also { scope ->
    val uri = runCatching { URI(scope) }.getOrNull()
    require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank()) {
        "kds.properties: KDS_SCOPE must be a full https:// address (got \"$scope\")"
    }
    val start = URI(kdsUrl)
    val startPath = start.path.orEmpty().ifEmpty { "/" }
    val scopePath = uri.path.orEmpty().ifEmpty { "/" }
    require(originOf(start) == originOf(uri) && startPath.startsWith(scopePath)) {
        "kds.properties: KDS_URL ($kdsUrl) must be inside KDS_SCOPE ($scope) - " +
            "same https host, and the URL path must start with the scope path"
    }
}
val kdsAppName = kdsSetting("APP_NAME")
    .replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
val kdsVersionCode = kdsSetting("VERSION_CODE").toIntOrNull()?.takeIf { it > 0 }
    ?: error("kds.properties: VERSION_CODE must be a positive whole number")
val kdsVersionName = kdsSetting("VERSION_NAME")

android {
    namespace = "np.com.narayanipauroti.kds"
    compileSdk = 35

    defaultConfig {
        // Do NOT change: TVs treat a different applicationId as a separate app,
        // and updates only install over the same applicationId + signing key.
        applicationId = "np.com.narayanipauroti.kds"
        minSdk = 21
        targetSdk = 35
        versionCode = kdsVersionCode
        versionName = kdsVersionName

        // Values from kds.properties.
        buildConfigField("String", "START_URL", "\"$kdsUrl\"")
        buildConfigField("String", "SCOPE", "\"$kdsScope\"")
        resValue("string", "app_name", kdsAppName)
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

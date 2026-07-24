import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.gms.google.services)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

/** Set OPENAI_API_KEY=sk-… in local.properties (file is gitignored). Escaped for Java string literal. */
/** Facebook Login: add FACEBOOK_APP_ID and FACEBOOK_CLIENT_TOKEN (Meta App → Settings → Advanced) + Android platform + key hash. */
/** Remote pairing: set REMOTE_BACKEND_BASE_URL=https://your-vercel-app.vercel.app (public URL only). */
/**
 * WebRTC ICE (Phase 5): optional local.properties
 *   REMOTE_STUN_URLS=stun:stun.l.google.com:19302 (comma-separated)
 *   REMOTE_TURN_URL= / REMOTE_TURN_USERNAME= / REMOTE_TURN_CREDENTIAL=
 */
fun String.escapeForBuildConfigField(): String =
    this.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.autoreplybot"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.autoreplybot"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val openAiKey = (localProperties.getProperty("OPENAI_API_KEY") ?: "").trim().escapeForBuildConfigField()
        buildConfigField("String", "OPENAI_API_KEY", "\"$openAiKey\"")

        val fbAppId = (localProperties.getProperty("FACEBOOK_APP_ID") ?: "").trim().escapeForBuildConfigField()
        val fbClientToken = (localProperties.getProperty("FACEBOOK_CLIENT_TOKEN") ?: "").trim().escapeForBuildConfigField()
        buildConfigField("String", "FACEBOOK_APP_ID", "\"$fbAppId\"")
        buildConfigField("String", "FACEBOOK_CLIENT_TOKEN", "\"$fbClientToken\"")

        val fbRawId = (localProperties.getProperty("FACEBOOK_APP_ID") ?: "").trim()
        manifestPlaceholders["facebookAppId"] = fbRawId
        manifestPlaceholders["facebookClientToken"] = localProperties.getProperty("FACEBOOK_CLIENT_TOKEN") ?: ""
        /** Browser login returns via intent scheme fb + App ID — must match Meta dashboard Android setup */
        manifestPlaceholders["fbLoginScheme"] =
            if (fbRawId.isNotEmpty()) "fb$fbRawId" else "fb0"

        val remoteBackendBaseUrl =
            (localProperties.getProperty("REMOTE_BACKEND_BASE_URL") ?: "").trim()
                .trimEnd('/')
                .escapeForBuildConfigField()
        buildConfigField("String", "REMOTE_BACKEND_BASE_URL", "\"$remoteBackendBaseUrl\"")

        val remoteStunUrls =
            (localProperties.getProperty("REMOTE_STUN_URLS") ?: "stun:stun.l.google.com:19302")
                .trim()
                .ifEmpty { "stun:stun.l.google.com:19302" }
                .escapeForBuildConfigField()
        buildConfigField("String", "REMOTE_STUN_URLS", "\"$remoteStunUrls\"")
        buildConfigField(
            "String",
            "REMOTE_TURN_URL",
            "\"${(localProperties.getProperty("REMOTE_TURN_URL") ?: "").trim().escapeForBuildConfigField()}\""
        )
        buildConfigField(
            "String",
            "REMOTE_TURN_USERNAME",
            "\"${(localProperties.getProperty("REMOTE_TURN_USERNAME") ?: "").trim().escapeForBuildConfigField()}\""
        )
        buildConfigField(
            "String",
            "REMOTE_TURN_CREDENTIAL",
            "\"${(localProperties.getProperty("REMOTE_TURN_CREDENTIAL") ?: "").trim().escapeForBuildConfigField()}\""
        )
    }

    buildFeatures {
        buildConfig = true
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.auth)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.messaging)
    implementation(libs.okhttp)
    implementation(libs.work.runtime)
    implementation(libs.security.crypto)
    implementation(libs.facebookLogin)
    implementation(libs.recyclerview)
    implementation(libs.browser)
    implementation(libs.ffmpeg.kit.full.gpl)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.camera.video)
    // Phase 5 WebRTC: io.getstream:stream-webrtc-android (maintained Google WebRTC AAR for AGP 8.x).
    // Fallback if unavailable: org.webrtc:google-webrtc from a known working mirror — prefer Stream.
    implementation(libs.stream.webrtc.android)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
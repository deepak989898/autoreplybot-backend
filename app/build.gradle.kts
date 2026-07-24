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
    implementation(libs.okhttp)
    implementation(libs.work.runtime)
    implementation(libs.security.crypto)
    implementation(libs.facebookLogin)
    implementation(libs.recyclerview)
    implementation(libs.browser)
    implementation(libs.ffmpeg.kit.full.gpl)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
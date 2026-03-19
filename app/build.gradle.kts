plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

fun loadDotEnv(): Map<String, String> {
    val envFile = rootProject.file(".env")
    if (!envFile.exists()) return emptyMap()

    return buildMap {
        envFile.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach

            val separatorIndex = trimmed.indexOf('=')
            if (separatorIndex <= 0) return@forEach

            val key = trimmed.substring(0, separatorIndex).trim()
            val rawValue = trimmed.substring(separatorIndex + 1).trim()
            val value = rawValue.removeSurrounding("\"").removeSurrounding("'")
            put(key, value)
        }
    }
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val dotEnv = loadDotEnv()

fun configuredString(name: String, default: String = ""): String =
    providers.gradleProperty(name)
        .orElse(providers.environmentVariable(name))
        .orElse(dotEnv[name] ?: default)
        .get()

fun configuredInt(name: String, default: Int): Int =
    configuredString(name, default.toString()).toIntOrNull() ?: default

val modelProvider = configuredString("PHONECLAW_MODEL_PROVIDER", "stub")
val modelBaseUrl = configuredString("PHONECLAW_MODEL_BASE_URL", "")
val modelApiStyle = configuredString("PHONECLAW_MODEL_API_STYLE", "phoneclaw-gateway")
val modelApiKey = configuredString("PHONECLAW_MODEL_API_KEY", "")
val modelId = configuredString("PHONECLAW_MODEL_ID", "")
val modelConnectTimeoutSeconds = configuredInt("PHONECLAW_MODEL_CONNECT_TIMEOUT_SECONDS", 15)
val modelReadTimeoutSeconds = configuredInt("PHONECLAW_MODEL_READ_TIMEOUT_SECONDS", 45)

android {
    namespace = "com.phoneclaw.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.phoneclaw.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "PHONECLAW_MODEL_PROVIDER", modelProvider.asBuildConfigString())
        buildConfigField("String", "PHONECLAW_MODEL_BASE_URL", modelBaseUrl.asBuildConfigString())
        buildConfigField("String", "PHONECLAW_MODEL_API_STYLE", modelApiStyle.asBuildConfigString())
        buildConfigField("String", "PHONECLAW_MODEL_API_KEY", modelApiKey.asBuildConfigString())
        buildConfigField("String", "PHONECLAW_MODEL_ID", modelId.asBuildConfigString())
        buildConfigField("int", "PHONECLAW_MODEL_CONNECT_TIMEOUT_SECONDS", modelConnectTimeoutSeconds.toString())
        buildConfigField("int", "PHONECLAW_MODEL_READ_TIMEOUT_SECONDS", modelReadTimeoutSeconds.toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)

    testImplementation(libs.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}



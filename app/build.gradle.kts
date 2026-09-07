import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

val configuredApiUrl = providers.gradleProperty("usageApiUrl").orNull
    ?: localProperties.getProperty("USAGE_API_URL", "")
val configuredDemoMode = providers.gradleProperty("demoMode").orNull?.toBoolean()
    ?: localProperties.getProperty("DEMO_MODE", "false").toBoolean()

fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.carlren.aiusagemonitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.carlren.aiusagemonitor"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.1"
        buildConfigField("String", "USAGE_API_URL", configuredApiUrl.asBuildConfigString())
        buildConfigField("boolean", "DEMO_MODE", configuredDemoMode.toString())
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
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
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

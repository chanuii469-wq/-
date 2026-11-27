import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.adshield.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.adshield.android"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"
    }

    // 서명 키: android/keystore.properties 파일 또는 환경변수(CI)에서 읽음. 없으면 디버그 키로 서명
    val signing = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
        System.getenv("ADSHIELD_STORE_FILE")?.let { put("storeFile", it) }
        System.getenv("ADSHIELD_STORE_PASSWORD")?.let { put("storePassword", it) }
        System.getenv("ADSHIELD_KEY_ALIAS")?.let { put("keyAlias", it) }
        System.getenv("ADSHIELD_KEY_PASSWORD")?.let { put("keyPassword", it) }
    }
    val hasReleaseKey = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !signing.getProperty(it).isNullOrBlank() }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
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
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
}

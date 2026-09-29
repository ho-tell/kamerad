plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "at.tellioglu.kamerad"
    compileSdk = 34

    defaultConfig {
        applicationId = "at.tellioglu.kamerad"
        minSdk = 23
        targetSdk = 34
        versionCode = 16
        versionName = "1.2.11"
    }

    // Release key lives outside the repo; configure kamerad.* in ~/.gradle/gradle.properties
    val releaseStoreFile = providers.gradleProperty("kamerad.storeFile").orNull
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = providers.gradleProperty("kamerad.storePassword").get()
                keyAlias = providers.gradleProperty("kamerad.keyAlias").get()
                keyPassword = providers.gradleProperty("kamerad.keyPassword").get()
            }
        }
    }

    buildTypes {
        debug {
            // Same key as release, so debug and published builds can replace each other on the Karoo
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.hammerhead.karoo.ext)
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.androidx.lifeycle)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.compose.ui)
    implementation(libs.androidx.glance.appwidget)
}

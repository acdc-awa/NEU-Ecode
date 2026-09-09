plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.neboer.ecode.webviewdemo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.neboer.ecode.webviewdemo"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0-demo"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        // CI 跑裸 assembleRelease 会构建本模块,别让 lintVital 挡路
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.okhttp)
}

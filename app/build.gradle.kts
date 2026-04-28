plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.neboer.ecode"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.neboer.ecode"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.activity.ktx)
    implementation(libs.constraintlayout)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    implementation(libs.jsoup)
    implementation(libs.security.crypto)
    implementation(libs.coroutines.android)
    implementation(libs.material)
}

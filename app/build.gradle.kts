import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 版本号与 git tag 同步:
// - CI 打 tag(推送 v1.2.3)触发时:直接读 GITHUB_REF_NAME,versionName = "1.2.3"
// - 本地构建:git describe --tags,tag 之后的提交会得到 "1.2.3-3-gabc1234" 这类名字
// - 仓库还没有任何 v* tag 时:退回 "1.0.0"
// versionCode 由版本号前两三段数字算出(major*10000 + minor*100 + patch),随版本单调递增
val tagName: String = System.getenv("GITHUB_REF_NAME")
    ?.takeIf { System.getenv("GITHUB_REF_TYPE") == "tag" && Regex("""^v?\d""").containsMatchIn(it) }
    ?.removePrefix("v")
    ?: runCatching {
        val proc = ProcessBuilder("git", "describe", "--tags", "--match", "v*")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        proc.inputStream.bufferedReader().readText().trim().takeIf { proc.waitFor() == 0 }
    }.getOrNull()?.removePrefix("v")
    ?: "1.0.0"

val semver = Regex("""^(\d+)(?:\.(\d+))?(?:\.(\d+))?""").find(tagName)?.groupValues
val versionCodeNum: Int = semver
    ?.let { it[1].toInt() * 10000 + it[2].ifEmpty { "0" }.toInt() * 100 + it[3].ifEmpty { "0" }.toInt() }
    ?: 10000

// 签名信息全部来自环境变量(CI 里由 GitHub Secrets 注入);本地没有这些变量时,
// assembleRelease 产出未签名 APK,不影响 assembleDebug 日常开发
val signingStoreFilePath: String? = System.getenv("SIGNING_STORE_FILE")
    ?.takeIf { it.isNotBlank() && File(it).exists() }

android {
    namespace = "com.neboer.ecode"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.neboer.ecode"
        minSdk = 24
        targetSdk = 34
        versionCode = versionCodeNum
        versionName = tagName
    }

    signingConfigs {
        if (signingStoreFilePath != null) {
            create("release") {
                storeFile = File(signingStoreFilePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingStoreFilePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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

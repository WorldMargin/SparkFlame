import java.util.Properties

plugins {
    alias(libs.plugins.agp.app)
}

val gitCommitCount = 1

// 签名信息读取自根目录 release-signing.properties（不入库），缺失时不签名
val releaseSigning =
    rootProject.file("release-signing.properties").takeIf { it.exists() }?.let { props ->
        Properties().apply { props.inputStream().use { load(it) } }
    }

android {
    namespace = "com.worldmargin.autospark"
    buildToolsVersion = "37.0.0"
    compileSdk = 37

    if (releaseSigning != null) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.worldmargin.autospark"
        minSdk = 26
        targetSdk = 36
        // 固定版本号：历史压缩后提交数会变小，不能再拿它当 versionCode（会触发降级安装失败）
        versionCode = 10500
        versionName = "v1.4.0"
    }

    // 按 ABI 分包：arm64-v8a / armeabi-v7a 各出一个 APK
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            signingConfig = releaseSigning?.let { signingConfigs["release"] }
        }
        debug {
            // 调试包与发布包同一签名（覆盖安装不丢登录态，便于真机联调）
            signingConfig = releaseSigning?.let { signingConfigs["release"] }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.rhino)
    implementation(platform(libs.sora.editor.bom))
    implementation(libs.sora.editor)
    implementation(libs.webkit)
}

/**
 * 使用文档：把仓库根目录的 Markdown 同步进 assets（App 内的「使用文档」直接读这份），
 * 保证单一来源 —— 改文档不用改两处。
 *
 * 同一份文档还会写一份到仓库的 `docs/manual.md`：网页上能直接看，也方便把链接丢给 AI。
 */
val manualSource = layout.projectDirectory.file("../../使用文档.md")
val syncManual =
    tasks.register<Copy>("syncManual") {
        onlyIf { manualSource.asFile.exists() }
        from(manualSource)
        into(layout.projectDirectory.dir("src/main/assets/docs"))
        rename { "manual.md" }
    }

/** `docs/manual.md`：对外的那一份（assets 里那份只给 App 用，网页上不好找）。 */
val syncManualToRepo =
    tasks.register<Copy>("syncManualToRepo") {
        onlyIf { manualSource.asFile.exists() }
        from(manualSource)
        into(rootProject.layout.projectDirectory.dir("docs"))
        rename { "manual.md" }
    }

tasks.named("preBuild") { dependsOn(syncManual, syncManualToRepo) }

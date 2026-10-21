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
        applicationId = "io.github.worldmargin.sparkflame"
        minSdk = 26
        targetSdk = 36
        // 固定版本号：历史压缩后提交数会变小，不能再拿它当 versionCode（会触发降级安装失败）
        versionCode = 11105
        versionName = "v1.5.6"
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
    implementation(libs.webkit)
}

/**
 * 使用文档：仓库里只保留一份 `docs/manual.md`（网页上能直接看，也方便丢给 AI）。
 *
 * 改动流程：写仓库根目录的 `使用文档.md` → 构建时同步到 `docs/manual.md` → 再复制进
 * `assets/docs/manual.md` 给 App 读。assets 里那份是构建产物，不入库。
 */
val manualSource = layout.projectDirectory.file("../../使用文档.md")

/** `docs/manual.md`：仓库里唯一入库的那一份。 */
val syncManualToRepo =
    tasks.register<Copy>("syncManualToRepo") {
        onlyIf { manualSource.asFile.exists() }
        from(manualSource)
        into(rootProject.layout.projectDirectory.dir("docs"))
        rename { "manual.md" }
    }

/** `assets/docs/manual.md`：App 内使用文档，构建时从 `docs/manual.md` 复制，不入库。 */
val syncManual =
    tasks.register<Copy>("syncManual") {
        val repoSingle = rootProject.layout.projectDirectory.file("docs/manual.md")
        onlyIf { repoSingle.asFile.exists() }
        from(repoSingle)
        into(layout.projectDirectory.dir("src/main/assets/docs"))
        rename { "manual.md" }
        dependsOn(syncManualToRepo)
    }

tasks.named("preBuild") { dependsOn(syncManual, syncManualToRepo) }

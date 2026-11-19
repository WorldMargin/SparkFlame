import java.security.KeyStore
import java.security.MessageDigest
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

// ---- 模块标识：全部来自 gradle.properties，构建时注入 BuildConfig ----
// fork 之后只改 gradle.properties，不需要在源码里搜包名 / 指纹。
val modulePackage: String =
    providers.gradleProperty("spark.modulePackage").getOrElse("io.github.worldmargin.sparkflame")
val hostPackage: String =
    providers.gradleProperty("spark.hostPackage").getOrElse("com.ss.android.ugc.aweme")
val maintainer: String =
    providers.gradleProperty("spark.maintainer").getOrElse("WorldMargin")
val repoUrl: String =
    providers.gradleProperty("spark.repoUrl").getOrElse("https://gitee.com/WorldMargin/SparkFlame")

/**
 * 期望的签名证书 SHA-256（hex）。
 *
 * 优先用 `spark.signatureSha256`；留空则从签名配置指向的 keystore 现算 —— 换签名重新构建即可，
 * 不会出现「代码里的指纹和实际签名不一致，官方包把自己判成改包」。
 * 都没有（未配置签名）时为空串，运行时跳过校验（仅开发自用构建）。
 */
val signatureSha256: String =
    providers.gradleProperty("spark.signatureSha256").orNull?.takeIf { it.isNotBlank() }
        ?: releaseSigning?.let { props ->
            runCatching {
                val store = rootProject.file(props.getProperty("storeFile"))
                val keyStore = KeyStore.getInstance(store, props.getProperty("storePassword").toCharArray())
                val certificate = keyStore.getCertificate(props.getProperty("keyAlias")) ?: return@runCatching ""
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(certificate.encoded)
                    .joinToString("") { byte -> "%02x".format(byte) }
            }.getOrDefault("")
        } ?: ""

if (signatureSha256.isBlank()) {
    logger.warn(
        "[SparkFlame] 没有可用的签名指纹（未配置 spark.signatureSha256，也没有 release-signing.properties）" +
            " —— 这份构建的签名闸门会被跳过，只适合本机调试。",
    )
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
        applicationId = modulePackage
        minSdk = 26
        targetSdk = 36
        // 固定版本号：历史压缩后提交数会变小，不能再拿它当 versionCode（会触发降级安装失败）
        versionCode = 11109
        versionName = "v1.6.0"

        // 模块标识一律通过 BuildConfig 注入，源码里不写死
        buildConfigField("String", "MODULE_PACKAGE", "\"$modulePackage\"")
        buildConfigField("String", "HOST_PACKAGE", "\"$hostPackage\"")
        buildConfigField("String", "MAINTAINER", "\"$maintainer\"")
        buildConfigField("String", "REPO_URL", "\"$repoUrl\"")
        buildConfigField("String", "SIGNATURE_SHA256", "\"$signatureSha256\"")
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

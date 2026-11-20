import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64
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

/**
 * 内容指纹：把「会原样进 APK 的资源」按固定顺序哈希，构建期注入 BuildConfig。
 *
 * 运行时用同一套规则读 APK 里的条目重算，签名之外再验一层资源完整性。
 * 只取原样复制的条目（编译过的 res 不在此列），否则两次算出来的字节不一样。
 */
val contentEntries: List<Pair<String, File>> =
    buildList {
        rootProject.file("docs/manual.md").takeIf { it.isFile }?.let { add("assets/docs/manual.md" to it) }
        rootProject.file("app/src/main/assets/cm6")
            .walkTopDown()
            .filter { it.isFile }
            .forEach { add("assets/cm6/" + it.relativeTo(rootProject.file("app/src/main/assets/cm6")).path.replace('\\', '/') to it) }
        // META-INF/xposed 不参与：java_init.list 会被 R8 按混淆结果改写，源码与 APK 不一致
    }.sortedBy { it.first }

fun computeContentSha256(): String =
    MessageDigest.getInstance("SHA-256").let { md ->
        contentEntries.forEach { (name, file) ->
            md.update(name.toByteArray(Charsets.UTF_8))
            md.update(file.readBytes())
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

val contentSha256: String = computeContentSha256()

/**
 * 预生成阶段：把内容哈希用发布私钥签名，连同证书一起写进 APK（assets/spark/integrity.json）。
 *
 * 运行时先验清单的证书指纹与签名，再验资源哈希 —— 改了资源就得拿私钥重签清单，
 * 光换 APK 签名或 hook 包管理器都过不了。
 */
val integrityAssetDir = layout.buildDirectory.dir("generated/integrity/assets")
val generateIntegrity =
    tasks.register("generateIntegrity") {
        val outFile = integrityAssetDir.map { it.file("spark/integrity.json") }
        outputs.file(outFile)
        // 声明输入：fork 改了资源或换 keystore，下次构建必须重签，不能被增量构建跳过
        releaseSigning?.let { signing ->
            inputs.files(contentEntries.map { it.second } + rootProject.file(signing.getProperty("storeFile")))
            inputs.property("keyAlias", signing.getProperty("keyAlias"))
        }
        onlyIf { releaseSigning != null }
        doLast {
            val signing = releaseSigning ?: return@doLast
            val store = rootProject.file(signing.getProperty("storeFile"))
            val storePass = signing.getProperty("storePassword")
            val alias = signing.getProperty("keyAlias")
            val keyPass = signing.getProperty("keyPassword")
            val keyStore = KeyStore.getInstance(store, storePass.toCharArray())
            val privateKey = keyStore.getKey(alias, keyPass.toCharArray()) as PrivateKey
            val certificate = keyStore.getCertificate(alias)

            val digest = computeContentSha256()
            val algorithm =
                if (privateKey.algorithm.equals("EC", ignoreCase = true)) "SHA256withECDSA" else "SHA256withRSA"
            val signature =
                Signature.getInstance(algorithm).apply {
                    initSign(privateKey)
                    update(digest.toByteArray(Charsets.UTF_8))
                }.sign()

            val json =
                buildString {
                    append("{\"sha256\":\"").append(digest).append("\",")
                    append("\"sig\":\"").append(Base64.getEncoder().encodeToString(signature)).append("\",")
                    append("\"cert\":\"").append(Base64.getEncoder().encodeToString(certificate.encoded)).append("\"}")
                }
            outFile.get().asFile.apply {
                parentFile.mkdirs()
                writeText(json)
            }
        }
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
        versionCode = 11112
        versionName = "v1.6.3"

        // 模块标识一律通过 BuildConfig 注入，源码里不写死
        buildConfigField("String", "MODULE_PACKAGE", "\"$modulePackage\"")
        buildConfigField("String", "HOST_PACKAGE", "\"$hostPackage\"")
        buildConfigField("String", "MAINTAINER", "\"$maintainer\"")
        buildConfigField("String", "REPO_URL", "\"$repoUrl\"")
        buildConfigField("String", "SIGNATURE_SHA256", "\"$signatureSha256\"")
        buildConfigField("String", "CONTENT_SHA256", "\"$contentSha256\"")
        buildConfigField("Boolean", "INTEGRITY_ENABLED", "${releaseSigning != null}")
    }

    // 预生成的内容签名清单作为 assets 打进 APK
    sourceSets {
        getByName("main") {
            @Suppress("DEPRECATION")
            assets.srcDir(integrityAssetDir.get().asFile)
        }
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

    // 裸 syscall 读 APK 的 native 库：绕开 xhook 那类 PLT/GOT hook
    // 用 ndk-build 而非 CMake：本机 CMake 在中文路径下检测编译器会崩溃
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }
    // 与 local.properties 的 ndk.dir 保持一致（无空格路径，ndk-build 不接受带空格的 NDK 路径）
    ndkVersion = "27.2.12479018"

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

tasks.named("preBuild") { dependsOn(syncManual, syncManualToRepo, generateIntegrity) }

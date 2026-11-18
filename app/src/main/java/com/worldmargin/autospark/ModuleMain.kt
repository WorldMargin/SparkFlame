package com.worldmargin.autospark

import android.app.Activity
import android.app.Application
import android.content.Context
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import com.worldmargin.autospark.core.HostRuntime
import com.worldmargin.autospark.core.ModLog
import com.worldmargin.autospark.host.bridge.AutoSparkBridge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 模块入口：只做自动续火花。
 *
 * 1. 宿主 Application attach 后记录 Context，并安排「开屏 30 秒后扫会话 → 续火花」；
 * 2. 抖音设置页注入「续火花」入口，点开模块自己的管理界面。
 */
class ModuleMain : XposedModule() {
    @Volatile
    private var hostClassLoader: ClassLoader? = null

    @Volatile
    private var appContext: Context? = null
    private val installed = AtomicBoolean(false)
    private val attached = AtomicBoolean(false)

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        ModLog.bind(this)
        HostRuntime.module = this
        HostRuntime.processName = param.processName
        ModLog.i("onModuleLoaded: ${param.processName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (HostRuntime.processName != param.packageName) return
        if (!installed.compareAndSet(false, true)) return

        ModLog.i("onPackageReady: ${param.packageName}")
        hostClassLoader = param.classLoader
        HostRuntime.classLoader = param.classLoader

        hookFileOpens()
        startElfWatcher()



        hookApplicationAttach()
        hookSettingsPage(param.classLoader)
        hookStickerRoute(param.classLoader)
    }

    /** 热重载：无自有线程池需要关停，仅把上下文带到重载后的新实例。 */
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        param.setSavedInstanceState(arrayOf(appContext, hostClassLoader))
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        param.oldHookHandles.forEach { it.unhook() }

        ModLog.bind(this)
        HostRuntime.module = this

        val saved = param.savedInstanceState as? Array<*>
        val context = saved?.getOrNull(0) as? Context
        val classLoader = saved?.getOrNull(1) as? ClassLoader

        appContext = context
        if (context != null) HostRuntime.context = context
        if (classLoader != null) {
            hostClassLoader = classLoader
            HostRuntime.classLoader = classLoader
            hookSettingsPage(classLoader)
        hookStickerRoute(classLoader)
        }
        HostRuntime.processName = param.processName
        ModLog.i("热重载完成")
    }

    /** 宿主 Application attach 后取 Context 并安排开屏续火花（只取第一次 attach）。 */
    private fun hookApplicationAttach() {
        val attach =
            runCatching { Application::class.java.getDeclaredMethod("attach", Context::class.java) }
                .onFailure { ModLog.e("Application.attach 定位失败，跳过", it) }
                .getOrNull() ?: return
        hook(attach).intercept { chain ->
            val first = attached.compareAndSet(false, true)
            val raw = chain.getArg(0) as Context
            try {
                // 先让 attach 跑完：attach 完成前 ContextWrapper.mBase 还是 null，
                // 这时去取 applicationContext 会 NPE（跨天值守就是这么挂不上的）
                chain.proceed()
            } finally {
                if (first) {
                    val context = raw.applicationContext ?: raw
                    appContext = context
                    HostRuntime.context = context
                    runCatching { AutoSparkBridge.installRefreshReceiver(context) }
                        .onFailure { ModLog.e("刷新会话广播注册失败", it) }
                    runCatching { AutoSparkBridge.scheduleOnAppStart() }
                        .onFailure { ModLog.e("续火花任务安排失败", it) }
                    runCatching { (chain.thisObject as? Application)?.let { AutoSparkBridge.startDayWatcher(it) } }
                        .onFailure { ModLog.e("跨天值守挂载失败", it) }
                }
            }
        }
    }

    /** 设置页创建后注入「续火花」入口。 */
    /** 临时：抓互动表情发送入口的 Bundle，定位贴纸参数。 */
    /** 临时：记录宿主打开的文件路径，定位互动表情清单来源。 */
    /** 临时：轮询 im_resources，发现 elf_emoji.json 就复制。 */
    private fun startElfWatcher() {
        runCatching {
            val root = java.io.File("/data/user/0/com.ss.android.ugc.aweme/files/im_resources")
            val out = java.io.File("/data/user/0/com.ss.android.ugc.aweme/files/spark_elf_capture.json")
            val observer = object : android.os.FileObserver(root.absolutePath, ALL_EVENTS) {
                override fun onEvent(event: Int, path: String?) {
                    runCatching {
                        root.walkTopDown().firstOrNull { it.isFile && it.name.contains("elf_emoji") && it.length() > 2 }?.let { f ->
                            out.writeText(f.readText())
                        }
                    }
                }
            }
            observer.startWatching()
        }
        Thread {
            val root = java.io.File("/data/user/0/com.ss.android.ugc.aweme/files/im_resources")
            val out = java.io.File("/data/user/0/com.ss.android.ugc.aweme/files/spark_elf_capture.json")
            repeat(600) {
                runCatching {
                    root.walkTopDown().firstOrNull { it.isFile && it.name.contains("elf_emoji") && it.length() > 2 }?.let { f ->
                        out.writeText(f.readText())
                    }
                }
                Thread.sleep(1000L)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun hookFileOpens() {
        val log = java.io.File("/data/user/0/com.ss.android.ugc.aweme/files/spark_file_open.log")
        fun writePath(path: String) {
            if (path.isEmpty() || !path.contains("com.ss.android.ugc.aweme")) return
            if (!Regex("emoji|lite|keva|mmkv|sticker|emoji_").containsMatchIn(path)) return
            runCatching { log.appendText(path + "\n") }
            if (path.contains("ELF_EMOJI") || path.contains("elf_emoji") || path.contains("im_resources")) {
                runCatching {
                    val src = java.io.File(path)
                    if (src.isFile) {
                        java.io.File("/data/user/0/com.ss.android.ugc.aweme/files/spark_elf_capture.json")
                            .writeText(src.readText())
                    }
                }
            }
        }
        runCatching {
            val linux = Class.forName("libcore.io.Linux", false, null)
            linux.declaredMethods.filter { it.name == "open" }.forEach { method ->
                runCatching {
                    hook(method).intercept { chain ->
                        runCatching { writePath(chain.args.getOrNull(0)?.toString().orEmpty()) }
                        chain.proceed()
                    }
                }
            }
        }
        runCatching {
            java.io.FileInputStream::class.java.declaredConstructors.forEach { ctor ->
                runCatching {
                    hook(ctor).intercept { chain ->
                        runCatching {
                            chain.args.forEach { arg ->
                                when (arg) {
                                    is String -> writePath(arg)
                                    is java.io.File -> writePath(arg.absolutePath)
                                }
                            }
                        }
                        chain.proceed()
                    }
                }
            }
        }
    }

    private fun hookStickerRoute(classLoader: ClassLoader) {
        val classes = listOf(
            "com.ss.android.ugc.aweme.im.business.chat.feature.actionbar.schema.SendInteractiveEmojiRoute",
            "com.ss.android.ugc.aweme.im.business.chat.feature.actionbar.schema.SendEmojiMsgRoute",
            "com.ss.android.ugc.aweme.im.business.chat.feature.actionbar.schema.SendElfEmojiRoute",
        )
        val marker = org.json.JSONObject()
        runCatching {
            val db = Class.forName("com.ss.android.ugc.aweme.im.business.chat.feature.emojihorizontal.autoemoji.hotcache.AutoEmojiDatabase", false, classLoader)
            db.declaredMethods.filter { it.name.startsWith("LIZ") }.forEach { method ->
                runCatching {
                    hook(method).intercept { chain ->
                        chain.proceed()
                        runCatching {
                            val result = runCatching { chain.javaClass.getMethod("getResult").invoke(chain) }.getOrNull()
                            val dao = result ?: return@runCatching
                            val info = org.json.JSONObject()
                            info.put("dao", dao.javaClass.name)
                            info.put("methods", org.json.JSONArray(dao.javaClass.declaredMethods.map { m ->
                                m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + "):" + m.returnType.simpleName
                            }))
                            java.io.File("/data/user/0/com.ss.android.ugc.aweme/files/spark_emoji_dao.json").writeText(info.toString())
                        }
                    }
                }
            }
        }

        runCatching {
            val route = Class.forName("com.ss.android.ugc.aweme.im.business.chat.feature.actionbar.schema.SendInteractiveEmojiRoute", false, classLoader)
            route.declaredMethods.filter { it.name == "LIZJ" }.forEach { method ->
                runCatching {
                    hook(method).intercept { chain ->
                        chain.proceed()
                        runCatching {
                            val result = runCatching { chain.javaClass.getMethod("getResult").invoke(chain) }.getOrNull()
                                ?: runCatching { chain.javaClass.getMethod("getReturnValue").invoke(chain) }.getOrNull()
                            java.io.File("/data/user/0/com.ss.android.ugc.aweme/files/spark_lite_list.json")
                                .writeText(result?.toString() ?: "null")
                        }
                    }
                }.onFailure { marker.put("LIZJ", it.toString()) }
            }
        }.onFailure { marker.put("LIZJClass", it.toString()) }

        runCatching {
            val repo = Class.forName("com.ss.android.ugc.aweme.im.business.chat.feature.emojihorizontal.autoemoji.AutoEmojiDataRepository", false, classLoader)
            var instance: Any? = null
            repo.declaredConstructors.forEach { ctor ->
                runCatching { hook(ctor).intercept { chain -> chain.proceed(); instance = chain.thisObject } }
            }
            repo.declaredMethods.filter { it.name == "DD" || it.name == "ED" }.forEach { method ->
                runCatching {
                    hook(method).intercept { chain ->
                        chain.proceed()
                        runCatching {
                            val field = repo.getDeclaredField("d").apply { isAccessible = true }
                            val value = field.get(instance)?.toString() ?: "null"
                            java.io.File("/data/user/0/com.ss.android.ugc.aweme/files/spark_lite_repo.json").writeText(value)
                        }
                    }
                }
            }
        }.onFailure { marker.put("repo", it.toString()) }

        runCatching {
            val utils = Class.forName("X.1NQz", false, classLoader)
            utils.declaredMethods.filter { it.name == "sendMessage" && it.parameterCount == 3 }.forEach { method ->
                runCatching {
                    hook(method).intercept { chain ->
                        runCatching {
                            val message = chain.args.getOrNull(0)
                            val type = runCatching { message?.javaClass?.getMethod("getMsgType")?.invoke(message) as? Number }.getOrNull()?.toInt()
                            if (type == 5) {
                                val content = runCatching { message?.javaClass?.getMethod("getContent")?.invoke(message)?.toString() }.getOrNull()
                                java.io.File("/data/user/0/com.ss.android.ugc.aweme/files/spark_lite_send.json")
                                    .writeText(org.json.JSONObject().put("content", content ?: "").toString())
                            }
                        }
                        chain.proceed()
                    }
                    marker.put("sendMessage#" + method.name, "ok")
                }.onFailure { marker.put("sendMessage", it.toString()) }
            }
        }.onFailure { marker.put("sendMessageClass", it.toString()) }
        classes.forEach { name ->
            runCatching {
                val cls = Class.forName(name, false, classLoader)
                cls.declaredMethods.filter { it.name.startsWith("LJ") && it.parameterCount >= 1 }.forEach { method ->
                    val tag = name + "#" + method.name
                    runCatching {
                        hook(method).intercept { chain ->
                            runCatching {
                                val line = org.json.JSONObject()
                                    .put("tag", tag)
                                    .put("args", org.json.JSONArray(chain.args.map { it?.javaClass?.name ?: "null" }))
                                    .put("first", chain.args.firstOrNull()?.toString()?.take(200) ?: "")
                                java.io.File("/data/user/0/com.ss.android.ugc.aweme/files/spark_lite_hits.jsonl")
                                    .appendText(line.toString() + "\n")
                                val context = chain.args.firstOrNull { it is android.content.Context } as? android.content.Context
                                val bundle = chain.args.firstOrNull { it is android.os.Bundle } as? android.os.Bundle
                                if (context != null && bundle != null) {
                                    val json = org.json.JSONObject()
                                    bundle.keySet().forEach { key -> json.put(key, bundle.get(key)?.toString() ?: "null") }
                                    java.io.File(context.filesDir, "spark_lite_bundle.json").writeText(json.toString())
                                }
                            }
                            chain.proceed()
                        }
                        marker.put(tag, "ok")
                    }.onFailure { marker.put(tag, it.toString()) }
                }
            }.onFailure { marker.put(name, it.toString()) }
        }
        runCatching {
            java.io.File("/data/user/0/com.ss.android.ugc.aweme/files/spark_hook_marker.json").writeText(marker.toString())
        }
    }

    private fun hookSettingsPage(classLoader: ClassLoader) {
        val onCreate = SettingsInjector.targetCreateMethod(classLoader) ?: return
        hook(onCreate).intercept { chain ->
            chain.proceed()
            (chain.thisObject as? Activity)?.let { SettingsInjector.injectEntry(it) }
        }
    }
}

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
import com.worldmargin.autospark.features.autospark.bridge.AutoSparkBridge
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

        hookApplicationAttach()
        hookSettingsPage(param.classLoader)
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
            if (attached.compareAndSet(false, true)) {
                val raw = chain.getArg(0) as Context
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
            chain.proceed()
        }
    }

    /** 设置页创建后注入「续火花」入口。 */
    private fun hookSettingsPage(classLoader: ClassLoader) {
        val onCreate = SettingsInjector.targetCreateMethod(classLoader) ?: return
        hook(onCreate).intercept { chain ->
            chain.proceed()
            (chain.thisObject as? Activity)?.let { SettingsInjector.injectEntry(it) }
        }
    }
}

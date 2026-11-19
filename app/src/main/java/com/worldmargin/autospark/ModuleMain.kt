package com.worldmargin.autospark

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import com.worldmargin.autospark.app.provider.ConfigContract
import com.worldmargin.autospark.core.AuthGate
import com.worldmargin.autospark.core.HostRuntime
import com.worldmargin.autospark.core.ModLog
import com.worldmargin.autospark.host.bridge.AutoSparkBridge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 模块入口：只做自动续火花。
 *
 * 1. **先过签名闸门**：模块被改包分发时（签名与编译时的期望值不符），
 *    宿主侧功能一律不启动，直到用户在 App 里点「无视风险」授权（见 [AuthGate]）；
 * 2. 宿主 Application attach 后记录 Context，并安排「开屏后扫会话 → 续火花」；
 * 3. 抖音设置页注入「续火花」入口，点开模块自己的管理界面。
 */
class ModuleMain : XposedModule() {
    @Volatile
    private var hostClassLoader: ClassLoader? = null

    @Volatile
    private var appContext: Context? = null
    private val installed = AtomicBoolean(false)
    private val attached = AtomicBoolean(false)

    /** 宿主侧功能是否已经启动（授权前不放行，授权后只启动一次）。 */
    private val featuresStarted = AtomicBoolean(false)

    /** 授权监听是否已挂上（避免重复注册）。 */
    private val gateWatching = AtomicBoolean(false)

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

        // 只挂 attach：签名闸门要 Context 才能判定，其余 hook 等放行后再装
        hookApplicationAttach()
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
            // 签名不符且未授权时不要顺手把设置页 hook 装回去
            if (context == null || AuthGate.allowed(context)) hookSettingsPage(classLoader)
        }
        HostRuntime.processName = param.processName
        ModLog.i("热重载完成")
    }

    /**
     * 宿主 Application attach 后取 Context，过签名闸门，再安排开屏续火花（只取第一次 attach）。
     *
     * 闸门放在这里而不是 [onPackageReady]：签名要 Context 才能查（PackageManager），
     * 而 attach 是宿主进程里最早拿到 Context 的时机。
     */
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
                    val application = chain.thisObject as? Application
                    appContext = context
                    HostRuntime.context = context
                    if (AuthGate.allowed(context)) {
                        startHostFeatures(context, application)
                    } else {
                        // 改包且未授权：宿主侧一行代码都不跑，只留一个等授权的监听
                        ModLog.w("签名校验未通过且用户未授权，宿主侧功能不启动")
                        awaitAuthorization(context, application)
                    }
                }
            }
        }
    }

    /** 启动全部宿主侧能力（签名通过、或用户已授权）。 */
    private fun startHostFeatures(
        context: Context,
        application: Application?,
    ) {
        if (!featuresStarted.compareAndSet(false, true)) return
        hostClassLoader?.let { classLoader ->
            runCatching { hookSettingsPage(classLoader) }
                .onFailure { ModLog.e("设置页入口注入失败", it) }
        }
        runCatching { AutoSparkBridge.installRefreshReceiver(context) }
            .onFailure { ModLog.e("刷新会话广播注册失败", it) }
        runCatching { AutoSparkBridge.scheduleOnAppStart() }
            .onFailure { ModLog.e("续火花任务安排失败", it) }
        runCatching { application?.let { AutoSparkBridge.startDayWatcher(it) } }
            .onFailure { ModLog.e("跨天值守挂载失败", it) }
    }

    /**
     * 未授权时唯一会跑的宿主侧代码：等 App 里点「无视风险」。
     *
     * 授权位存在模块 App 的私有目录，宿主只能跨进程读；所以由 App 授权后发一条定向广播，
     * 这里收到再重新判定 —— 用户在抖音开着的时候授权，也能立刻生效，不用重开抖音。
     */
    private fun awaitAuthorization(
        context: Context,
        application: Application?,
    ) {
        if (!gateWatching.compareAndSet(false, true)) return
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    source: Context?,
                    intent: Intent?,
                ) {
                    if (intent?.action != ConfigContract.ACTION_SIG_CHANGED) return
                    // 跨进程读授权位可能阻塞，别占主线程
                    Thread(
                        {
                            AuthGate.invalidate()
                            if (AuthGate.allowed(context)) {
                                ModLog.i("已获得用户授权，启动宿主侧功能")
                                startHostFeatures(context, application)
                            }
                        },
                        "spark-gate",
                    ).apply { isDaemon = true }.start()
                }
            }
        runCatching {
            val filter = IntentFilter(ConfigContract.ACTION_SIG_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
        }.onFailure { ModLog.e("授权监听注册失败", it) }
    }

    /** 设置页创建后注入「续火花」入口；未授权时注入也无意义，直接跳过。 */
    private fun hookSettingsPage(classLoader: ClassLoader) {
        val onCreate = SettingsInjector.targetCreateMethod(classLoader) ?: return
        hook(onCreate).intercept { chain ->
            val result = chain.proceed()
            // 用缓存判断：这里在宿主主线程上，不能做跨进程查询
            if (AuthGate.allowedCached()) {
                (chain.thisObject as? Activity)?.let { SettingsInjector.injectEntry(it) }
            }
            result
        }
    }
}

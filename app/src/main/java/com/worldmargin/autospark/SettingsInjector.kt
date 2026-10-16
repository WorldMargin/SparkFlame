package com.worldmargin.autospark

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.worldmargin.autospark.core.ModLog
import com.worldmargin.autospark.features.autospark.ui.SparkSettingsActivity
import java.lang.reflect.Method

/**
 * 抖音设置页注入「续火花」入口。
 *
 * 定位宿主设置 Activity 的 onCreate，在原生条目模板处插入同款样式的入口项
 * （tag 标记防重复注入），点击打开模块自己的续火花管理界面。
 */
object SettingsInjector {
    private const val SETTING_ACTIVITY = "com.ss.android.ugc.aweme.setting.ui.DouYinSettingNewVersionActivity"
    private const val COMMON_ITEM_VIEW = "com.bytedance.ies.dmt.ui.common.views.CommonItemView"
    private const val MODULE_ENTRY_TAG = "doumcp_settings_entry"
    private const val MODULE_PACKAGE = "com.worldmargin.autospark"
    private const val DEFAULT_ICON_SIZE_PX = 48

    /**
     * 定位宿主设置页的 onCreate 方法（hook 目标）。
     *
     * @param classLoader 宿主 ClassLoader
     * @return 目标 Method；设置页类不存在时返回 null
     */
    fun targetCreateMethod(classLoader: ClassLoader): Method? {
        val activityClass =
            runCatching {
                Class.forName(SETTING_ACTIVITY, false, classLoader)
            }.getOrNull() ?: return null
        return activityClass.declaredMethods.firstOrNull {
            it.name == "onCreate" && it.parameterCount == 1 &&
                it.parameterTypes[0] == android.os.Bundle::class.java
        }
    }

    /**
     * 向设置页注入入口项（已注入则跳过）。
     *
     * @param activity 宿主设置页 Activity
     */
    fun injectEntry(activity: Activity) {
        val itemViewClass =
            runCatching {
                Class.forName(COMMON_ITEM_VIEW, false, activity.classLoader)
            }.getOrNull() ?: return

        val contentView = activity.findViewById<View>(android.R.id.content) ?: return
        val template = findAllViews(contentView, itemViewClass).firstOrNull() ?: return
        val parent = template.parent as? ViewGroup ?: return
        if (findModuleEntry(parent) != null) return

        val entry =
            itemViewClass
                .getDeclaredConstructor(Context::class.java)
                .newInstance(activity) as View
        entry.tag = MODULE_ENTRY_TAG

        cloneVisualState(template, entry)
        setLeftText(entry, "续火花")
        setModuleIcon(entry, activity)

        attachClick(entry) { openSettings(activity) }
        parent.addView(entry, parent.indexOfChild(template))
    }

    /**
     * 把点击动作挂到条目**及其所有子 view** 上。
     *
     * 宿主的 `CommonItemView` 内部有些子 view 自带 clickable（点击会被它们吃掉，
     * 表现为「点了一点动静都没有」，2026-10-16 真机定位），所以逐个挂上。
     */
    private fun attachClick(
        view: View,
        action: () -> Unit,
    ) {
        view.isClickable = true
        view.setOnClickListener { action() }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                attachClick(view.getChildAt(index), action)
            }
        }
    }

    private fun findModuleEntry(parent: ViewGroup): View? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.tag == MODULE_ENTRY_TAG) return child
        }
        return null
    }

    /** 从模板条目复制布局参数、背景与右侧图标，使入口项与原生条目外观一致 */
    private fun cloneVisualState(
        template: View,
        entry: View,
    ) {
        template.layoutParams?.let { lp ->
            entry.layoutParams =
                when (lp) {
                    is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(lp)
                    else -> ViewGroup.LayoutParams(lp)
                }
        }
        template.background
            ?.constantState
            ?.newDrawable()
            ?.let { entry.background = it }

        val templateRight = rightIconView(template) ?: return
        val entryRight = rightIconView(entry) ?: return
        templateRight.drawable?.constantState?.newDrawable()?.let {
            entryRight.setImageDrawable(it)
        }
    }

    private fun setLeftText(
        entry: View,
        text: String,
    ) {
        runCatching {
            entry.javaClass.getMethod("setLeftText", CharSequence::class.java).invoke(entry, text)
        }
    }

    /** 左图标位设为模块图标（资源取自模块 APK，转位图后设置） */
    private fun setModuleIcon(
        entry: View,
        context: Context,
    ) {
        runCatching {
            val icon =
                leftIconView(entry) ?: run {
                    ModLog.w("图标：未找到左图标 view")
                    return
                }
            val moduleRes = context.packageManager.getResourcesForApplication(MODULE_PACKAGE)
            val drawable = moduleRes.getDrawable(R.drawable.ic_spark, null)
            icon.setImageBitmap(drawableToBitmap(drawable))
            icon.visibility = View.VISIBLE
        }.onFailure { ModLog.w("图标：设置失败 $it") }
    }

    /** 图标是竖长的矢量图，转成正方形位图并居中，避免在方形图标位里被拉伸。 */
    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: DEFAULT_ICON_SIZE_PX
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: DEFAULT_ICON_SIZE_PX
        val size = maxOf(width, height)
        val left = (size - width) / 2
        val top = (size - height) / 2
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.setBounds(left, top, left + width, top + height)
            drawable.draw(Canvas(bitmap))
        }
    }

    private fun leftIconView(item: View): ImageView? = iconView(item, "getIvwLeft")

    private fun rightIconView(item: View): ImageView? = iconView(item, "getIvwRight")

    private fun iconView(
        item: View,
        accessor: String,
    ): ImageView? =
        runCatching {
            item.javaClass.getMethod(accessor).invoke(item) as? ImageView
        }.getOrNull()

    /**
     * 打开模块自己的续火花管理界面。
     *
     * ⚠️ 不能用 `Intent(activity, SparkSettingsActivity::class.java)`：本方法运行在抖音进程里，
     * 而 `Intent(Context, Class)` 内部的包名取的是 **`context.getPackageName()`**（= 抖音），
     * 于是拼出「抖音包 + 模块类名」这种不存在的组件，系统直接 `START_CLASS_NOT_FOUND (-92)`，
     * 表现就是点了设置页入口完全没反应（2026-10-16 真机定位）。必须显式写模块包名。
     */
    private fun openSettings(activity: Activity) {
        runCatching {
            val intent =
                Intent()
                    .setClassName(MODULE_PACKAGE, SparkSettingsActivity::class.java.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        }.onFailure { ModLog.w("打开续火花界面失败: $it") }
    }

    private fun findAllViews(
        root: View,
        targetClass: Class<*>,
    ): List<View> {
        val result = mutableListOf<View>()
        if (targetClass.isInstance(root)) {
            result.add(root)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                result.addAll(findAllViews(root.getChildAt(i), targetClass))
            }
        }
        return result
    }
}

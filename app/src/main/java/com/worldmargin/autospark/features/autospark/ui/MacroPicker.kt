package com.worldmargin.autospark.features.autospark.ui

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import com.worldmargin.autospark.features.autospark.store.ConfigStore

/**
 * 宏多选：把某个模板 / 某个会话要挂的宏勾出来。
 *
 * 关系存在宏那一侧（`MacroScript.appliesTo`），所以模板侧与会话侧改的是同一份数据，
 * 与「宏/函数」页里的选目标完全等价。
 */
object MacroPicker {
    fun show(
        activity: Activity,
        targetId: String,
        targetLabel: String,
        onDone: () -> Unit,
    ) {
        val config = ConfigStore.load(activity)
        if (config.macros.isEmpty()) {
            Toast.makeText(activity, "还没有宏，先去「宏/函数」页新建一个", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = config.macros.map { it.name.ifBlank { "(未命名宏)" } }.toTypedArray()
        val checked = BooleanArray(config.macros.size) { targetId in config.macros[it].appliesTo }
        AlertDialog
            .Builder(activity)
            .setTitle("「$targetLabel」挂哪些宏（在模板脚本之后执行）")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }.setPositiveButton("应用") { _, _ ->
                ConfigStore.update(activity) { cfg ->
                    cfg.copy(
                        macros =
                            cfg.macros.mapIndexed { index, macro ->
                                val has = targetId in macro.appliesTo
                                when {
                                    checked[index] && !has -> macro.copy(appliesTo = macro.appliesTo + targetId)
                                    !checked[index] && has -> macro.copy(appliesTo = macro.appliesTo - targetId)
                                    else -> macro
                                }
                            },
                    )
                }
                onDone()
            }.setNegativeButton("取消", null)
            .show()
    }
}

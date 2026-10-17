package com.worldmargin.autospark.features.autospark.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.worldmargin.autospark.features.autospark.model.ConfigCodec
import com.worldmargin.autospark.features.autospark.model.SyncMode
import com.worldmargin.autospark.features.autospark.model.TemplateGroup
import com.worldmargin.autospark.features.autospark.model.TemplateOps
import com.worldmargin.autospark.features.autospark.model.TemplateVar
import com.worldmargin.autospark.features.autospark.script.SparkScriptEngine
import com.worldmargin.autospark.features.autospark.script.TemplateVarPrelude
import com.worldmargin.autospark.features.autospark.store.ConfigStore
import com.worldmargin.autospark.features.autospark.ui.UiKit.asClickable
import com.worldmargin.autospark.features.autospark.ui.UiKit.actionRow
import com.worldmargin.autospark.features.autospark.ui.UiKit.column
import com.worldmargin.autospark.features.autospark.ui.UiKit.divider
import com.worldmargin.autospark.features.autospark.ui.UiKit.dp
import com.worldmargin.autospark.features.autospark.ui.UiKit.icon
import com.worldmargin.autospark.features.autospark.ui.UiKit.iconButton
import com.worldmargin.autospark.features.autospark.ui.UiKit.padSystemBars
import com.worldmargin.autospark.features.autospark.ui.UiKit.pasteButton
import com.worldmargin.autospark.features.autospark.ui.UiKit.rounded
import com.worldmargin.autospark.features.autospark.ui.UiKit.row
import com.worldmargin.autospark.features.autospark.ui.UiKit.text
import com.worldmargin.autospark.features.autospark.ui.UiKit.titleBar
import java.util.UUID

/** 编辑通用模板（模板组）的名称与文案。 */
class TemplateEditActivity : Activity() {
    companion object {
        const val EXTRA_GROUP_ID = "group_id"

        /** 变量页的请求码。 */
        private const val REQUEST_VARIABLES = 0x72
    }

    private var groupId: String? = null
    private lateinit var nameField: EditText
    private lateinit var editor: SparkEditor
    private var existed = false
    private var originalBody = ""

    /** 变量列表（在 [VariableListActivity] 里改，回来时整份替换）。 */
    private var vars: List<TemplateVar> = emptyList()
    private lateinit var varsSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        groupId = intent.getStringExtra(EXTRA_GROUP_ID)
        val config = ConfigStore.load(this)
        val group = config.group(groupId)
        existed = group != null
        originalBody = group?.body ?: ""

        val headline = if (existed) "编辑模板" else "新建模板"

        val root = column().apply { setPadding(dp(16), dp(8), dp(16), dp(8)) }

        root.addView(text("模板名称", 13f, UiKit.COLOR_DIM))
        nameField =
            EditText(this).apply {
                setText(group?.name ?: "")
                hint = "例如：死党组"
                setSingleLine()
            }
        root.addView(nameField)

        root.addView(variablesRow(group?.variables ?: emptyList()))

        root.addView(text("文案脚本（把要发送的文案赋给 " + SparkScriptEngine.RESULT_VAR + "）", 13f, UiKit.COLOR_DIM))

        editor =
            SparkEditors.create(this, config).apply {
                // 新建模板默认空白：要文案就从模板页的内置模板「添加为我的模板」，或用下面的「补全」写
                setText(group?.body ?: "")
                // 变量也声明进类型定义：脚本里 ctx.template. 能补全出变量名
                applyTypes(config, config.templateVarsForGroup(groupId.orEmpty()))
            }
        root.addView(
            editor.view,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        root.addView(
            actionRow {
                addView(iconButton(UiKit.Icons.PREVIEW, "", primary = true, compact = true, hint = "预览") { showPreview() })

                addView(pasteButton(editor))
                addView(iconButton(UiKit.Icons.LIGHTBULB, "", compact = true, hint = "补全") { editor.showCompletion() })
                addView(iconButton(UiKit.Icons.WAND, "", compact = true, hint = "格式化") { editor.format() })
                if (existed) {
                    addView(iconButton(UiKit.Icons.PLUG, "", compact = true, hint = "挂载宏") { pickMacros() })
                    addView(iconButton(UiKit.Icons.PERSON_ADD, "", compact = true, hint = "继承会话") { pickMembers() })
                    addView(iconButton(UiKit.Icons.TRASH, "", compact = true, hint = "删除") { confirmDelete() })
                }
            },
        )

        setContentView(
            column().apply {
                setBackgroundColor(UiKit.COLOR_BG)
                padSystemBars()
                addView(titleBar(headline, action = saveAction(), onBack = { finish() }))
                addView(divider())
                addView(root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f })
            },
        )
    }

    /**
     * 变量入口：**变量在单独一页里编辑**（[VariableListActivity]，一行一个：
     * 左边名字、右边两三行高的小 Monaco），这里只显示个数与名字摘要。
     *
     * 表达式在发送时按定义顺序求值，能读 ctx，也能引用前面已经算出来的 ctx.template.xxx；
     * 本模板没定义的变量会继续往父模板找。
     */
    private fun variablesRow(initial: List<TemplateVar>): android.view.View {
        vars = initial
        varsSummary = text("", 13f, UiKit.COLOR_DIM)
        val entry =
            row().apply {
                background = rounded(UiKit.COLOR_CARD, 10)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(10) }
                addView(icon(UiKit.Icons.VARIABLE_GROUP, 18f, UiKit.COLOR_ACCENT).apply { setPadding(0, 0, dp(12), 0) })
                addView(
                    column().apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        addView(text("变量", 16f, UiKit.COLOR_TEXT, bold = true))
                        addView(varsSummary)
                    },
                )
                addView(icon(UiKit.Icons.CHEVRON_RIGHT, 16f, UiKit.COLOR_DIM))
            }
        entry.asClickable { openVariables() }
        refreshVarsSummary()
        return entry
    }

    /** 摘要：个数 + 名字。 */
    private fun refreshVarsSummary() {
        varsSummary.text = if (vars.isEmpty()) "无" else "${vars.size} 个：" + vars.joinToString("、") { it.name }
    }

    /** 打开变量页（整份列表带过去，保存后整份带回来）。 */
    private fun openVariables() {
        startActivityForResult(
            Intent(this, VariableListActivity::class.java)
                .putExtra(VariableListActivity.EXTRA_VARS, ConfigCodec.encodeVars(vars).toString())
                .putExtra(VariableListActivity.EXTRA_OWNER, nameField.text.toString().trim()),
            REQUEST_VARIABLES,
        )
    }

    /** 变量页的结果：整份替换（它自己负责合并重名 / 忽略空名字）。 */
    @Deprecated("变量页回传")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VARIABLES || resultCode != RESULT_OK || data == null) return
        val raw = data.getStringExtra(VariableListActivity.RESULT_VARS) ?: return
        vars =
            runCatching { ConfigCodec.decodeVars(org.json.JSONArray(raw)) }
                .getOrDefault(vars)
        refreshVarsSummary()
    }

    /** 当前变量（变量页回来时已经合并好，这里直接用）。 */
    private fun collectVariables(): List<TemplateVar> = vars


    /** 勾选挂在本模板上的宏（宏在模板脚本之后执行）。 */
    private fun pickMacros() {
        val id = groupId ?: return
        val name = nameField.text.toString().ifBlank { "本模板" }
        MacroPicker.show(this, id, name) { }
    }

    private fun showPreview() {
        val config = ConfigStore.load(this)
        val body = editor.text
        // 预览与实际执行一致：自定义函数注入 → 模板 → 挂在本模板上的宏
        val program = config.composeProgram(body, config.macrosFor(listOfNotNull(groupId)))
        val sample = SparkPreview.sample(config)
        // 变量用**当前正在编辑的**（还没保存也算），这样改完表达式直接能预览
        val vars = collectVariables()
        val rendered =
            runCatching {
                SparkScriptEngine.render(
                    TemplateVarPrelude.of(vars) + program,
                    SparkPreview.variables(sample),
                )
            }.getOrElse { "【预览失败】" + (it.message ?: it.toString()) }
        AlertDialog
            .Builder(this)
            .setTitle("预览（样例：${sample?.displayName ?: "无缓存，用占位值"}）")
            .setMessage(rendered)
            .setPositiveButton("好", null)
            .show()
    }

    /** 标题栏右侧的保存动作。 */
    private fun saveAction(): View = iconButton(UiKit.Icons.CHECK, "保存", primary = true, compact = true) { save() }

    private fun save() {
        val name = nameField.text.toString().trim()
        val body = editor.text

        if (existed) {
            val bodyChanged = body != originalBody
            ConfigStore.update(this) { config ->
                val old = config.group(groupId)
                val updated = old?.copy(name = name, body = body, updatedAt = System.currentTimeMillis(), variables = collectVariables())
                config.copy(groups = config.groups.map { if (it.id == groupId) updated!! else it })
            }
            val group = ConfigStore.load(this).group(groupId)
            if (bodyChanged && group != null) {
                askSyncChildren(group)
            } else {
                finish()
            }
        } else {
            val id = UUID.randomUUID().toString()
            ConfigStore.update(this) { config ->
                val group =
            TemplateGroup(
                id = id,
                name = name,
                body = body,
                updatedAt = System.currentTimeMillis(),
                variables = collectVariables(),
            )
                config.copy(groups = config.groups + group)
            }
            finish()
        }
    }

    /**
     * 父模板内容改动后，询问如何同步子模板。
     *
     * 有「已本地改过、又没开跟随更新」的派生模板时，先把它们**列出来**再问要不要覆盖 ——
     * 直接静默覆盖会把别人改过的文案冲掉。
     */
    private fun askSyncChildren(group: TemplateGroup) {
        val config = ConfigStore.load(this)
        val pending =
            config
                .membersOf(group.id)
                .filter { it.modified && !it.autoSync }
        if (pending.isEmpty()) {
            askSyncAll(group)
            return
        }
        val names =
            pending.joinToString("\n") { member ->
                "· " + config.conversationLabel(member.conversationId)
            }
        AlertDialog
            .Builder(this)
            .setTitle("要覆盖这 ${pending.size} 个派生模板吗？")
            .setMessage("下面这些派生模板已经本地改过、又没有开启跟随更新：\n\n$names\n\n覆盖后会变成当前模板的内容。")
            .setPositiveButton("覆盖这些") { _, _ ->
                ConfigStore.update(this) { cfg ->
                    cfg.copy(
                        conversations =
                            TemplateOps.syncSelected(
                                cfg,
                                group.id,
                                group.body,
                                pending.map { it.conversationId }.toSet(),
                                System.currentTimeMillis(),
                            ),
                    )
                }
                Toast.makeText(this, "已覆盖 ${pending.size} 个派生模板", Toast.LENGTH_SHORT).show()
                finish()
            }.setNeutralButton("全部同步") { _, _ ->
                syncAll(group)
            }.setNegativeButton("保留它们的修改") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    /** 没有「已改但未跟随」的子模板时，仍给出常规的同步选择。 */
    private fun askSyncAll(group: TemplateGroup) {
        val options = arrayOf("仅未修改的会话模板", "仅已修改的会话模板", "全部会话模板", "不同步")
        AlertDialog
            .Builder(this)
            .setTitle("模板已修改，是否同步到子模板？")
            .setItems(options) { _, which ->
                if (which < 3) {
                    val mode =
                        when (which) {
                            0 -> SyncMode.UNMODIFIED
                            1 -> SyncMode.MODIFIED
                            else -> SyncMode.ALL
                        }
                    ConfigStore.update(this) { config ->
                        config.copy(
                            conversations =
                                TemplateOps.syncChildren(config, group.id, group.body, mode, System.currentTimeMillis()),
                        )
                    }
                    Toast.makeText(this, "已同步", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
            .setCancelable(false)
            .show()
    }

    /** 全部同步（把新内容推给所有子模板）。 */
    private fun syncAll(group: TemplateGroup) {
        ConfigStore.update(this) { config ->
            config.copy(
                conversations =
                    TemplateOps.syncChildren(config, group.id, group.body, SyncMode.ALL, System.currentTimeMillis()),
            )
        }
        Toast.makeText(this, "已全部同步", Toast.LENGTH_SHORT).show()
        finish()
    }

    /** 选择哪些会话继承本模板（勾选=继承本模板，取消=变为空白自定义）。 */
    private fun pickMembers() {
        val id = groupId ?: return
        val config = ConfigStore.load(this)
        val snapshots = config.snapshots.filter { !it.isGroup }
        if (snapshots.isEmpty()) {
            Toast.makeText(this, "暂无会话快照，请先在主页刷新会话", Toast.LENGTH_SHORT).show()
            return
        }
        val checked =
            BooleanArray(snapshots.size) {
                config.conversation(snapshots[it].conversationId)?.inheritFrom == id
            }
        AlertDialog
            .Builder(this)
            .setTitle("选择继承本模板的会话")
            .setMultiChoiceItems(snapshots.map { it.displayName.ifBlank { "(无名)" } }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }.setPositiveButton("应用") { _, _ ->
                val now = System.currentTimeMillis()
                ConfigStore.update(this) { cfg ->
                    val group = cfg.group(id) ?: return@update cfg
                    var list = cfg.conversations
                    snapshots.forEachIndexed { index, snapshot ->
                        val current = list.firstOrNull { it.conversationId == snapshot.conversationId }
                        if (checked[index]) {
                            if (current?.inheritFrom != id) {
                                val next =
                                    TemplateOps.inheritFrom(
                                        group,
                                        snapshot.conversationId,
                                        current?.autoSync ?: false,
                                        now,
                                        variables = current?.variables.orEmpty(),
                                    )
                                list =
                                    if (current == null) {
                                        list + next
                                    } else {
                                        list.map { if (it.conversationId == snapshot.conversationId) next else it }
                                    }
                            }
                        } else if (current != null && current.inheritFrom == id) {
                            val next =
                                TemplateOps.blank(
                                    current.conversationId,
                                    current.body,
                                    now,
                                    variables = current.variables,
                                )
                            list = list.map { if (it.conversationId == snapshot.conversationId) next else it }
                        }
                    }
                    cfg.copy(conversations = list)
                }
                Toast.makeText(this, "已更新继承关系", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete() {
        AlertDialog
            .Builder(this)
            .setTitle("删除模板？")
            .setMessage("挂在该模板下的会话会退回「空白模板」，内容保留。")
            .setPositiveButton("删除") { _, _ ->
                ConfigStore.update(this) { config ->
                    val conversations =
                        config.conversations.map { child ->
                            if (child.inheritFrom == groupId) {
                                TemplateOps.blank(
                                    child.conversationId,
                                    child.body,
                                    System.currentTimeMillis(),
                                    variables = child.variables,
                                )
                            } else {
                                child
                            }
                        }
                    config.copy(
                        groups = config.groups.filterNot { it.id == groupId },
                        conversations = conversations,
                        defaultGroupId = config.defaultGroupId.takeIf { it != groupId },
                    )
                }
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::editor.isInitialized) editor.release()
    }
}

package com.worldmargin.autospark.app.ui.conversation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.worldmargin.autospark.app.config.ConfigStore
import com.worldmargin.autospark.app.config.ConversationSnapshot
import com.worldmargin.autospark.app.config.SparkConfig
import com.worldmargin.autospark.app.ui.common.UiKit
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_ACCENT
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_BG
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_DIM
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_TEXT
import com.worldmargin.autospark.app.ui.common.UiKit.bottomBar
import com.worldmargin.autospark.app.ui.common.UiKit.column
import com.worldmargin.autospark.app.ui.common.UiKit.divider
import com.worldmargin.autospark.app.ui.common.UiKit.dp
import com.worldmargin.autospark.app.ui.common.UiKit.flatRow
import com.worldmargin.autospark.app.ui.common.UiKit.iconButton
import com.worldmargin.autospark.app.ui.common.UiKit.padSystemBars
import com.worldmargin.autospark.app.ui.common.UiKit.rounded
import com.worldmargin.autospark.app.ui.common.UiKit.row
import com.worldmargin.autospark.app.ui.common.UiKit.scroll
import com.worldmargin.autospark.app.ui.common.UiKit.searchRow
import com.worldmargin.autospark.app.ui.common.UiKit.spacer
import com.worldmargin.autospark.app.ui.common.UiKit.text
import com.worldmargin.autospark.app.ui.common.UiKit.titleBar

/**
 * 会话选择页（单独一页，替代原来的多选弹窗）。
 *
 * - 搜索框按 昵称 / 备注 / **标签名** 过滤；
 * - 顶部标签条可以按标签筛选（再点一次取消）；
 * - 会话可多选，标题栏「确定」把选中 id 回传给调用方。
 *
 * 入参见 companion；出参 [EXTRA_RESULT]（ArrayList&lt;String&gt;）。
 */
class ConversationPickerActivity : Activity() {
    companion object {
        const val EXTRA_TITLE = "picker_title"
        const val EXTRA_SELECTED = "picker_selected"
        const val EXTRA_ONLY_SINGLE = "picker_only_single"
        const val EXTRA_RESULT = "picker_result"
        private const val SRC_NONE = "none:"
    }

    private lateinit var listHost: LinearLayout
    private lateinit var tagsHost: LinearLayout
    private lateinit var sourcesHost: LinearLayout
    private lateinit var confirmLabel: TextView
    private val selected = linkedSetOf<String>()
    private var query = ""
    private val tagFilters = linkedSetOf<String>()
    private var sourceFilter: String? = null
    private var onlySingle = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onlySingle = intent.getBooleanExtra(EXTRA_ONLY_SINGLE, true)
        intent.getStringArrayListExtra(EXTRA_SELECTED)?.forEach { if (it.isNotBlank()) selected += it }

        listHost = column()
        tagsHost = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sourcesHost = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        confirmLabel = text(confirmText(), 14f, COLOR_ACCENT, bold = true).apply {
            isClickable = true
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { finishWithResult() }
        }

        setContentView(
            column().apply {
                setBackgroundColor(COLOR_BG)
                padSystemBars()
                addView(
                    titleBar(intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "选择会话" }, action = confirmLabel) { finish() },
                )
                addView(divider())
                addView(
                    column().apply {
                        setPadding(dp(16), 0, dp(16), 0)
                        addView(searchRow("搜索昵称 / 备注", query) { value ->
                            query = value
                            refreshList()
                        })
                        if (ConfigStore.load(this@ConversationPickerActivity).tags.isNotEmpty()) {
                            addView(
                                HorizontalScrollView(this@ConversationPickerActivity).apply {
                                    isHorizontalScrollBarEnabled = false
                                    addView(tagsHost)
                                },
                            )
                        }
                        addView(
                            HorizontalScrollView(this@ConversationPickerActivity).apply {
                                isHorizontalScrollBarEnabled = false
                                addView(sourcesHost)
                            },
                        )
                    },
                )
                addView(divider())
                addView(scroll().apply {
                    addView(
                        column().apply {
                            setPadding(dp(16), dp(6), dp(16), dp(16))
                            addView(listHost)
                        },
                    )
                })
                addView(
                    bottomBar {
                        addView(
                            row().apply {
                                addView(
                                    iconButton(UiKit.Icons.SELECTION, "全选当前结果", compact = true) {
                                        selected += visible().map { it.conversationId }
                                        refreshList()
                                        refreshChips()
                                        confirmLabel.text = confirmText()
                                    },
                                )
                                addView(spacer())
                                addView(
                                    iconButton(UiKit.Icons.CLEAR_ALL, "清空", compact = true) {
                                        selected.clear()
                                        refreshList()
                                        refreshChips()
                                        confirmLabel.text = confirmText()
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
        refreshChips()
        refreshList()
    }

    private fun confirmText(): String = "确定 ${selected.size}"

    /** 当前搜索 / 标签 / 来源条件下可见的会话（已隐藏的永远不出现）。 */
    private fun visible(): List<ConversationSnapshot> {
        val config = ConfigStore.load(this)
        // 标签可能在别的页面被删了：筛选项跟着失效，避免列表被筛成空
        tagFilters.removeAll { id -> config.tag(id) == null }
        val needle = query.trim()
        val rows =
            config.snapshots
                .filter { !it.isHidden && (!onlySingle || !it.isGroup) }
                .filter { snapshot ->
                    val tags = config.tagsOf(snapshot.conversationId)
                    // 多选标签取并集：命中任意一个即可
                    if (tagFilters.isNotEmpty() && tags.none { it.id in tagFilters }) return@filter false
                    if (sourceFilter != null && (config.inheritKey(snapshot.conversationId) ?: SRC_NONE) != sourceFilter) {
                        return@filter false
                    }
                    if (needle.isEmpty()) return@filter true
                    val haystack =
                        buildString {
                            append(snapshot.displayName).append(' ').append(snapshot.remark).append(' ').append(snapshot.name)
                        }
                    haystack.contains(needle, ignoreCase = true)
                }
        // 按标签筛选时：命中的所选标签越多越靠前，同分保持原顺序
        return if (tagFilters.isEmpty()) {
            rows
        } else {
            rows.sortedByDescending { snapshot -> config.tagsOf(snapshot.conversationId).count { it.id in tagFilters } }
        }
    }

    private fun refreshList() {
        listHost.removeAllViews()
        confirmLabel.text = confirmText()
        val rows = visible()
        if (rows.isEmpty()) {
            listHost.addView(text("没有符合条件的会话（试试换个关键字或标签）", 13f, COLOR_DIM))
            return
        }
        listHost.addView(
            text("共 ${rows.size} 个会话，点行多选；标签在“标签”页维护", 12f, COLOR_DIM).apply {
                setPadding(0, dp(4), 0, dp(4))
            },
        )
        val config = ConfigStore.load(this)
        rows.forEach { snapshot ->
            listHost.addView(rowFor(config, snapshot))
        }
    }

    private fun rowFor(
        config: SparkConfig,
        snapshot: ConversationSnapshot,
    ): View {
        val id = snapshot.conversationId
        val check =
            CheckBox(this).apply {
                isChecked = id in selected
                isClickable = false
                isFocusable = false
            }
        return flatRow().apply {
            addView(check)
            addView(
                column().apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(text(snapshot.displayName.ifBlank { "(无名)" }, 15f, COLOR_TEXT, bold = true))
                    val tags = config.tagsOf(id)
                    if (tags.isNotEmpty()) {
                        addView(
                            row().apply {
                                tags.forEach { tag -> addView(tagChip(tag.name, tag.color)) }
                            },
                        )
                    }
                    addView(text(stateLine(snapshot), 12f, COLOR_DIM))
                },
            )
            isClickable = true
            setOnClickListener {
                if (id in selected) selected.remove(id) else selected += id
                check.isChecked = id in selected
                confirmLabel.text = confirmText()
            }
        }
    }

    private fun stateLine(snapshot: ConversationSnapshot): String =
        buildString {
            append(if (snapshot.isGroup) "群聊" else "单聊")
            if (snapshot.hasSpark) append(" · 火花 ").append(snapshot.days).append(" 天")
            if (snapshot.stateText.isNotBlank()) append(" · ").append(snapshot.stateText)
        }

    private fun tagChip(
        name: String,
        color: Int,
    ): View =
        text(name, 11f, SparkConfig.tagColor(color)).apply {
            background = rounded(0x1A000000, 8)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(4) }
        }

    /** 两行标记筛选：标签（彩色）+ 继承来源（灰）。 */
    private fun refreshChips() {
        val config = ConfigStore.load(this)
        tagsHost.removeAllViews()
        if (config.tags.isNotEmpty()) {
            tagsHost.addView(chip("全部", tagFilters.isEmpty()) {
                tagFilters.clear()
                refreshChips()
                refreshList()
            })
            config.tags.forEach { tag ->
                val active = tag.id in tagFilters
                tagsHost.addView(
                    chip(tag.name, active, if (active) null else SparkConfig.tagColor(tag.color)) {
                        // 可多选：再点一次取消这一个，其余保留
                        if (active) tagFilters.remove(tag.id) else tagFilters.add(tag.id)
                        refreshChips()
                        refreshList()
                    },
                )
            }
        }
        sourcesHost.removeAllViews()
        val sources =
            config.snapshots
                .filter { !it.isHidden && (!onlySingle || !it.isGroup) }
                .map { config.inheritKey(it.conversationId) }
                .distinct()
        if (sources.size > 1) {
            sourcesHost.addView(chip("全部来源", sourceFilter == null) {
                sourceFilter = null
                refreshChips()
                refreshList()
            })
            sources.forEach { key ->
                val value = key ?: SRC_NONE
                val active = sourceFilter == value
                sourcesHost.addView(chip(config.inheritLabel(key), active) {
                    sourceFilter = if (active) null else value
                    refreshChips()
                    refreshList()
                })
            }
        }
    }

    private fun chip(
        label: String,
        active: Boolean,
        color: Int? = null,
        onClick: () -> Unit,
    ): View =
        text(label, 12f, if (active) COLOR_ACCENT else (color ?: COLOR_DIM), bold = active).apply {
            background = rounded(if (active) 0x333B7CFF else 0x0A000000, 12)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginEnd = dp(6)
                    topMargin = dp(8)
                    bottomMargin = dp(6)
                }
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun finishWithResult() {
        setResult(
            RESULT_OK,
            Intent().putStringArrayListExtra(EXTRA_RESULT, ArrayList(selected)),
        )
        finish()
    }
}

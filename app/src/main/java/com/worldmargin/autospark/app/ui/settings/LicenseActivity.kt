// SPDX-License-Identifier: SFAL-1.0
// Copyright (c) 2026 WorldMargin. See LICENSE.SPARKFLAME.md and NOTICE.

package com.worldmargin.autospark.app.ui.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_CARD
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_DIM
import com.worldmargin.autospark.app.ui.common.UiKit.COLOR_TEXT
import com.worldmargin.autospark.app.ui.common.UiKit.Icons.CHEVRON_RIGHT
import com.worldmargin.autospark.app.ui.common.UiKit.column
import com.worldmargin.autospark.app.ui.common.UiKit.divider
import com.worldmargin.autospark.app.ui.common.UiKit.dp
import com.worldmargin.autospark.app.ui.common.UiKit.icon
import com.worldmargin.autospark.app.ui.common.UiKit.padSystemBars
import com.worldmargin.autospark.app.ui.common.UiKit.rounded
import com.worldmargin.autospark.app.ui.common.UiKit.scroll
import com.worldmargin.autospark.app.ui.common.UiKit.text
import com.worldmargin.autospark.app.ui.common.UiKit.titleBar

/**
 * 开源许可证入口页：纵向圆角按钮列表，点击跳转到 [LicenseDetailActivity] 整页预览。
 *
 * - NOTICE：版权声明、上游来源、fork 署名要求
 * - Apache License 2.0：历史代码（v1.6.2 及之前）
 * - SFAL-1.0：新增代码（v1.6.3 起）
 */
class LicenseActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val list = column().apply { setPadding(dp(16), dp(12), dp(16), dp(16)) }
        LICENSES.forEach { (label, desc, asset) ->
            list.addView(
                rowFor(label, desc).apply {
                    setOnClickListener {
                        startActivity(
                            Intent(this@LicenseActivity, LicenseDetailActivity::class.java)
                                .putExtra(LicenseDetailActivity.EXTRA_TITLE, label)
                                .putExtra(LicenseDetailActivity.EXTRA_ASSET, asset),
                        )
                    }
                },
            )
        }

        setContentView(
            column().apply {
                setBackgroundColor(com.worldmargin.autospark.app.ui.common.UiKit.COLOR_BG)
                padSystemBars()
                addView(titleBar("开源许可证") { finish() })
                addView(divider())
                addView(scroll().apply { addView(list) })
            },
        )
    }

    private fun rowFor(
        label: String,
        desc: String,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(COLOR_CARD, 10)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(6)
                }
            addView(
                column().apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(text(label, 16f, COLOR_TEXT, bold = true))
                    addView(text(desc, 12f, COLOR_DIM).apply { setPadding(0, dp(3), 0, 0) })
                },
            )
            addView(icon(CHEVRON_RIGHT, 16f, COLOR_DIM))
            isClickable = true
        }

    companion object {
        /** 三元组：(标题, 说明, assets 文件名)。 */
        val LICENSES =
            listOf(
                Triple("声明与署名", "版权、上游来源与 fork 署名要求", "NOTICE"),
                Triple("Apache License 2.0", "历史代码（v1.6.2 及之前）", "LICENSE"),
                Triple("SFAL-1.0", "新增代码（v1.6.3 起）· SparkFlame Attribution License", "LICENSE.SPARKFLAME.md"),
            )
    }
}

// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import android.app.Activity
import android.content.Intent
import android.widget.TextView

internal enum class SampleTab {
    HOME,
    CHAT,
    AGENT,
    WORKBENCH
}

internal fun Activity.bindSampleNavigation(active: SampleTab) {
    val destinations = listOf(
        Triple(R.id.navHome, SampleTab.HOME, HomeActivity::class.java),
        Triple(R.id.navChat, SampleTab.CHAT, MainActivity::class.java),
        Triple(R.id.navAgent, SampleTab.AGENT, AgentHouseActivity::class.java),
        Triple(R.id.navWorkbench, SampleTab.WORKBENCH, ProductCenterActivity::class.java)
    )
    destinations.forEach { (viewId, tab, activityClass) ->
        findViewById<TextView?>(viewId)?.apply {
            val selected = tab == active
            isSelected = selected
            setTextColor(getColor(if (selected) R.color.primary else R.color.textSecondary))
            background = if (selected) {
                getDrawable(R.drawable.bg_nav_item_active)
            } else {
                null
            }
            compoundDrawablesRelative.forEach { drawable ->
                drawable?.mutate()?.setTint(
                    getColor(if (selected) R.color.primary else R.color.textSecondary)
                )
            }
            setOnClickListener {
                if (selected) return@setOnClickListener
                startActivity(
                    Intent(this@bindSampleNavigation, activityClass).apply {
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        if (tab == SampleTab.WORKBENCH) {
                            putExtra(ProductCenterActivity.EXTRA_SECTION, "workbench")
                        }
                    }
                )
            }
        }
    }
}

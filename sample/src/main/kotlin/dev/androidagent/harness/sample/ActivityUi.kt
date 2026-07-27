// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.widget.Button

internal fun Activity.applySampleInsets(root: View) {
    val baseLeft = root.paddingLeft
    val baseTop = root.paddingTop
    val baseRight = root.paddingRight
    val baseBottom = root.paddingBottom
    root.setOnApplyWindowInsetsListener { view, insets ->
        val insetLeft: Int
        val insetTop: Int
        val insetRight: Int
        val insetBottom: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val system = insets.getInsets(WindowInsets.Type.systemBars())
            insetLeft = system.left
            insetTop = system.top
            insetRight = system.right
            insetBottom = system.bottom
        } else {
            @Suppress("DEPRECATION")
            insetLeft = insets.systemWindowInsetLeft
            @Suppress("DEPRECATION")
            insetTop = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            insetRight = insets.systemWindowInsetRight
            @Suppress("DEPRECATION")
            insetBottom = insets.systemWindowInsetBottom
        }
        val imeBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.ime()).bottom
        } else {
            0
        }
        view.setPadding(
            baseLeft + insetLeft,
            baseTop + insetTop,
            baseRight + insetRight,
            baseBottom + maxOf(insetBottom, imeBottom)
        )
        insets
    }
    root.requestApplyInsets()
}

internal fun Button.removeClippedShadow() {
    elevation = 0f
    translationZ = 0f
    stateListAnimator = null
    isAllCaps = false
}

internal fun Activity.dp(value: Int): Int {
    return (value * resources.displayMetrics.density).toInt()
}

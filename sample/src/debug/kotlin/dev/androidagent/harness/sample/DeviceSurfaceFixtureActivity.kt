// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import android.app.Activity
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.LinearLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class DeviceSurfaceFixtureActivity : Activity() {
    lateinit var editor: ClipboardFallbackEditText
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gestureDownCount.set(0)
        gestureStarted = CountDownLatch(1)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(
            GestureTargetView(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        editor = ClipboardFallbackEditText().apply {
            contentDescription = EDITOR_LABEL
            hint = EDITOR_LABEL
            minHeight = 180
        }
        root.addView(
            editor,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(root)
    }

    inner class GestureTargetView : View(this) {
        init {
            contentDescription = GESTURE_LABEL
            isClickable = false
            isFocusable = false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                gestureDownCount.incrementAndGet()
                gestureStarted.countDown()
            }
            return true
        }
    }

    inner class ClipboardFallbackEditText : EditText(this) {
        val pasteActionCount = AtomicInteger()

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                return false
            }
            if (action == AccessibilityNodeInfo.ACTION_PASTE) {
                pasteActionCount.incrementAndGet()
            }
            return super.performAccessibilityAction(action, arguments)
        }
    }

    companion object {
        const val GESTURE_LABEL = "Harness gesture target"
        const val EDITOR_LABEL = "Harness clipboard editor"

        val gestureDownCount = AtomicInteger()

        @Volatile
        var gestureStarted = CountDownLatch(1)
    }
}

// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.sample

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val input = EditText(this).apply {
            hint = "Public sample text"
            setText("android")
        }
        val output = TextView(this).apply {
            text = "Tap Run to execute the local deterministic provider → tool → provider loop."
            setTextIsSelectable(true)
            setPadding(0, padding, 0, 0)
        }
        val run = Button(this).apply {
            text = "Run deterministic harness"
            setOnClickListener {
                val value = input.text.toString().ifBlank { "android" }
                val result = SampleAgent.run(value)
                val transcript = result.session.messages.joinToString("\n") { message ->
                    "${message.role}: ${message.content}"
                }
                output.text = buildString {
                    append(result.output)
                    append("\n\nProvider steps: ${result.providerSteps}")
                    append("\n\nTranscript\n")
                    append(transcript)
                }
            }
        }

        content.addView(
            TextView(this).apply {
                text = "Android Agent Harness M0"
                textSize = 24f
            },
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        content.addView(input)
        content.addView(run)
        content.addView(output)

        setContentView(
            ScrollView(this).apply { addView(content) }
        )
    }
}

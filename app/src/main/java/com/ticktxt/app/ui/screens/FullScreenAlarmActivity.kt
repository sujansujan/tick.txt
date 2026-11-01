package com.ticktxt.app.ui.screens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ticktxt.app.ui.theme.LocalTickPalette
import com.ticktxt.app.ui.theme.TickTxtTheme

/**
 * Full-screen lock-screen alarm surface (`ReminderType.ALARM`).
 * Dry, high-contrast monospace display with 1-tap quick snoozes, dismiss, and mark done.
 */
class FullScreenAlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val taskTitle = intent.getStringExtra("extra_task_title") ?: "scheduled task"
        val remSpec = intent.getStringExtra("extra_raw_spec") ?: "0m/a"

        setContent {
            TickTxtTheme {
                val palette = LocalTickPalette.current
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(palette.bg)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "tick.txt // alarm firing",
                        color = palette.priorityA,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )

                    Column {
                        Text(
                            text = taskTitle,
                            color = palette.fg,
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "rem:$remSpec",
                            color = palette.reminder,
                            fontSize = 14.sp
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "snooze (does not change due date):",
                            color = palette.fgMuted,
                            fontSize = 12.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf("5m", "15m", "1h", "tomorrow").forEach { preset ->
                                Text(
                                    text = "[+$preset]",
                                    color = palette.project,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    modifier = Modifier.clickable { finish() }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "[dismiss]",
                                color = palette.fgMuted,
                                fontSize = 16.sp,
                                modifier = Modifier.clickable { finish() }
                            )
                            Text(
                                text = "[x mark done]",
                                color = palette.reminder,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                modifier = Modifier.clickable { finish() }
                            )
                        }
                    }
                }
            }
        }
    }
}

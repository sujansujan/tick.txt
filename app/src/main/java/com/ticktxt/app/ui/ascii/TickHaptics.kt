package com.ticktxt.app.ui.ascii

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Tactile, lightweight Android haptic feedback tailored to `tick.txt`'s ASCII controls:
 * - `tap()`       : crisp clock-tick detent for tabs, filters, option pills, and quick chips
 * - `toggle()`    : clean keyboard-tap for checkboxes and toggles
 * - `confirm()`   : rewarding confirmation pulse (API 30+ CONFIRM) for save / add / check-off
 * - `longPress()` : heavier pulse when opening inline quick-triage or committing a swipe
 * - `reject()`    : distinct double-tap / reject pulse for delete and dismissal
 */
@Immutable
class TickHaptics(private val view: View) {

    fun tap() {
        runCatching {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    fun toggle() {
        runCatching {
            val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                HapticFeedbackConstants.TEXT_HANDLE_MOVE
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            }
            view.performHapticFeedback(constant)
        }
    }

    fun confirm() {
        runCatching {
            val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            }
            view.performHapticFeedback(constant)
        }
    }

    fun longPress() {
        runCatching {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    fun reject() {
        runCatching {
            val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.REJECT
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
            view.performHapticFeedback(constant)
        }
    }
}

@Composable
fun rememberTickHaptics(): TickHaptics {
    val view = LocalView.current
    return remember(view) { TickHaptics(view) }
}

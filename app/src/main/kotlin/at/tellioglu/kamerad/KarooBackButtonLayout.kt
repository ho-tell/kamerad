package at.tellioglu.kamerad

import android.content.Context
import android.view.KeyEvent
import android.widget.FrameLayout

/**
 * The Karoo's lower-left hardware button is its "back" button, but it sends KEY_SELECT (DPAD_CENTER).
 * Handled before the IME stage: later, Android swallows the first press to leave touch mode and
 * Compose would treat it as a click on the focused button.
 */
class KarooBackButtonLayout(context: Context, private val onBack: () -> Unit) : FrameLayout(context) {
    init {
        // Pre-IME key dispatch only follows the focus path, so keep focus in this layout
        isFocusableInTouchMode = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!hasFocus()) requestFocus()
    }

    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (event.action == KeyEvent.ACTION_UP) onBack()
            return true
        }
        return super.dispatchKeyEventPreIme(event)
    }
}

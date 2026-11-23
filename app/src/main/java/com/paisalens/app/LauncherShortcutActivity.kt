package com.paisalens.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

internal enum class LauncherShortcut(val action: String) {
    ADD_EXPENSE("com.paisalens.app.shortcut.ADD_EXPENSE"),
    SYNC_SMS("com.paisalens.app.shortcut.SYNC_SMS"),
    BILLS_AND_DUES("com.paisalens.app.shortcut.BILLS_AND_DUES"),
    REMINDERS("com.paisalens.app.shortcut.REMINDERS"),
    ;

    companion object {
        fun fromAction(action: String?): LauncherShortcut? = entries.firstOrNull { it.action == action }
    }
}

/** A new object distinguishes two consecutive launches of the same shortcut. */
internal class LauncherShortcutRequest(val shortcut: LauncherShortcut)

class LauncherShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val shortcut = LauncherShortcut.fromAction(intent.action) ?: return
            pending = LauncherShortcutRequest(shortcut)
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(ACTION_HANDOFF)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        } finally {
            finish()
        }
    }

    companion object {
        private const val ACTION_HANDOFF = "com.paisalens.app.shortcut.HANDOFF"

        // Both activities run on the main thread. Only the non-exported entry point
        // can queue an action, so an external intent to MainActivity cannot start a scan.
        private var pending: LauncherShortcutRequest? = null

        internal fun takePendingRequest(intent: Intent?): LauncherShortcutRequest? =
            if (intent?.action == ACTION_HANDOFF) pending.also { pending = null } else null
    }
}

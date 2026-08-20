package com.paisalens.app.widget

import android.content.Context

/** Current-month spending snapshot. Kept under the original class name for existing widgets. */
class PaisaLensWidgetProvider : PaisaLensBaseWidgetProvider(PaisaLensWidgetKind.MONTHLY_SPENDING) {
    companion object {
        /** Refreshes every PaisaLens widget variant from one local-only database snapshot. */
        fun updateAll(context: Context) {
            PaisaLensWidgetCoordinator.updateAll(context)
        }

        /** Coalesces rapid ledger/theme changes and renders widgets away from the UI thread. */
        fun scheduleUpdateAll(context: Context, delayMillis: Long = 0L) {
            PaisaLensWidgetCoordinator.scheduleUpdateAll(context, delayMillis)
        }
    }
}

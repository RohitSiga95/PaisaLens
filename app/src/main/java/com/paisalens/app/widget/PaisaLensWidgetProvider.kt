package com.paisalens.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.paisalens.app.MainActivity
import com.paisalens.app.R
import com.paisalens.app.data.local.PaisaLensDatabase
import com.paisalens.app.data.local.UserPreferences
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.AppThemeStyle
import com.paisalens.app.data.model.buildEffectiveExpenseTransactions
import com.paisalens.app.data.model.transactionIdsAppliedAsExpenseOffsets
import com.paisalens.app.ui.theme.colorSchemeFor
import com.paisalens.app.ui.theme.gradientBackgroundColorsFor
import java.text.NumberFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Currency
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class PaisaLensWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        updateExecutor.execute {
            try {
                manager.updateAppWidget(appWidgetIds, createViews(appContext))
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, PaisaLensWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isNotEmpty()) {
                val views = createViews(context)
                manager.updateAppWidget(ids, views)
            }
        }

        /** Coalesces rapid Theme Studio taps and keeps ledger/widget rendering off the UI thread. */
        @Synchronized
        fun scheduleUpdateAll(context: Context, delayMillis: Long = 0L) {
            val appContext = context.applicationContext
            pendingUpdate?.cancel(false)
            pendingUpdate = updateExecutor.schedule(
                { runCatching { updateAll(appContext) } },
                delayMillis,
                TimeUnit.MILLISECONDS,
            )
        }

        private fun createViews(context: Context): RemoteViews {
            val preferences = UserPreferences(context)
            val (transactions, transactionLinks, expenseSplits) = PaisaLensDatabase(context).use { database ->
                Triple(database.getTransactions(), database.getTransactionLinks(), database.getExpenseSplits())
            }
            val currentMonth = YearMonth.now()
            val zone = ZoneId.systemDefault()
            val effectiveExpenses = buildEffectiveExpenseTransactions(transactions, transactionLinks, expenseSplits)
            val appliedOffsets = transactionIdsAppliedAsExpenseOffsets(transactions, transactionLinks)
            val adjustedExpenses = effectiveExpenses.filter {
                YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zone)) == currentMonth
            }.sumOf { it.amountMinor }
            val unlinkedRefunds = transactions.filter {
                it.type == TransactionType.REFUND &&
                    it.reviewStatus == ReviewStatus.CONFIRMED &&
                    it.id !in appliedOffsets &&
                    YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zone)) == currentMonth
            }.sumOf { it.amountMinor }
            val spend = (adjustedExpenses - unlinkedRefunds).coerceAtLeast(0)
            val reviewCount = transactions.count { it.reviewStatus == ReviewStatus.NEEDS_REVIEW }
            val showAmount = preferences.widgetAmountsVisible && !preferences.appLockEnabled
            val amountText = if (showAmount) formatMoney(spend, preferences.baseCurrency) else "Amounts hidden"
            val theme = preferences.themeConfiguration.value
            val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            val isDark = theme.isDark(systemDark)
            val colors = colorSchemeFor(theme, isDark)
            val gradientStops = gradientBackgroundColorsFor(theme.palette, isDark)
            val widgetForeground = if (theme.style == AppThemeStyle.GRADIENT) {
                colors.onBackground
            } else {
                colors.onSurface
            }
            val detail = when {
                reviewCount > 0 -> "$reviewCount transaction${if (reviewCount == 1) "" else "s"} need review"
                transactions.isEmpty() -> "Open PaisaLens to add your first transaction"
                else -> "Tap for analytics and recent activity"
            }
            return RemoteViews(context.packageName, R.layout.paisalens_widget).apply {
                setImageViewBitmap(
                    R.id.widget_background_image,
                    createBackgroundBitmap(
                        style = theme.style,
                        gradientStartArgb = gradientStops.first().toArgb(),
                        gradientEndArgb = gradientStops.last().toArgb(),
                        surfaceArgb = colors.surface.toArgb(),
                    ),
                )
                setTextViewText(R.id.widget_amount, amountText)
                setTextViewText(R.id.widget_detail, detail)
                setTextColor(R.id.widget_title, widgetForeground.toArgb())
                setTextColor(R.id.widget_amount, widgetForeground.toArgb())
                setTextColor(R.id.widget_detail, widgetForeground.toArgb())
                val intent = Intent(context, MainActivity::class.java)
                val pending = PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                setOnClickPendingIntent(R.id.widget_root, pending)
            }
        }

        private fun formatMoney(amountMinor: Long, currencyCode: String): String = runCatching {
            NumberFormat.getCurrencyInstance(Locale.Builder().setLanguage("en").setRegion("IN").build()).apply {
                currency = Currency.getInstance(currencyCode)
                maximumFractionDigits = 0
            }.format(amountMinor / 100.0)
        }.getOrElse { "₹%,.0f".format(amountMinor / 100.0) }

        private fun createBackgroundBitmap(
            style: AppThemeStyle,
            gradientStartArgb: Int,
            gradientEndArgb: Int,
            surfaceArgb: Int,
        ): Bitmap {
            val bitmap = Bitmap.createBitmap(WIDGET_BACKGROUND_WIDTH, WIDGET_BACKGROUND_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = if (style == AppThemeStyle.GRADIENT) {
                LinearGradient(
                    0f,
                    0f,
                    WIDGET_BACKGROUND_WIDTH.toFloat(),
                    WIDGET_BACKGROUND_HEIGHT.toFloat(),
                    gradientStartArgb,
                    gradientEndArgb,
                    Shader.TileMode.CLAMP,
                )
            } else {
                null
            }
            paint.color = if (style == AppThemeStyle.AMOLED) android.graphics.Color.BLACK else surfaceArgb
            canvas.drawRoundRect(
                0f,
                0f,
                WIDGET_BACKGROUND_WIDTH.toFloat(),
                WIDGET_BACKGROUND_HEIGHT.toFloat(),
                WIDGET_CORNER_RADIUS,
                WIDGET_CORNER_RADIUS,
                paint,
            )
            return bitmap
        }

        private const val WIDGET_BACKGROUND_WIDTH = 360
        private const val WIDGET_BACKGROUND_HEIGHT = 220
        private const val WIDGET_CORNER_RADIUS = 48f
        private val updateExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "PaisaLens-widget").apply { isDaemon = true }
        }
        private var pendingUpdate: ScheduledFuture<*>? = null
    }
}

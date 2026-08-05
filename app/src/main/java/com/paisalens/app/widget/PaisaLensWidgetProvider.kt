package com.paisalens.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.paisalens.app.MainActivity
import com.paisalens.app.R
import com.paisalens.app.data.local.PaisaLensDatabase
import com.paisalens.app.data.local.UserPreferences
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.TransactionType
import java.text.NumberFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Currency
import java.util.Locale

class PaisaLensWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { manager.updateAppWidget(it, createViews(context)) }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, PaisaLensWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isNotEmpty()) ids.forEach { manager.updateAppWidget(it, createViews(context)) }
        }

        private fun createViews(context: Context): RemoteViews {
            val preferences = UserPreferences(context)
            val transactions = PaisaLensDatabase(context).use { it.getTransactions() }
            val currentMonth = YearMonth.now()
            val zone = ZoneId.systemDefault()
            val spend = transactions.filter {
                it.type == TransactionType.EXPENSE &&
                    it.reviewStatus == ReviewStatus.CONFIRMED &&
                    YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zone)) == currentMonth
            }.sumOf { it.amountMinor }
            val reviewCount = transactions.count { it.reviewStatus == ReviewStatus.NEEDS_REVIEW }
            val showAmount = preferences.widgetAmountsVisible && !preferences.appLockEnabled
            val amountText = if (showAmount) formatMoney(spend, preferences.baseCurrency) else "Amounts hidden"
            val detail = when {
                reviewCount > 0 -> "$reviewCount transaction${if (reviewCount == 1) "" else "s"} need review"
                transactions.isEmpty() -> "Open PaisaLens to add your first transaction"
                else -> "Tap for analytics and recent activity"
            }
            return RemoteViews(context.packageName, R.layout.paisalens_widget).apply {
                setTextViewText(R.id.widget_amount, amountText)
                setTextViewText(R.id.widget_detail, detail)
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
    }
}

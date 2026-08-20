package com.paisalens.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.paisalens.app.MainActivity
import com.paisalens.app.R
import com.paisalens.app.data.model.AppThemeStyle
import com.paisalens.app.data.model.DueStatus
import com.paisalens.app.ui.theme.colorSchemeFor
import com.paisalens.app.ui.theme.gradientBackgroundColorsFor
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

internal object WidgetRemoteViewsFactory {
    private val indianLocale: Locale = Locale.Builder().setLanguage("en").setRegion("IN").build()
    private val monthFormatter = DateTimeFormatter.ofPattern("MMM yyyy", indianLocale)
    private val dueDateFormatter = DateTimeFormatter.ofPattern("d MMM", indianLocale)

    fun create(
        context: Context,
        kind: PaisaLensWidgetKind,
        snapshot: WidgetLedgerSnapshot,
        settings: WidgetRenderSettings,
        appWidgetId: Int,
        options: Bundle,
    ): RemoteViews {
        val privacy = widgetPrivacyState(
            appLockEnabled = settings.appLockEnabled,
            amountsVisible = settings.amountsVisible,
        )
        val theme = settings.theme
        val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val isDark = theme.isDark(systemDark)
        val scheme = colorSchemeFor(theme, isDark)
        val gradientStops = gradientBackgroundColorsFor(theme.palette, isDark)
        val foreground = if (theme.style == AppThemeStyle.GRADIENT) scheme.onBackground else scheme.onSurface
        val secondary = if (theme.style == AppThemeStyle.GRADIENT) {
            withAlpha(foreground.toArgb(), 0xC7)
        } else {
            scheme.onSurfaceVariant.toArgb()
        }
        val accent = when (kind) {
            PaisaLensWidgetKind.MONTHLY_SPENDING -> scheme.primary.toArgb()
            PaisaLensWidgetKind.CATEGORY_BREAKDOWN -> scheme.secondary.toArgb()
            PaisaLensWidgetKind.DUE_BILLS -> scheme.tertiary.toArgb()
            PaisaLensWidgetKind.CREDIT_CARD_BILLS -> scheme.primary.toArgb()
        }
        val colors = WidgetColors(
            foreground = foreground.toArgb(),
            secondary = secondary,
            accent = accent,
            alert = scheme.error.toArgb(),
            track = withAlpha(foreground.toArgb(), if (isDark) 0x35 else 0x25),
            surface = scheme.surface.toArgb(),
            gradientStart = gradientStops.first().toArgb(),
            gradientEnd = gradientStops.last().toArgb(),
        )
        val minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140)
        val maxRows = when {
            minHeightDp >= 180 -> 3
            minHeightDp >= 140 -> 2
            else -> 1
        }
        val views = when (kind) {
            PaisaLensWidgetKind.MONTHLY_SPENDING -> monthlySpendingViews(
                context = context,
                snapshot = snapshot,
                privacy = privacy,
                currencyCode = settings.currencyCode,
                colors = colors,
                compact = minHeightDp < 125,
            )
            PaisaLensWidgetKind.CATEGORY_BREAKDOWN -> categoryBreakdownViews(
                context = context,
                snapshot = snapshot,
                privacy = privacy,
                currencyCode = settings.currencyCode,
                colors = colors,
                maxRows = maxRows,
            )
            PaisaLensWidgetKind.DUE_BILLS -> dueBillsViews(
                context = context,
                snapshot = snapshot,
                privacy = privacy,
                currencyCode = settings.currencyCode,
                colors = colors,
                maxRows = maxRows,
            )
            PaisaLensWidgetKind.CREDIT_CARD_BILLS -> creditCardBillsViews(
                context = context,
                snapshot = snapshot,
                privacy = privacy,
                currencyCode = settings.currencyCode,
                colors = colors,
                maxRows = maxRows,
            )
        }
        views.setImageViewBitmap(
            R.id.widget_background_image,
            createBackgroundBitmap(
                style = theme.style,
                surfaceArgb = colors.surface,
                gradientStartArgb = colors.gradientStart,
                gradientEndArgb = colors.gradientEnd,
                accentArgb = colors.accent,
                secondaryAccentArgb = when (kind) {
                    PaisaLensWidgetKind.MONTHLY_SPENDING -> theme.palette.secondaryArgb.toInt()
                    PaisaLensWidgetKind.CATEGORY_BREAKDOWN -> theme.palette.primaryArgb.toInt()
                    PaisaLensWidgetKind.DUE_BILLS -> theme.palette.secondaryArgb.toInt()
                    PaisaLensWidgetKind.CREDIT_CARD_BILLS -> theme.palette.tertiaryArgb.toInt()
                },
            ),
        )
        views.setOnClickPendingIntent(
            R.id.widget_root,
            PendingIntent.getActivity(
                context,
                kind.ordinal * 100_000 + appWidgetId,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(WIDGET_DESTINATION_EXTRA, kind.destination)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        return views
    }

    private fun monthlySpendingViews(
        context: Context,
        snapshot: WidgetLedgerSnapshot,
        privacy: WidgetPrivacyState,
        currencyCode: String,
        colors: WidgetColors,
        compact: Boolean,
    ): RemoteViews {
        val zoneId = ZoneId.systemDefault()
        val presentation = buildMonthlySpendingWidgetPresentation(
            transactions = snapshot.transactions,
            links = snapshot.links,
            splits = snapshot.splits,
            month = YearMonth.now(zoneId),
            zoneId = zoneId,
        )
        val detail = when {
            !privacy.showDetails -> "Unlock PaisaLens to view this widget"
            presentation.transactionCount == 0 -> "No confirmed spending this month"
            presentation.reviewCount > 0 ->
                "${presentation.transactionCount} expenses · ${presentation.reviewCount} need attention"
            else -> "${presentation.transactionCount} confirmed expense${pluralSuffix(presentation.transactionCount)}"
        }
        val amount = when {
            !privacy.showDetails -> "Locked"
            privacy.showAmounts -> formatMoney(presentation.spendMinor, currencyCode)
            else -> "Amounts hidden"
        }
        val contentDescription = when {
            !privacy.showDetails -> "PaisaLens spending widget. Locked. Open PaisaLens to view."
            privacy.showAmounts ->
                "${presentation.month.format(monthFormatter)} spending, $amount. $detail. Open PaisaLens."
            else -> "${presentation.month.format(monthFormatter)} spending. Amounts hidden. $detail. Open PaisaLens."
        }
        return RemoteViews(context.packageName, R.layout.paisalens_widget).apply {
            setTextViewText(R.id.widget_title, "PaisaLens · ${presentation.month.format(monthFormatter)}")
            setTextViewText(R.id.widget_amount, amount)
            setTextViewText(R.id.widget_detail, detail)
            setViewVisibility(R.id.widget_detail, if (compact) View.GONE else View.VISIBLE)
            setTextColor(R.id.widget_title, colors.secondary)
            setTextColor(R.id.widget_amount, colors.foreground)
            setTextColor(R.id.widget_detail, colors.accent)
            setContentDescription(R.id.widget_root, contentDescription)
        }
    }

    private fun categoryBreakdownViews(
        context: Context,
        snapshot: WidgetLedgerSnapshot,
        privacy: WidgetPrivacyState,
        currencyCode: String,
        colors: WidgetColors,
        maxRows: Int,
    ): RemoteViews {
        val zoneId = ZoneId.systemDefault()
        val presentation = buildMonthlySpendingWidgetPresentation(
            transactions = snapshot.transactions,
            links = snapshot.links,
            splits = snapshot.splits,
            month = YearMonth.now(zoneId),
            zoneId = zoneId,
        )
        val rows = if (privacy.showDetails) presentation.categoryRows.take(maxRows) else emptyList()
        val summaryAmount = when {
            !privacy.showDetails -> "Locked"
            privacy.showAmounts -> formatMoney(presentation.spendMinor, currencyCode)
            else -> "Amounts hidden"
        }
        val emptyText = when {
            !privacy.showDetails -> "Unlock PaisaLens to view categories"
            presentation.categoryRows.isEmpty() -> "No category spending this month"
            else -> ""
        }
        val contentDescription = when {
            !privacy.showDetails -> "PaisaLens category breakdown widget. Locked. Open PaisaLens to view."
            privacy.showAmounts -> buildString {
                append("Spending breakdown for ${presentation.month.format(monthFormatter)}, $summaryAmount. ")
                rows.forEach { append("${it.label}, ${formatMoney(it.amountMinor, currencyCode)}. ") }
                append("Open PaisaLens.")
            }
            else -> "Spending breakdown for ${presentation.month.format(monthFormatter)}. Amounts hidden. Open PaisaLens."
        }
        return RemoteViews(context.packageName, R.layout.paisalens_category_widget).apply {
            setTextViewText(R.id.widget_title, "Spending breakdown")
            setTextViewText(R.id.widget_period, presentation.month.format(monthFormatter))
            setTextViewText(R.id.widget_summary_amount, summaryAmount)
            setTextViewText(R.id.widget_summary_label, "Current month")
            setTextViewText(R.id.widget_empty, emptyText)
            setViewVisibility(R.id.widget_empty, if (emptyText.isBlank()) View.GONE else View.VISIBLE)
            setTextColor(R.id.widget_title, colors.foreground)
            setTextColor(R.id.widget_period, colors.secondary)
            setTextColor(R.id.widget_summary_amount, colors.foreground)
            setTextColor(R.id.widget_summary_label, colors.secondary)
            setTextColor(R.id.widget_empty, colors.secondary)
            bindSpendingRows(this, rows, privacy, currencyCode, colors)
            setContentDescription(R.id.widget_root, contentDescription)
        }
    }

    private fun dueBillsViews(
        context: Context,
        snapshot: WidgetLedgerSnapshot,
        privacy: WidgetPrivacyState,
        currencyCode: String,
        colors: WidgetColors,
        maxRows: Int,
    ): RemoteViews {
        val today = LocalDate.now()
        val presentation = buildDueBillsWidgetPresentation(
            bills = snapshot.bills,
            transactions = snapshot.transactions,
            loans = snapshot.loans,
            commitments = snapshot.commitments,
            accounts = snapshot.accounts,
            today = today,
            zoneId = ZoneId.systemDefault(),
        )
        val rows = if (privacy.showDetails) presentation.rows.take(maxRows) else emptyList()
        val summary = when {
            !privacy.showDetails -> "Locked"
            privacy.showAmounts -> formatMoney(presentation.totalDueMinor, currencyCode)
            else -> "Amounts hidden"
        }
        val supporting = when {
            !privacy.showDetails -> "Unlock PaisaLens to view upcoming bills"
            presentation.rows.isEmpty() -> "Nothing due in the next 30 days"
            presentation.overdueCount > 0 ->
                "${presentation.rows.size} due · ${presentation.overdueCount} overdue"
            else -> "${presentation.rows.size} due in the next 30 days"
        }
        val contentDescription = when {
            !privacy.showDetails -> "PaisaLens due bills widget. Locked. Open PaisaLens to view."
            privacy.showAmounts -> buildString {
                append("Bills due in the next 30 days, $summary. ")
                rows.forEach { row ->
                    append("${row.title}, ${formatMoney(row.amountMinor, currencyCode)}, ${dueLabel(row.dueDate, today, row.status == DueStatus.OVERDUE)}. ")
                }
                append("Open PaisaLens.")
            }
            else -> "Bills due in the next 30 days. Amounts hidden. $supporting. Open PaisaLens."
        }
        return RemoteViews(context.packageName, R.layout.paisalens_due_bills_widget).apply {
            setTextViewText(R.id.widget_title, "Bills due")
            setTextViewText(R.id.widget_period, "Next 30 days")
            setTextViewText(R.id.widget_summary_amount, summary)
            setTextViewText(R.id.widget_summary_label, supporting)
            setTextColor(R.id.widget_title, colors.foreground)
            setTextColor(R.id.widget_period, colors.secondary)
            setTextColor(R.id.widget_summary_amount, colors.foreground)
            setTextColor(R.id.widget_summary_label, if (presentation.overdueCount > 0) colors.alert else colors.secondary)
            bindDueRows(this, rows, privacy, currencyCode, colors, today)
            setContentDescription(R.id.widget_root, contentDescription)
        }
    }

    private fun creditCardBillsViews(
        context: Context,
        snapshot: WidgetLedgerSnapshot,
        privacy: WidgetPrivacyState,
        currencyCode: String,
        colors: WidgetColors,
        maxRows: Int,
    ): RemoteViews {
        val today = LocalDate.now()
        val presentation = buildCreditCardBillsWidgetPresentation(
            bills = snapshot.creditCardBills,
            accounts = snapshot.accounts,
            today = today,
        )
        val rows = if (privacy.showDetails) presentation.rows.take(maxRows) else emptyList()
        val summary = when {
            !privacy.showDetails -> "Locked"
            privacy.showAmounts -> formatMoney(presentation.totalDueMinor, currencyCode)
            else -> "Amounts hidden"
        }
        val supporting = when {
            !privacy.showDetails -> "Unlock PaisaLens to view card bills"
            presentation.rows.isEmpty() -> "No unpaid card statements"
            else -> "${presentation.rows.size} card${pluralSuffix(presentation.rows.size)} with payment due"
        }
        val contentDescription = when {
            !privacy.showDetails -> "PaisaLens credit card bills widget. Locked. Open PaisaLens to view."
            privacy.showAmounts -> buildString {
                append("Credit card statements due, $summary. ")
                rows.forEach { row ->
                    append("${row.cardName}, ${formatMoney(row.amountMinor, currencyCode)}, ${dueLabel(row.dueDate, today, row.overdue)}. ")
                }
                append("Open PaisaLens.")
            }
            else -> "Credit card statements due. Amounts hidden. $supporting. Open PaisaLens."
        }
        return RemoteViews(context.packageName, R.layout.paisalens_card_bills_widget).apply {
            setTextViewText(R.id.widget_title, "Credit card bills")
            setTextViewText(R.id.widget_period, "Latest statements")
            setTextViewText(R.id.widget_summary_amount, summary)
            setTextViewText(R.id.widget_summary_label, supporting)
            setTextColor(R.id.widget_title, colors.foreground)
            setTextColor(R.id.widget_period, colors.secondary)
            setTextColor(R.id.widget_summary_amount, colors.foreground)
            setTextColor(R.id.widget_summary_label, colors.secondary)
            bindCardRows(this, rows, privacy, currencyCode, colors, today)
            setContentDescription(R.id.widget_root, contentDescription)
        }
    }

    private fun bindSpendingRows(
        views: RemoteViews,
        rows: List<SpendingWidgetRow>,
        privacy: WidgetPrivacyState,
        currencyCode: String,
        colors: WidgetColors,
    ) {
        val rowIds = intArrayOf(R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3)
        val labelIds = intArrayOf(R.id.widget_row_1_title, R.id.widget_row_2_title, R.id.widget_row_3_title)
        val amountIds = intArrayOf(R.id.widget_row_1_amount, R.id.widget_row_2_amount, R.id.widget_row_3_amount)
        val barIds = intArrayOf(R.id.widget_row_1_bar, R.id.widget_row_2_bar, R.id.widget_row_3_bar)
        val rowAccents = intArrayOf(colors.accent, rotateAccent(colors.accent, 1), rotateAccent(colors.accent, 2))
        rowIds.indices.forEach { index ->
            val row = rows.getOrNull(index)
            views.setViewVisibility(rowIds[index], if (row == null) View.GONE else View.VISIBLE)
            if (row != null) {
                views.setTextViewText(labelIds[index], row.label)
                views.setTextViewText(
                    amountIds[index],
                    if (privacy.showAmounts) formatMoney(row.amountMinor, currencyCode) else "Hidden",
                )
                views.setTextColor(labelIds[index], colors.foreground)
                views.setTextColor(amountIds[index], colors.secondary)
                views.setViewVisibility(barIds[index], if (privacy.showAmounts) View.VISIBLE else View.GONE)
                if (privacy.showAmounts) {
                    views.setImageViewBitmap(
                        barIds[index],
                        createProgressBitmap(row.progressBasisPoints, rowAccents[index], colors.track),
                    )
                }
            }
        }
    }

    private fun bindDueRows(
        views: RemoteViews,
        rows: List<DueBillWidgetRow>,
        privacy: WidgetPrivacyState,
        currencyCode: String,
        colors: WidgetColors,
        today: LocalDate,
    ) {
        val rowIds = intArrayOf(R.id.widget_bill_row_1, R.id.widget_bill_row_2, R.id.widget_bill_row_3)
        val titleIds = intArrayOf(R.id.widget_bill_row_1_title, R.id.widget_bill_row_2_title, R.id.widget_bill_row_3_title)
        val dueIds = intArrayOf(R.id.widget_bill_row_1_due, R.id.widget_bill_row_2_due, R.id.widget_bill_row_3_due)
        val amountIds = intArrayOf(R.id.widget_bill_row_1_amount, R.id.widget_bill_row_2_amount, R.id.widget_bill_row_3_amount)
        rowIds.indices.forEach { index ->
            val row = rows.getOrNull(index)
            views.setViewVisibility(rowIds[index], if (row == null) View.GONE else View.VISIBLE)
            if (row != null) {
                views.setTextViewText(titleIds[index], row.title)
                views.setTextViewText(dueIds[index], dueLabel(row.dueDate, today, row.status == DueStatus.OVERDUE))
                views.setTextViewText(
                    amountIds[index],
                    if (privacy.showAmounts) formatMoney(row.amountMinor, currencyCode) else "Hidden",
                )
                views.setTextColor(titleIds[index], colors.foreground)
                views.setTextColor(dueIds[index], if (row.status == DueStatus.OVERDUE) colors.alert else colors.secondary)
                views.setTextColor(amountIds[index], colors.secondary)
            }
        }
    }

    private fun bindCardRows(
        views: RemoteViews,
        rows: List<CreditCardBillWidgetRow>,
        privacy: WidgetPrivacyState,
        currencyCode: String,
        colors: WidgetColors,
        today: LocalDate,
    ) {
        val rowIds = intArrayOf(R.id.widget_card_row_1, R.id.widget_card_row_2, R.id.widget_card_row_3)
        val titleIds = intArrayOf(R.id.widget_card_row_1_title, R.id.widget_card_row_2_title, R.id.widget_card_row_3_title)
        val dueIds = intArrayOf(R.id.widget_card_row_1_due, R.id.widget_card_row_2_due, R.id.widget_card_row_3_due)
        val amountIds = intArrayOf(R.id.widget_card_row_1_amount, R.id.widget_card_row_2_amount, R.id.widget_card_row_3_amount)
        rowIds.indices.forEach { index ->
            val row = rows.getOrNull(index)
            views.setViewVisibility(rowIds[index], if (row == null) View.GONE else View.VISIBLE)
            if (row != null) {
                views.setTextViewText(titleIds[index], row.cardName)
                views.setTextViewText(dueIds[index], dueLabel(row.dueDate, today, row.overdue))
                views.setTextViewText(
                    amountIds[index],
                    if (privacy.showAmounts) formatMoney(row.amountMinor, currencyCode) else "Hidden",
                )
                views.setTextColor(titleIds[index], colors.foreground)
                views.setTextColor(dueIds[index], if (row.overdue) colors.alert else colors.secondary)
                views.setTextColor(amountIds[index], colors.secondary)
            }
        }
    }

    private fun dueLabel(dueDate: LocalDate, today: LocalDate, overdue: Boolean): String = when {
        overdue -> "Overdue · ${dueDate.format(dueDateFormatter)}"
        dueDate == today -> "Due today"
        dueDate == today.plusDays(1) -> "Due tomorrow"
        else -> "Due ${dueDate.format(dueDateFormatter)}"
    }

    private fun formatMoney(amountMinor: Long, currencyCode: String): String = runCatching {
        NumberFormat.getCurrencyInstance(indianLocale).apply {
            currency = Currency.getInstance(currencyCode)
            maximumFractionDigits = 0
        }.format(amountMinor / 100.0)
    }.getOrElse { "₹%,.0f".format(indianLocale, amountMinor / 100.0) }

    private fun pluralSuffix(count: Int): String = if (count == 1) "" else "s"

    private fun createBackgroundBitmap(
        style: AppThemeStyle,
        surfaceArgb: Int,
        gradientStartArgb: Int,
        gradientEndArgb: Int,
        accentArgb: Int,
        secondaryAccentArgb: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(BACKGROUND_WIDTH, BACKGROUND_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bounds = RectF(0f, 0f, BACKGROUND_WIDTH.toFloat(), BACKGROUND_HEIGHT.toFloat())
        val clip = Path().apply { addRoundRect(bounds, CORNER_RADIUS, CORNER_RADIUS, Path.Direction.CW) }
        canvas.clipPath(clip)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = if (style == AppThemeStyle.GRADIENT) {
            LinearGradient(
                0f,
                0f,
                BACKGROUND_WIDTH.toFloat(),
                BACKGROUND_HEIGHT.toFloat(),
                gradientStartArgb,
                gradientEndArgb,
                Shader.TileMode.CLAMP,
            )
        } else {
            null
        }
        paint.color = if (style == AppThemeStyle.AMOLED) android.graphics.Color.BLACK else surfaceArgb
        canvas.drawRect(bounds, paint)
        paint.shader = null
        paint.color = withAlpha(accentArgb, if (style == AppThemeStyle.GRADIENT) 0x38 else 0x28)
        canvas.drawCircle(BACKGROUND_WIDTH * 0.91f, BACKGROUND_HEIGHT * 0.02f, 118f, paint)
        paint.color = withAlpha(secondaryAccentArgb, if (style == AppThemeStyle.GRADIENT) 0x2C else 0x1E)
        canvas.drawCircle(BACKGROUND_WIDTH * 0.10f, BACKGROUND_HEIGHT * 1.05f, 102f, paint)
        return bitmap
    }

    private fun createProgressBitmap(
        progressBasisPoints: Int,
        accentArgb: Int,
        trackArgb: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(PROGRESS_WIDTH, PROGRESS_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = PROGRESS_HEIGHT / 2f
        paint.color = trackArgb
        canvas.drawRoundRect(0f, 0f, PROGRESS_WIDTH.toFloat(), PROGRESS_HEIGHT.toFloat(), radius, radius, paint)
        paint.color = accentArgb
        val progressWidth = (PROGRESS_WIDTH * progressBasisPoints.coerceIn(0, 10_000) / 10_000f)
        canvas.drawRoundRect(0f, 0f, progressWidth.coerceAtLeast(radius * 2), PROGRESS_HEIGHT.toFloat(), radius, radius, paint)
        return bitmap
    }

    private fun rotateAccent(color: Int, step: Int): Int {
        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF
        return when (step % 3) {
            1 -> android.graphics.Color.rgb(green, blue, red)
            2 -> android.graphics.Color.rgb(blue, red, green)
            else -> color
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private data class WidgetColors(
        val foreground: Int,
        val secondary: Int,
        val accent: Int,
        val alert: Int,
        val track: Int,
        val surface: Int,
        val gradientStart: Int,
        val gradientEnd: Int,
    )

    private const val BACKGROUND_WIDTH = 420
    private const val BACKGROUND_HEIGHT = 260
    private const val CORNER_RADIUS = 52f
    private const val PROGRESS_WIDTH = 600
    private const val PROGRESS_HEIGHT = 12
}

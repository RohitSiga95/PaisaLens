package com.paisalens.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import com.paisalens.app.PaisaLensApplication
import com.paisalens.app.data.local.PaisaLensDatabase
import com.paisalens.app.data.local.UserPreferences
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AppThemeConfiguration
import com.paisalens.app.data.model.BillReminder
import com.paisalens.app.data.model.CreditCardBill
import com.paisalens.app.data.model.ExpenseSplit
import com.paisalens.app.data.model.LoanAccount
import com.paisalens.app.data.model.PaymentCommitment
import com.paisalens.app.data.model.TransactionLink
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.ui.privacy.PrivacyModeRuntime
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal enum class PaisaLensWidgetKind(
    val providerClass: Class<out AppWidgetProvider>,
    val destination: String,
) {
    MONTHLY_SPENDING(PaisaLensWidgetProvider::class.java, WIDGET_DESTINATION_HOME),
    CATEGORY_BREAKDOWN(CategoryBreakdownWidgetProvider::class.java, WIDGET_DESTINATION_HOME),
    DUE_BILLS(DueBillsWidgetProvider::class.java, WIDGET_DESTINATION_DUE_BILLS),
    CREDIT_CARD_BILLS(CreditCardBillsWidgetProvider::class.java, WIDGET_DESTINATION_CARD_BILLS),
}

internal const val WIDGET_DESTINATION_EXTRA = "com.paisalens.app.extra.WIDGET_DESTINATION"
internal const val WIDGET_DESTINATION_HOME = "home"
internal const val WIDGET_DESTINATION_DUE_BILLS = "due_bills"
internal const val WIDGET_DESTINATION_CARD_BILLS = "card_bills"

internal data class WidgetLedgerSnapshot(
    val transactions: List<TransactionRecord>,
    val links: List<TransactionLink>,
    val splits: List<ExpenseSplit>,
    val bills: List<BillReminder>,
    val loans: List<LoanAccount>,
    val commitments: List<PaymentCommitment>,
    val creditCardBills: List<CreditCardBill>,
    val accounts: List<AccountProfile>,
)

internal data class WidgetRenderSettings(
    val theme: AppThemeConfiguration,
    val appLockEnabled: Boolean,
    val amountsVisible: Boolean,
    val currencyCode: String,
)

abstract class PaisaLensBaseWidgetProvider internal constructor(
    private val kind: PaisaLensWidgetKind,
) : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        PaisaLensWidgetCoordinator.execute {
            try {
                PaisaLensWidgetCoordinator.updateProvider(context.applicationContext, manager, appWidgetIds, kind)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        val pendingResult = goAsync()
        PaisaLensWidgetCoordinator.execute {
            try {
                PaisaLensWidgetCoordinator.updateProvider(
                    context = context.applicationContext,
                    manager = appWidgetManager,
                    appWidgetIds = intArrayOf(appWidgetId),
                    kind = kind,
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}

internal object PaisaLensWidgetCoordinator {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "PaisaLens-widget").apply { isDaemon = true }
    }
    private var pendingUpdate: ScheduledFuture<*>? = null

    fun execute(block: () -> Unit) {
        executor.execute(block)
    }

    fun updateProvider(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
        kind: PaisaLensWidgetKind,
    ) {
        if (appWidgetIds.isEmpty()) return
        val snapshot = loadSnapshot(context)
        val settings = loadSettings(context)
        appWidgetIds.forEach { appWidgetId ->
            manager.updateAppWidget(
                appWidgetId,
                WidgetRemoteViewsFactory.create(
                    context = context,
                    kind = kind,
                    snapshot = snapshot,
                    settings = settings,
                    appWidgetId = appWidgetId,
                    options = manager.getAppWidgetOptions(appWidgetId),
                ),
            )
        }
    }

    fun updateAll(context: Context) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val activeKinds = PaisaLensWidgetKind.entries.mapNotNull { kind ->
            val ids = manager.getAppWidgetIds(ComponentName(appContext, kind.providerClass))
            (kind to ids).takeIf { ids.isNotEmpty() }
        }
        if (activeKinds.isEmpty()) return
        val snapshot = loadSnapshot(appContext)
        val settings = loadSettings(appContext)
        activeKinds.forEach { (kind, ids) ->
            ids.forEach { appWidgetId ->
                manager.updateAppWidget(
                    appWidgetId,
                    WidgetRemoteViewsFactory.create(
                        context = appContext,
                        kind = kind,
                        snapshot = snapshot,
                        settings = settings,
                        appWidgetId = appWidgetId,
                        options = manager.getAppWidgetOptions(appWidgetId),
                    ),
                )
            }
        }
    }

    @Synchronized
    fun scheduleUpdateAll(context: Context, delayMillis: Long = 0L) {
        val appContext = context.applicationContext
        pendingUpdate?.cancel(false)
        pendingUpdate = executor.schedule(
            { runCatching { updateAll(appContext) } },
            delayMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun loadSnapshot(context: Context): WidgetLedgerSnapshot = PaisaLensDatabase(context).use { database ->
        WidgetLedgerSnapshot(
            transactions = database.getTransactions(),
            links = database.getTransactionLinks(),
            splits = database.getExpenseSplits(),
            bills = database.getBills(),
            loans = database.getLoans(),
            commitments = database.getPaymentCommitments(),
            creditCardBills = database.getCreditCardBills(),
            accounts = database.getAccounts(),
        )
    }

    private fun loadSettings(context: Context): WidgetRenderSettings {
        val preferences = (context.applicationContext as? PaisaLensApplication)?.preferences
            ?: UserPreferences(context)
        return preferences.let {
            WidgetRenderSettings(
                theme = it.themeConfiguration.value,
                appLockEnabled = it.appLockEnabled,
                amountsVisible = it.widgetAmountsVisible &&
                    !it.privacyModeConfiguration.value.defaultEnabled &&
                    !PrivacyModeRuntime.active,
                currencyCode = it.baseCurrency,
            )
        }
    }
}

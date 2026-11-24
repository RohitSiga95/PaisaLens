package com.paisalens.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Wifi
import com.paisalens.app.data.model.BillTimelineEntry
import com.paisalens.app.data.model.BillTimelineSource
import com.paisalens.app.data.model.UtilityBillKind

internal fun BillTimelineEntry.timelineIcon() = when (utilityKind) {
    UtilityBillKind.BROADBAND -> Icons.Rounded.Wifi
    UtilityBillKind.ELECTRICITY -> Icons.Rounded.Bolt
    UtilityBillKind.MOBILE_POSTPAID -> Icons.Rounded.Smartphone
    null -> when (source) {
        BillTimelineSource.CREDIT_CARD -> Icons.Rounded.CreditCard
        BillTimelineSource.RECURRING, BillTimelineSource.COMMITMENT -> Icons.Rounded.Payments
        BillTimelineSource.LOAN -> Icons.Rounded.Savings
        else -> Icons.AutoMirrored.Rounded.ReceiptLong
    }
}

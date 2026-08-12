@file:OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)

package com.paisalens.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Attractions
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Checkroom
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material.icons.rounded.LocalAtm
import androidx.compose.material.icons.rounded.LocalDining
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.RequestQuote
import androidx.compose.material.icons.rounded.ShoppingBasket
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.CustomCategory
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.categoryLabel
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Modifier.pressScale(enabled: Boolean = true): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = tween(120),
        label = "pressScale",
    )
    scale(scale)
        .clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = null,
            onClick = {},
        )
}

@Composable
fun PaisaCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(content = content)
    }
}

@Composable
fun SectionHeader(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (action != null && onAction != null) {
            Surface(
                onClick = onAction,
                color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = action,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
fun MoneyText(
    amountMinor: Long,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
    prefix: String = "",
) {
    AnimatedContent(
        targetState = amountMinor,
        modifier = modifier,
        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(100)) },
        label = "money",
    ) { value ->
        Text(
            text = prefix + formatMoney(value),
            style = style,
            color = color,
            fontWeight = style.fontWeight ?: FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
fun CategoryIcon(
    category: ExpenseCategory,
    modifier: Modifier = Modifier,
    iconSize: Int = 22,
) {
    val color = categoryColor(category)
    Box(
        modifier = modifier
            .size(48.dp)
            .background(color.copy(alpha = 0.16f), CircleShape)
            .semantics { contentDescription = category.label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = categoryIcon(category),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(iconSize.dp),
        )
    }
}

@Composable
fun CustomCategoryIcon(
    category: CustomCategory,
    modifier: Modifier = Modifier,
    iconSize: Int = 22,
) {
    val color = customCategoryColor(category.colorHex)
    Box(
        modifier = modifier
            .size(48.dp)
            .background(color.copy(alpha = 0.16f), CircleShape)
            .semantics { contentDescription = category.name },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = customCategoryIcon(category.name),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(iconSize.dp),
        )
    }
}

fun customCategoryColor(colorHex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(colorHex))
}.getOrDefault(Color(0xFF7784FF))

fun customCategoryIcon(name: String): ImageVector {
    val normalized = name.lowercase(Locale.ROOT)
    return when {
        categoryNameMatches(normalized, "pet", "animal", "dog", "cat") -> Icons.Rounded.Pets
        categoryNameMatches(normalized, "rent", "home", "house", "mortgage") -> Icons.Rounded.Home
        categoryNameMatches(normalized, "gift", "present") -> Icons.Rounded.CardGiftcard
        categoryNameMatches(normalized, "beauty", "salon", "spa", "groom") -> Icons.Rounded.Spa
        categoryNameMatches(normalized, "gym", "fitness", "sport", "workout") -> Icons.Rounded.FitnessCenter
        categoryNameMatches(normalized, "child", "baby", "kid", "school care") -> Icons.Rounded.ChildCare
        categoryNameMatches(normalized, "charity", "donation", "giving") -> Icons.Rounded.VolunteerActivism
        categoryNameMatches(normalized, "business", "work", "office") -> Icons.Rounded.Work
        categoryNameMatches(normalized, "tax", "duty") -> Icons.Rounded.RequestQuote
        categoryNameMatches(normalized, "subscription", "membership") -> Icons.Rounded.Subscriptions
        categoryNameMatches(normalized, "insurance", "cover") -> Icons.Rounded.HealthAndSafety
        categoryNameMatches(normalized, "coffee", "cafe") -> Icons.Rounded.Coffee
        categoryNameMatches(normalized, "food", "dining", "restaurant") -> Icons.Rounded.LocalDining
        categoryNameMatches(normalized, "fuel", "petrol", "diesel", "gas") -> Icons.Rounded.LocalGasStation
        categoryNameMatches(normalized, "hotel", "stay", "accommodation") -> Icons.Rounded.Hotel
        categoryNameMatches(normalized, "device", "electronic", "gadget") -> Icons.Rounded.Devices
        else -> Icons.Rounded.Category
    }
}

private fun categoryNameMatches(normalizedName: String, vararg keywords: String): Boolean {
    val words = normalizedName.split(Regex("[^a-z0-9]+"))
    return keywords.any { keyword ->
        if (' ' in keyword) {
            normalizedName.contains(keyword)
        } else {
            words.any { word ->
                word == keyword || word == "${keyword}s" || word == "${keyword}es" || word == "${keyword}ing"
            }
        }
    }
}

@Composable
fun TransactionRow(
    transaction: TransactionRecord,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        color = Color.Transparent,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryIcon(transaction.category)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transaction.merchant,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = buildString {
                        append(transaction.categoryLabel())
                        transaction.accountName?.let { append(" · ").append(it) }
                        append(" · ").append(formatTransactionTime(transaction.occurredAt))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                transaction.institutionName
                    ?.takeIf(String::isNotBlank)
                    ?.takeUnless { institution -> institution.equals(transaction.accountName, ignoreCase = true) }
                    ?.let { institution ->
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f),
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = CircleShape,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.AccountBalance,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = institution,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                transaction.note?.takeIf { it.isNotBlank() }?.let { note ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Note: $note",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (transaction.tags.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = transaction.tags.joinToString("  ") { "#$it" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (transaction.originalAmountMinor != null && transaction.originalCurrency != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Original: ${transaction.originalCurrency} ${"%,.2f".format(transaction.originalAmountMinor / 100.0)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (transaction.reviewStatus == ReviewStatus.NEEDS_REVIEW) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "Needs review",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            MoneyText(
                amountMinor = transaction.amountMinor,
                style = MaterialTheme.typography.titleMedium,
                color = when (transaction.type) {
                    TransactionType.INCOME, TransactionType.REFUND -> MaterialTheme.colorScheme.secondary
                    TransactionType.TRANSFER -> MaterialTheme.colorScheme.onSurfaceVariant
                    TransactionType.EXPENSE -> MaterialTheme.colorScheme.onSurface
                },
                prefix = when (transaction.type) {
                    TransactionType.INCOME, TransactionType.REFUND -> "+"
                    TransactionType.TRANSFER -> ""
                    TransactionType.EXPENSE -> "−"
                },
            )
        }
    }
}

@Composable
fun SpendingDonut(
    values: List<Pair<ExpenseCategory, Long>>,
    totalMinor: Long,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val progress by animateFloatAsState(
        targetValue = if (values.isEmpty()) 0f else 1f,
        animationSpec = tween(650),
        label = "donutProgress",
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .size(156.dp)
                .semantics {
                    contentDescription = "Category spending chart. Total " + formatMoney(totalMinor)
                },
        ) {
            val stroke = 17.dp.toPx()
            val inset = stroke / 2
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            if (totalMinor > 0) {
                var start = -90f
                values.forEach { (category, value) ->
                    val sweep = (value.toFloat() / totalMinor.toFloat()) * 360f * progress
                    drawArc(
                        color = categoryColor(category),
                        startAngle = start + 2f,
                        sweepAngle = (sweep - 4f).coerceAtLeast(0f),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                    start += sweep
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "This month",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = formatCompactMoney(totalMinor),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.AutoMirrored.Rounded.ReceiptLong,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(7.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun formatMoney(amountMinor: Long): String {
    val formatter = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).apply {
        maximumFractionDigits = if (amountMinor % 100L == 0L) 0 else 2
        minimumFractionDigits = 0
    }
    return formatter.format(amountMinor / 100.0)
}

fun formatCompactMoney(amountMinor: Long): String = when {
    amountMinor >= 10_000_000L -> "₹" + "%.1fL".format(Locale.US, amountMinor / 10_000_000.0)
    amountMinor >= 100_000L -> "₹" + "%.1fK".format(Locale.US, amountMinor / 100_000.0)
    else -> formatMoney(amountMinor)
}

fun formatTransactionTime(timestamp: Long): String =
    SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(timestamp))

fun categoryIcon(category: ExpenseCategory): ImageVector = when (category) {
    ExpenseCategory.FOOD -> Icons.Rounded.LocalDining
    ExpenseCategory.GROCERIES -> Icons.Rounded.ShoppingBasket
    ExpenseCategory.SHOPPING -> Icons.Rounded.Checkroom
    ExpenseCategory.TRANSPORT -> Icons.Rounded.DirectionsCar
    ExpenseCategory.BILLS -> Icons.AutoMirrored.Rounded.ReceiptLong
    ExpenseCategory.ENTERTAINMENT -> Icons.Rounded.Attractions
    ExpenseCategory.HEALTH -> Icons.Rounded.Favorite
    ExpenseCategory.EDUCATION -> Icons.Rounded.AutoStories
    ExpenseCategory.TRAVEL -> Icons.Rounded.Flight
    ExpenseCategory.CASH -> Icons.Rounded.LocalAtm
    ExpenseCategory.TRANSFER -> Icons.Rounded.SwapHoriz
    ExpenseCategory.INCOME -> Icons.Rounded.AccountBalance
    ExpenseCategory.OTHER -> Icons.Rounded.Category
}

fun categoryColor(category: ExpenseCategory): Color = when (category) {
    ExpenseCategory.FOOD -> Color(0xFFFF8A65)
    ExpenseCategory.GROCERIES -> Color(0xFF55D6A8)
    ExpenseCategory.SHOPPING -> Color(0xFFB48CFF)
    ExpenseCategory.TRANSPORT -> Color(0xFF5EB7FF)
    ExpenseCategory.BILLS -> Color(0xFFFFC857)
    ExpenseCategory.ENTERTAINMENT -> Color(0xFFFF72AE)
    ExpenseCategory.HEALTH -> Color(0xFFFF6E7F)
    ExpenseCategory.EDUCATION -> Color(0xFF8EA1FF)
    ExpenseCategory.TRAVEL -> Color(0xFF40C4D8)
    ExpenseCategory.CASH -> Color(0xFFB0BEC5)
    ExpenseCategory.TRANSFER -> Color(0xFF9FA8DA)
    ExpenseCategory.INCOME -> Color(0xFF21D19F)
    ExpenseCategory.OTHER -> Color(0xFF90A4AE)
}

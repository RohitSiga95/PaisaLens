package com.paisalens.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DashboardCustomize
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.HomeLayoutConfiguration
import com.paisalens.app.data.model.HomeModule
import com.paisalens.app.ui.components.PaisaCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeCustomizationSheet(
    configuration: HomeLayoutConfiguration,
    onConfigurationChange: (HomeLayoutConfiguration) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val visible = configuration.normalized().orderedVisibleModules
    val orderedModules = visible + HomeModule.defaultOrder.filterNot(visible::contains)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                top = 4.dp,
                end = 20.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    text = "Customise Home",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Choose what appears below the Home header and arrange it around what matters to you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Rounded.DashboardCustomize, contentDescription = null)
                        Column(Modifier.weight(1f)) {
                            Text("Your layout, stored on this device", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Changes apply instantly and never leave PaisaLens.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    text = "HOME MODULES",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
            }
            items(orderedModules, key = HomeModule::storageId) { module ->
                val isVisible = module in visible
                val visibleIndex = visible.indexOf(module)
                PaisaCard(Modifier.fillMaxWidth()) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 64.dp)
                                .toggleable(
                                    value = isVisible,
                                    role = Role.Switch,
                                    onValueChange = {
                                        onConfigurationChange(configuration.withVisibility(module, it))
                                    },
                                )
                                .semantics {
                                    contentDescription = "${module.label} Home module"
                                    stateDescription = if (isVisible) "Shown" else "Hidden"
                                }
                                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(module.label, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = homeModuleDescription(module),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = isVisible,
                                onCheckedChange = null,
                            )
                        }
                        if (isVisible) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.padding(start = 8.dp).size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Position ${visibleIndex + 1}",
                                    modifier = Modifier
                                        .weight(1f)
                                        .semantics {
                                            contentDescription = "Position ${visibleIndex + 1} of ${visible.size}"
                                        },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                IconButton(
                                    onClick = {
                                        onConfigurationChange(configuration.move(module, visibleIndex - 1))
                                    },
                                    enabled = visibleIndex > 0,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.ArrowUpward,
                                        contentDescription = if (visibleIndex > 0) {
                                            "Move ${module.label} up to position $visibleIndex"
                                        } else {
                                            "${module.label} is already first"
                                        },
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        onConfigurationChange(configuration.move(module, visibleIndex + 1))
                                    },
                                    enabled = visibleIndex in 0 until visible.lastIndex,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.ArrowDownward,
                                        contentDescription = if (visibleIndex < visible.lastIndex) {
                                            "Move ${module.label} down to position ${visibleIndex + 2}"
                                        } else {
                                            "${module.label} is already last"
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { onConfigurationChange(HomeLayoutConfiguration()) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text("Restore default layout")
                }
            }
            item {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text("Done")
                }
            }
        }
    }
}

private fun homeModuleDescription(module: HomeModule): String = when (module) {
    HomeModule.MONTHLY_SPEND -> "This month's net spending hero."
    HomeModule.SPEND_OVERVIEW -> "Gross expenses, refunds, income, and available money."
    HomeModule.SPENDING_BREAKDOWN -> "Monthly category analysis and month navigation."
    HomeModule.BANK_BALANCES -> "Latest balance tiles grouped by account last four."
    HomeModule.CREDIT_AVAILABLE -> "Available credit and utilisation for active cards."
    HomeModule.SAVINGS_GOALS -> "At-a-glance progress for goals and sinking funds."
    HomeModule.UPCOMING_COMMITMENTS -> "The next subscriptions and UPI AutoPay mandates due."
}

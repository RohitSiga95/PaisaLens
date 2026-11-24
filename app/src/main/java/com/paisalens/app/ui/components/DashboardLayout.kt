package com.paisalens.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/** Center long reading surfaces on wide windows without narrowing a compact phone. */
internal fun Modifier.dashboardWidth(max: Dp): Modifier =
    fillMaxWidth().wrapContentWidth().widthIn(max = max).fillMaxWidth()

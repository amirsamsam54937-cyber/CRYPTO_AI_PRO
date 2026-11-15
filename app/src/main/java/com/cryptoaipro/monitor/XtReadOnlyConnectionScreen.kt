package com.cryptoaipro.monitor

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** XT connection and explicitly confirmed manual execution surfaces. */
@Composable
internal fun XtConnectionAndExecutionScreen(context: Context, signals: List<MarketSignal>) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        XtApiCredentialPanel(context)
        XtManualExecutionPanel(context, signals)
    }
}

package com.cryptoaipro.monitor

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Separate read-only connection surface; it has no order or live-mode controls. */
@Composable
internal fun XtReadOnlyConnectionScreen(context: Context) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        XtApiCredentialPanel(context)
    }
}

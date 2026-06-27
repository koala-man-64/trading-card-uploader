package com.tradingcards.uploader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
fun CaptureScreen(
    statusText: String,
    onSignIn: () -> Unit,
    onCapture: () -> Unit,
) {
    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Trading Card Uploader",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(statusText)
            Button(onClick = onSignIn) {
                Text("Sign in")
            }
            Button(onClick = onCapture) {
                Text("Capture card photo")
            }
        }
    }
}

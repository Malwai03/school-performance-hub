package com.schoolapp.core.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.schoolapp.core.ui.theme.DangerRed
import com.schoolapp.core.ui.theme.SuccessGreen
import com.schoolapp.core.ui.theme.TextSecondary

@Composable
fun InlineValidationText(
    message: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = when {
            isError -> DangerRed
            message.contains("ready", ignoreCase = true) -> SuccessGreen
            else -> TextSecondary
        },
        modifier = modifier,
    )
}

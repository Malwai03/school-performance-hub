package com.schoolapp.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.schoolapp.core.model.ScoreStatus
import com.schoolapp.core.ui.theme.DangerRed
import com.schoolapp.core.ui.theme.DangerRedBg
import com.schoolapp.core.ui.theme.ExemptGrayBg
import com.schoolapp.core.ui.theme.SuccessGreen
import com.schoolapp.core.ui.theme.SuccessGreenBg
import com.schoolapp.core.ui.theme.TextSecondary
import com.schoolapp.core.ui.theme.WarningAmber

data class MarksRowUi(
    val studentId: String,
    val rollNumber: Int,
    val studentName: String,
    val status: ScoreStatus,
    val marksDraft: String,
    val maxMarks: Double?,
    val validationMessage: String? = null,
)

@Composable
fun MarksEntryTable(
    rows: List<MarksRowUi>,
    onStatusToggle: (String) -> Unit,
    onMarksChange: (String, String) -> Unit,
    dense: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val vSpacing = if (dense) 6.dp else 10.dp
    val statusWidth = if (dense) 78.dp else 86.dp
    val marksWidth = if (dense) 88.dp else 96.dp

    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(vSpacing)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Student",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Status",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    modifier = Modifier.width(statusWidth),
                )
                Text(
                    "Marks",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    modifier = Modifier.width(marksWidth),
                )
            }

            rows.forEach { row ->
                val label = when (row.status) {
                    ScoreStatus.PRESENT -> "Present"
                    ScoreStatus.ABSENT -> "Absent"
                    ScoreStatus.EXEMPT -> "Exempt"
                }
                val color = when (row.status) {
                    ScoreStatus.PRESENT -> SuccessGreen
                    ScoreStatus.ABSENT -> DangerRed
                    ScoreStatus.EXEMPT -> WarningAmber
                }
                val isPresent = row.status == ScoreStatus.PRESENT

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${row.rollNumber}. ${row.studentName}",
                            style = if (dense) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        AssistChip(
                            onClick = { onStatusToggle(row.studentId) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = when (row.status) {
                                    ScoreStatus.PRESENT -> SuccessGreenBg
                                    ScoreStatus.ABSENT -> DangerRedBg
                                    ScoreStatus.EXEMPT -> ExemptGrayBg
                                },
                                labelColor = color,
                            ),
                            modifier = Modifier.width(statusWidth),
                        )
                        OutlinedTextField(
                            value = if (isPresent) row.marksDraft else "",
                            onValueChange = { if (isPresent) onMarksChange(row.studentId, it) },
                            enabled = isPresent,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Next,
                            ),
                            label = {
                                Text(
                                    if (row.maxMarks != null) "/${"%.0f".format(row.maxMarks)}" else "Marks",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            modifier = Modifier.width(marksWidth),
                        )
                    }
                    row.validationMessage?.let { message ->
                        InlineValidationText(
                            message = message,
                            isError = true,
                        )
                    }
                }
            }
        }
    }
}

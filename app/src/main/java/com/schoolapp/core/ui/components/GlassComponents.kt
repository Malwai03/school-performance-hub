package com.schoolapp.core.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.schoolapp.core.model.PassStatus
import com.schoolapp.core.model.RiskLevel
import com.schoolapp.core.ui.theme.Background
import com.schoolapp.core.ui.theme.Border
import com.schoolapp.core.ui.theme.DangerRedBg
import com.schoolapp.core.ui.theme.DangerRed
import com.schoolapp.core.ui.theme.ExemptGrayBg
import com.schoolapp.core.ui.theme.Primary
import com.schoolapp.core.ui.theme.PrimaryLight
import com.schoolapp.core.ui.theme.SuccessGreenBg
import com.schoolapp.core.ui.theme.SuccessGreen
import com.schoolapp.core.ui.theme.Surface
import com.schoolapp.core.ui.theme.TextPrimary
import com.schoolapp.core.ui.theme.TextSecondary
import com.schoolapp.core.ui.theme.WarningAmber
import com.schoolapp.core.ui.theme.WarningYellowBg

@Composable
fun GradientBackground(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Background)) { content() }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .shadow(2.dp, RoundedCornerShape(16.dp), ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.08f))
            .clip(RoundedCornerShape(16.dp))
            .background(Surface)
            .border(1.dp, Border, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(title: String, actions: @Composable () -> Unit = {}) {
    CenterAlignedTopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = TextPrimary) },
        actions = { actions() },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Surface),
    )
}

data class DropdownOption(
    val id: String,
    val label: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormDropdownField(
    label: String,
    options: List<DropdownOption>,
    selectedId: String?,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.id == selectedId }?.label.orEmpty()
    val canExpand = enabled && options.isNotEmpty()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (canExpand) expanded = !expanded },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            placeholder = { Text(if (options.isEmpty()) "No options" else "Select") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        DropdownMenu(
            expanded = expanded && canExpand,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelected(option.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
fun KpiCard(title: String, value: String, accent: Color = Primary, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .shadow(2.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Surface)
            .border(1.dp, Border, RoundedCornerShape(16.dp)),
    ) {
        Row {
            Box(Modifier.width(4.dp).height(80.dp).background(accent))
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Text(value, style = MaterialTheme.typography.headlineMedium, color = accent)
            }
        }
    }
}

@Composable
fun ProgressRing(percent: Double, ringColor: Color = Primary, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(targetValue = percent.toFloat(), label = "ring")
    val progress = (animated / 100f).coerceIn(0f, 1f)
    Box(modifier = modifier.size(120.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = 10.dp.toPx()
            drawCircle(color = Border, style = Stroke(s))
            drawArc(ringColor, -90f, progress * 360f, false, style = Stroke(s, cap = StrokeCap.Round))
        }
        Text("${"%.1f".format(percent)}%", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun Sparkline(points: List<Float>, modifier: Modifier = Modifier) {
    if (points.isEmpty()) return
    val lineColor = Primary
    val fillStart = PrimaryLight.copy(alpha = 0.25f)
    val fillEnd = PrimaryLight.copy(alpha = 0.0f)

    if (points.size == 1) {
        Canvas(modifier) { drawCircle(lineColor, 5.dp.toPx(), Offset(size.width / 2f, size.height / 2f)) }
        return
    }
    val max = points.maxOrNull() ?: return
    val min = points.minOrNull() ?: return
    val range = (max - min).takeIf { it > 0f } ?: 1f

    Canvas(modifier) {
        val stepX = size.width / (points.size - 1).coerceAtLeast(1)
        val vPad = size.height * 0.1f
        val offsets = points.mapIndexed { i, v ->
            Offset(i * stepX, size.height - vPad - ((v - min) / range) * (size.height - 2 * vPad))
        }
        val fill = Path().apply {
            moveTo(offsets.first().x, size.height); lineTo(offsets.first().x, offsets.first().y)
            for (i in 1..offsets.lastIndex) lineTo(offsets[i].x, offsets[i].y)
            lineTo(offsets.last().x, size.height); close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(fillStart, fillEnd), 0f, size.height))
        val line = Path().apply {
            moveTo(offsets.first().x, offsets.first().y)
            for (i in 1..offsets.lastIndex) lineTo(offsets[i].x, offsets[i].y)
        }
        drawPath(line, lineColor, style = Stroke(4f, cap = StrokeCap.Round))
        offsets.forEach { o -> drawCircle(Color.White, 5f, o); drawCircle(lineColor, 3.5f, o) }
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text(label, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun RiskBadge(risk: RiskLevel, modifier: Modifier = Modifier) {
    val (label, fg, bg) = when (risk) {
        RiskLevel.LOW -> Triple("LOW RISK", SuccessGreen, SuccessGreenBg)
        RiskLevel.MEDIUM -> Triple("MED RISK", WarningAmber, WarningYellowBg)
        RiskLevel.HIGH -> Triple("HIGH RISK", DangerRed, DangerRedBg)
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

@Composable
fun PassStatusBadge(status: PassStatus, modifier: Modifier = Modifier) {
    val (label, fg, bg) = when (status) {
        PassStatus.PASS -> Triple("PASS", SuccessGreen, SuccessGreenBg)
        PassStatus.FAIL -> Triple("FAIL", DangerRed, DangerRedBg)
        PassStatus.INCOMPLETE -> Triple("INCOMPLETE", WarningAmber, ExemptGrayBg)
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

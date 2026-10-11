package com.aliothmoon.maameow.presentation.components

import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aliothmoon.maameow.R
import com.aliothmoon.maameow.data.model.WeeklyScheduled
import com.aliothmoon.maameow.theme.MaaAnimatedVisibility

/** 周计划开关 + 星期选择，理智作战与基建换班共用 */
@Composable
fun WeeklyScheduleSection(
    config: WeeklyScheduled,
    onEnabledChange: (Boolean) -> Unit,
    onScheduleChange: (Map<String, Boolean>) -> Unit,
) {
    val weekDays = listOf(
        "MONDAY" to stringResource(R.string.panel_weekday_monday),
        "TUESDAY" to stringResource(R.string.panel_weekday_tuesday),
        "WEDNESDAY" to stringResource(R.string.panel_weekday_wednesday),
        "THURSDAY" to stringResource(R.string.panel_weekday_thursday),
        "FRIDAY" to stringResource(R.string.panel_weekday_friday),
        "SATURDAY" to stringResource(R.string.panel_weekday_saturday),
        "SUNDAY" to stringResource(R.string.panel_weekday_sunday),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CheckBoxWithExpandableTip(
            checked = config.useWeeklySchedule,
            onCheckedChange = onEnabledChange,
            label = stringResource(R.string.panel_weekly_schedule),
            tipText = stringResource(R.string.panel_weekly_schedule_tip)
        )
        MaaAnimatedVisibility(
            visible = config.useWeeklySchedule,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 4.dp)
            ) {
                weekDays.forEach { (key, display) ->
                    val selected = config.weeklySchedule[key] != false
                    Surface(
                        onClick = {
                            val updated = config.weeklySchedule.toMutableMap()
                            updated[key] = !selected
                            onScheduleChange(updated)
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = if (selected)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (selected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.outlineVariant
                        )
                    ) {
                        Text(
                            text = display,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selected)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

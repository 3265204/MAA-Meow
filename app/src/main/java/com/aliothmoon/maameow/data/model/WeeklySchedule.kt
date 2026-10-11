package com.aliothmoon.maameow.data.model

import java.time.DayOfWeek

/** 周计划：只在勾选的游戏日执行，key 为 [DayOfWeek] 枚举名 */
interface WeeklyScheduled {
    val useWeeklySchedule: Boolean
    val weeklySchedule: Map<String, Boolean>

    /** 没记录的日子按勾选算，对齐 WPF */
    fun isSkippedOn(day: DayOfWeek): Boolean =
        useWeeklySchedule && weeklySchedule[day.name] == false
}

object WeeklySchedule {
    val ALL_DAYS: Map<String, Boolean> = DayOfWeek.entries.associate { it.name to true }
}

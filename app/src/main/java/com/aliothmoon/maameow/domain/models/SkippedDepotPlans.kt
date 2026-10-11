package com.aliothmoon.maameow.domain.models

/**
 * 一份库存保持里预检时已充足、因而没下发的计划，对齐上游 70d1f06bb8
 *
 * 预检用的是识别前的缓存；本轮仓库识别后若实际不足，计划已无法在运行中补进队列，只能提示重跑
 * 只收关卡今天打得了的，打不了的实际不足也补不上
 *
 * @param firstOnly 仅执行第一个不足计划：只提示第一个翻转的
 */
data class SkippedDepotPlans(
    val taskName: String,
    val plans: List<Plan>,
    val firstOnly: Boolean,
) {
    /** @param no 计划序号，从 1 起，与预检日志一致 */
    data class Plan(val no: Int, val dropId: String, val dropCount: Int)
}

/** 复查出的翻转：预检够、实际不够 */
data class StaleSkippedPlan(
    val taskName: String,
    val no: Int,
    val dropName: String,
    val current: Int,
    val target: Int,
)

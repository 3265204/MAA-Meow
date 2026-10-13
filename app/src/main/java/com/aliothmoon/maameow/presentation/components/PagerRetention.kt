package com.aliothmoon.maameow.presentation.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first

/**
 * 所在分页是否可见；常驻但不在屏上的页据此停掉无限动画
 *
 * 传 State 而非 Boolean：切页时页码变化只让读取方重组，不牵动整页
 */
val LocalPageVisible = staticCompositionLocalOf<State<Boolean>> { AlwaysVisible }

private val AlwaysVisible: State<Boolean> = derivedStateOf { true }

/** 当前页或正滑向的页算可见，叠加外层可见性 */
@Composable
fun rememberPageVisible(pagerState: PagerState, page: Int): State<Boolean> {
    val parent = LocalPageVisible.current
    return remember(pagerState, page, parent) {
        derivedStateOf { parent.value && (page == pagerState.currentPage || page == pagerState.targetPage) }
    }
}

/** 所在页不可见时停用，常驻的离屏页不抢返回键 */
@Composable
fun PageBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val visible by LocalPageVisible.current
    BackHandler(enabled = enabled && visible, onBack = onBack)
}

/** 所在页不可见时停在最后一次的值，常驻页不再跟着高频数据重组 */
@Composable
fun <T> StateFlow<T>.collectWhilePageVisible(): State<T> {
    val visible by LocalPageVisible.current
    val source = remember(this, visible) { if (visible) this else emptyFlow() }
    val initial = remember { value }
    return source.collectAsStateWithLifecycle(initial)
}

// 等首屏与转场结束再补组合
private const val STAGE_START_DELAY_MS = 400L

/**
 * 分帧扩大 Pager 常驻页数：首帧只组合当前页，静止后每帧多留一页
 *
 * 一次性常驻全部页面会把所有页的组合压进进入页面的那一帧
 */
@Composable
fun rememberStagedBeyondViewportCount(pagerState: PagerState, max: Int): Int {
    var count by remember { mutableIntStateOf(0) }
    LaunchedEffect(pagerState, max) {
        delay(STAGE_START_DELAY_MS)
        while (count < max) {
            snapshotFlow { pagerState.isScrollInProgress }.first { !it }
            withFrameNanos { }
            count++
        }
    }
    return count.coerceAtMost(max)
}

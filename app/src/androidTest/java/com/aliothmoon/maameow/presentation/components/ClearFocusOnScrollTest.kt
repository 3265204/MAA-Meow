package com.aliothmoon.maameow.presentation.components

import android.widget.EditText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aliothmoon.maameow.presentation.InputFocusManager
import com.aliothmoon.maameow.presentation.LocalInputFocusManager
import com.aliothmoon.maameow.presentation.ProvideInputFocusManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClearFocusOnScrollTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun clearsOncePerDragAndResetsForNextGesture() {
        val focus = CountingFocusManager()
        val listState = showList(focus)

        composeRule.onNodeWithTag("list").performTouchInput { swipeUp(durationMillis = 500) }
        composeRule.runOnIdle {
            assertTrue(listState.firstVisibleItemIndex > 0)
            assertEquals(1, focus.clearCount)
        }

        composeRule.onNodeWithTag("list").performTouchInput { swipeUp(durationMillis = 500) }
        composeRule.runOnIdle { assertEquals(2, focus.clearCount) }
    }

    @Test
    fun programmaticScrollDoesNotClearFocus() {
        val focus = CountingFocusManager()
        showList(focus)

        composeRule.onNodeWithTag("list").performScrollToIndex(20)

        composeRule.runOnIdle { assertEquals(0, focus.clearCount) }
    }

    @Test
    fun blankTapsClearForEachGesture() {
        val focus = CountingFocusManager()
        showList(focus)

        repeat(2) {
            composeRule.onNodeWithTag("list").performTouchInput { click() }
            composeRule.runOnIdle { assertEquals(it + 1, focus.clearCount) }
        }
    }

    @Test
    fun composeEditorKeepsFocusOnProgrammaticScrollAndClearsOnDrag() {
        composeRule.setContent {
            ProvideInputFocusManager {
                Column(Modifier.size(300.dp, 400.dp).clearFocusOnBlankTap()) {
                    BasicTextField(
                        value = "text",
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth().height(56.dp).testTag("editor"),
                    )
                    LazyColumn(Modifier.weight(1f).testTag("list")) {
                        items(100) { Text("row-$it", Modifier.height(48.dp)) }
                    }
                }
            }
        }

        composeRule.onNodeWithTag("editor").performTouchInput { click() }
        composeRule.onNodeWithTag("editor").assertIsFocused()
        composeRule.onNodeWithTag("list").performScrollToIndex(20)
        composeRule.onNodeWithTag("editor").assertIsFocused()
        composeRule.onNodeWithTag("list").performTouchInput { swipeUp(durationMillis = 500) }
        composeRule.onNodeWithTag("editor").assertIsNotFocused()
    }

    @Test
    fun nativeEditorKeepsFocusInsideAndClearsBeforeOutsideButtonClick() {
        lateinit var editor: EditText
        composeRule.setContent {
            ProvideInputFocusManager {
                Column(Modifier.size(300.dp, 400.dp).clearFocusOnBlankTap()) {
                    AndroidView(
                        factory = { context ->
                            EditText(context).apply {
                                editor = this
                                setText("text")
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp).testTag("editor"),
                    )
                    Button(onClick = {}, modifier = Modifier.testTag("outside")) { Text("outside") }
                }
            }
        }

        composeRule.onNodeWithTag("editor").performTouchInput { click() }
        composeRule.runOnIdle { assertTrue(editor.hasFocus()) }
        composeRule.onNodeWithTag("editor").performTouchInput { click() }
        composeRule.runOnIdle { assertTrue(editor.hasFocus()) }
        composeRule.onNodeWithTag("outside").performTouchInput { click() }
        composeRule.runOnIdle { assertFalse(editor.hasFocus()) }
    }

    private fun showList(focus: InputFocusManager): LazyListState {
        lateinit var listState: LazyListState
        composeRule.setContent {
            listState = rememberLazyListState()
            CompositionLocalProvider(LocalInputFocusManager provides focus) {
                Box(Modifier.size(300.dp, 400.dp).clearFocusOnBlankTap()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag("list"),
                    ) {
                        items(100) { Text("row-$it", modifier = Modifier.height(48.dp)) }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return listState
    }

    private class CountingFocusManager : InputFocusManager {
        var clearCount = 0
            private set

        override fun clear(force: Boolean) {
            clearCount++
        }
    }
}

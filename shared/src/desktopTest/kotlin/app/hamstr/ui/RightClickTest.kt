package app.hamstr.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.hamstr.ui.components.onRightClick
import app.hamstr.ui.components.pressable
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a long press does on the phone, a right-click does on the desktop; a left click stays a click. */
@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
class RightClickTest {
    private class Counts {
        var clicks = 0
        var menus = 0
    }

    private fun check(content: @androidx.compose.runtime.Composable (Counts) -> Modifier) = runComposeUiTest {
        val n = Counts()
        setContent { Box(content(n).size(100.dp).testTag("it")) }
        onNodeWithTag("it").performMouseInput { rightClick() }
        waitForIdle()
        assertEquals(1, n.menus, "right-click")
        assertEquals(0, n.clicks, "right-click isn't a click")
        onNodeWithTag("it").performMouseInput { click() }
        waitForIdle()
        assertEquals(1, n.clicks, "left click")
        onNodeWithTag("it").performTouchInput { longClick() }
        waitForIdle()
        assertEquals(2, n.menus, "long press")
        assertEquals(1, n.clicks)
    }

    /** A stash row (TaskRow) opens its menu. */
    @Test fun row() = check { n ->
        Modifier.onRightClick { n.menus++ }.combinedClickable(onClick = { n.clicks++ }, onLongClick = { n.menus++ })
    }

    /** Why it's needed: Compose's long click alone ignores the right button. */
    @Test fun notByItself() = runComposeUiTest {
        val n = Counts()
        setContent { Box(Modifier.combinedClickable(onClick = { n.clicks++ }, onLongClick = { n.menus++ }).size(100.dp).testTag("it")) }
        onNodeWithTag("it").performMouseInput { rightClick() }
        waitForIdle()
        assertEquals(0, n.menus)
    }

    /** Buttons, tiles and cards with a long press. */
    @Test fun pressable() = check { n ->
        Modifier.pressable(onClick = { n.clicks++ }, onLongClick = { n.menus++ })
    }
}

package app.tidemark

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tidemark.design.AppTheme
import app.tidemark.design.ConfirmDialog
import app.tidemark.design.DayChips
import app.tidemark.design.SegmentedControl
import app.tidemark.design.StepperRow
import app.tidemark.design.ThemeMode
import app.tidemark.ui.BreatheScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The shared controls behave as specified in replica/design/components.md. */
@RunWith(AndroidJUnit4::class)
class ControlsUiTest {
    @get:Rule val rule = createComposeRule()
    private fun str(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)

    @Test fun stepperStopsAtItsBounds() {
        var last = -1
        rule.setContent {
            AppTheme(ThemeMode.LIGHT) {
                var v by remember { mutableIntStateOf(10) }
                StepperRow("Target", v, "$v", { v = it; last = it }, min = 10, max = 30, step = 10)
            }
        }
        rule.onNodeWithContentDescription("−10").assertIsNotEnabled()
        rule.onNodeWithContentDescription("+10").performClick()
        rule.onNodeWithContentDescription("+10").performClick()
        assertEquals(30, last)
        rule.onNodeWithContentDescription("+10").assertIsNotEnabled()
    }

    @Test fun dayChipsNeverClearTheLastDay() {
        var mask = 0
        rule.setContent {
            AppTheme(ThemeMode.DARK) {
                var m by remember { mutableIntStateOf(0b0000001) }
                DayChips(m, listOf("M", "T", "W", "T2", "F", "S", "S2")) { m = it; mask = it }
            }
        }
        rule.onNodeWithText("M").performClick() // only day selected: refused
        assertEquals(0, mask)
        rule.onNodeWithText("F").performClick()
        assertEquals(0b0010001, mask)
    }

    @Test fun segmentedControlSelects() {
        var sel = 0
        rule.setContent { AppTheme(ThemeMode.LIGHT) { SegmentedControl(listOf("Day", "Week", "Month"), sel, { sel = it }) } }
        rule.onNodeWithText("Month").performClick()
        assertEquals(2, sel)
    }

    @Test fun confirmDialogNeedsAnExplicitTap() {
        var confirmed = 0
        var dismissed = 0
        rule.setContent {
            AppTheme(ThemeMode.LIGHT) { ConfirmDialog("Delete all data", "Cannot be undone.", "Delete", { confirmed++ }, { dismissed++ }) }
        }
        rule.onNodeWithText(str(R.string.cancel)).performClick()
        assertEquals(0, confirmed); assertEquals(1, dismissed)
        rule.onNodeWithText("Delete").performClick()
        assertEquals(1, confirmed)
    }

    @Test fun breatheScreenOffersDone() {
        var done = 0
        rule.setContent { AppTheme(ThemeMode.SYSTEM) { BreatheScreen(onDone = { done++ }) } }
        rule.onNodeWithText(str(R.string.done)).assertIsDisplayed().performClick()
        assertEquals(1, done)
    }
}

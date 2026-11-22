package app.tidemark

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tidemark.design.AppTheme
import app.tidemark.design.ThemeMode
import app.tidemark.ui.DisclosureScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** F01-H1 / F01-N1 at the screen level: S02 agree and decline, both themes. */
@RunWith(AndroidJUnit4::class)
class OnboardingUiTest {
    @get:Rule val rule = createComposeRule()

    // Read copy from resources so the test passes in any device language (e.g. Indonesian).
    private fun str(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun show(mode: ThemeMode, onAgree: () -> Unit = {}, onDecline: () -> Unit = {}) =
        rule.setContent { AppTheme(mode) { DisclosureScreen(onAgree, onDecline) } }

    @Test fun agreeCallsBackOnce() {
        var agreed = 0
        show(ThemeMode.LIGHT, onAgree = { agreed++ })
        rule.onNodeWithText(str(R.string.disclosure_agree)).performScrollTo().performClick()
        assertEquals(1, agreed)
    }

    @Test fun declineIsEquallyAvailable() {
        var declined = 0
        show(ThemeMode.DARK, onDecline = { declined++ })
        rule.onNodeWithText(str(R.string.disclosure_decline)).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, declined)
    }

    @Test fun titleIsAHeadingForScreenReaders() {
        show(ThemeMode.SYSTEM)
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertIsDisplayed()
    }

    @Test fun disclosureNamesTheHashAndNoInternet() {
        show(ThemeMode.LIGHT)
        rule.onNodeWithText(str(R.string.disclosure_read_3)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(str(R.string.disclosure_never_3)).performScrollTo().assertIsDisplayed()
    }
}

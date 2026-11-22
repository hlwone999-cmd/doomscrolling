package app.tidemark.design

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.tidemark.R

/**
 * Inter (variable, SIL Open Font License 1.1), bundled in res/font so nothing
 * is downloaded. One file serves every weight the type scale uses. The
 * licence ships in assets/licenses/inter_OFL.txt and is shown in
 * Settings > Open-source licences.
 */
@OptIn(ExperimentalTextApi::class)
object AppFonts {
    private fun weight(w: Int) = Font(
        R.font.inter_variable,
        weight = FontWeight(w),
        variationSettings = FontVariation.Settings(FontVariation.weight(w)),
    )

    val Inter: FontFamily = FontFamily(weight(400), weight(500), weight(600), weight(700), weight(800))
}

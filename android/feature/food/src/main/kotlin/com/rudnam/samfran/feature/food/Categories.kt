package com.rudnam.samfran.feature.food

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rudnam.samfran.core.designsystem.icon.LineIcon
import com.rudnam.samfran.core.designsystem.icon.SamFranIcons
import com.rudnam.samfran.core.designsystem.theme.Pair2
import com.rudnam.samfran.core.designsystem.theme.SamFranTheme
import com.rudnam.samfran.core.model.PlaceCategory

/** A category's line icon (web/src/features/restaurants/categories.tsx draws the same). */
val PlaceCategory.icon: String
    get() = when (this) {
        PlaceCategory.Eat -> SamFranIcons.catEat
        PlaceCategory.Nature -> SamFranIcons.catNature
        PlaceCategory.Culture -> SamFranIcons.catCulture
        PlaceCategory.Fun -> SamFranIcons.catFun
        PlaceCategory.Relax -> SamFranIcons.catRelax
        PlaceCategory.Shopping -> SamFranIcons.catShopping
        PlaceCategory.Other -> SamFranIcons.catOther
    }

/** Its name in full: "Nature & parks". */
@get:StringRes
val PlaceCategory.title: Int
    get() = when (this) {
        PlaceCategory.Eat -> R.string.cat_eat
        PlaceCategory.Nature -> R.string.cat_nature
        PlaceCategory.Culture -> R.string.cat_culture
        PlaceCategory.Fun -> R.string.cat_fun
        PlaceCategory.Relax -> R.string.cat_relax
        PlaceCategory.Shopping -> R.string.cat_shopping
        PlaceCategory.Other -> R.string.cat_other
    }

/** Its short name, for a filter chip: "Nature". */
@get:StringRes
val PlaceCategory.short: Int
    get() = when (this) {
        PlaceCategory.Eat -> R.string.cat_eat_short
        PlaceCategory.Nature -> R.string.cat_nature_short
        PlaceCategory.Culture -> R.string.cat_culture_short
        PlaceCategory.Fun -> R.string.cat_fun_short
        PlaceCategory.Relax -> R.string.cat_relax_short
        PlaceCategory.Shopping -> R.string.cat_shopping_short
        PlaceCategory.Other -> R.string.cat_other_short
    }

/** What goes in it, for the form. */
@get:StringRes
val PlaceCategory.hint: Int
    get() = when (this) {
        PlaceCategory.Eat -> R.string.cat_eat_hint
        PlaceCategory.Nature -> R.string.cat_nature_hint
        PlaceCategory.Culture -> R.string.cat_culture_hint
        PlaceCategory.Fun -> R.string.cat_fun_hint
        PlaceCategory.Relax -> R.string.cat_relax_hint
        PlaceCategory.Shopping -> R.string.cat_shopping_hint
        PlaceCategory.Other -> R.string.cat_other_hint
    }

/** What the Kind field suggests; null for Other. */
@get:StringRes
val PlaceCategory.kindHint: Int?
    get() = when (this) {
        PlaceCategory.Eat -> R.string.food_hint
        PlaceCategory.Nature -> R.string.kind_hint_nature
        PlaceCategory.Culture -> R.string.kind_hint_culture
        PlaceCategory.Fun -> R.string.kind_hint_fun
        PlaceCategory.Relax -> R.string.kind_hint_relax
        PlaceCategory.Shopping -> R.string.kind_hint_shopping
        PlaceCategory.Other -> null
    }

/** Its tile's colours (.tile.cat-*). */
@Composable
fun PlaceCategory.colors(): Pair2 {
    val c = SamFranTheme.colors
    return when (this) {
        PlaceCategory.Eat -> c.tileOrange
        PlaceCategory.Nature -> c.tileGreen
        PlaceCategory.Culture -> c.tileRed
        PlaceCategory.Fun -> c.tileBlue
        PlaceCategory.Relax -> c.tilePurple
        PlaceCategory.Shopping -> c.tileYellow
        PlaceCategory.Other -> Pair2(c.sunken, c.muted)
    }
}

/** The category's icon on its colour, as the list, a place's page and the search show it. */
@Composable
fun CategoryTile(category: PlaceCategory, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val colors = category.colors()
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(colors.bg),
        contentAlignment = Alignment.Center,
    ) {
        LineIcon(category.icon, null, tint = colors.ink, size = size * 0.48f)
    }
}

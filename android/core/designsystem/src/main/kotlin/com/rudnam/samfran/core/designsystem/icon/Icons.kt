package com.rudnam.samfran.core.designsystem.icon

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * SamFran's line icons, on a 24-unit grid with one round 2-unit stroke: the
 * tab icons are the website's own (web/src/App.tsx), the rest drawn to match.
 */
object SamFranIcons {
    const val food = "M7 3v8a2 2 0 0 0 2 2v8M5 3v5M9 3v5M17 3c-2 2-2 6 0 9v9"
    const val shifts = "M5 5h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM3 10h18M8 3v4M16 3v4"
    const val bus = "M8 3h8a3 3 0 0 1 3 3v9a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3zM5 11h14M8 21v-3M16 21v-3"
    const val us = "M12.5 8a3.5 3.5 0 1 1-7 0a3.5 3.5 0 1 1 7 0zM19.5 9a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0zM3 20c0-3.5 2.7-6 6-6s6 2.5 6 6M15 14.5c3 0 6 1.8 6 5.5"
    const val add = "M12 5v14M5 12h14"
    const val back = "M15 5l-7 7 7 7"
    const val chevronLeft = "M14 6l-6 6 6 6"
    const val chevronRight = "M10 6l6 6-6 6"
    const val search = "M16.5 10.5a6 6 0 1 1-12 0a6 6 0 1 1 12 0zM15 15l5 5"
    const val map = "M9 4L3 6v14l6-2 6 2 6-2V4l-6 2-6-2zM9 4v14M15 6v14"
    const val edit = "M4 20h4L19 9l-4-4L4 16v4zM13.5 6.5l4 4"
    const val trash = "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"
    const val camera = "M4 8h3l2-3h6l2 3h3a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1zM15.5 13.5a3.5 3.5 0 1 1-7 0a3.5 3.5 0 1 1 7 0z"
    const val settings = "M4 7h10M18 7h2M4 17h4M12 17h8M16 7a2 2 0 1 1-4 0a2 2 0 1 1 4 0zM10 17a2 2 0 1 1-4 0a2 2 0 1 1 4 0z"
    const val share = "M12 15V3M8 7l4-4 4 4M5 12v7a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-7"
    const val copy = "M9 9h10v12H9zM5 15H4V3h11v1"
    const val location = "M12 21s-7-6.2-7-12a7 7 0 0 1 14 0c0 5.8-7 12-7 12zM14.5 9a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0z"
    const val close = "M6 6l12 12M18 6L6 18"
    const val calendar = shifts
    const val list = "M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01"
    const val check = "M5 12.5l4.5 4.5L19 7"
    /** The Places tab: a map pin. */
    const val places = "M12 21s-7-6.2-7-11.5a7 7 0 0 1 14 0C19 14.8 12 21 12 21zM14.5 9.5a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0z"

    // A place's category (web/src/features/restaurants/categories.tsx draws the same).
    const val catEat = food
    const val catNature = "M12 3a5 5 0 0 1 5 5 4 4 0 0 1-1 7.5H8A4 4 0 0 1 7 8a5 5 0 0 1 5-5zM12 15.5V21M12 12.5l-2.5-2M9 21h6"
    const val catCulture = "M3 5c4 1.2 14 1.2 18 0M5 9.5h14M7 6.3V21M17 6.3V21M12 6.5v3"
    const val catFun = "M3 8a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v2a2 2 0 0 0 0 4v2a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-2a2 2 0 0 0 0-4zM14.5 6v2M14.5 11v2M14.5 16v2"
    const val catRelax = "M8.5 3c-1 1.3 1 2.4 0 3.7s1 2.4 0 3.7M12 2.5c-1 1.3 1 2.4 0 3.7s1 2.4 0 3.7M15.5 3c-1 1.3 1 2.4 0 3.7s1 2.4 0 3.7M5 12.5c-.8 1-1 2-1 3 0 3 3.6 5.5 8 5.5s8-2.5 8-5.5c0-1-.2-2-1-3"
    const val catShopping = "M5.5 8h13l-1 13h-11zM9 10V6.5a3 3 0 0 1 6 0V10"
    const val catOther = "M6 12h.01M12 12h.01M18 12h.01"
    const val all = "M4 6h16M4 12h16M4 18h16"
    const val star = "M12 2.8l2.8 5.9 6.4.8-4.7 4.4 1.2 6.4L12 17.2l-5.7 3.1 1.2-6.4-4.7-4.4 6.4-.8z"

    private val cache = HashMap<String, ImageVector>()

    fun line(d: String, stroke: Float = 2f): ImageVector = cache.getOrPut("$stroke:$d") {
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
    }

    /** The star, filled (StarPicker's). */
    val filledStar: ImageVector by lazy {
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = PathParser().parsePathString(star).toNodes(), fill = SolidColor(Color.Black))
            .build()
    }
}

/** One of [SamFranIcons]' line icons, in the content colour unless given another. */
@Composable
fun LineIcon(
    path: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
    stroke: Float = 2f,
) {
    Icon(SamFranIcons.line(path, stroke), contentDescription, modifier.size(size), tint)
}

package com.motoroute.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape

/**
 * Shapes, from the [Radius] scale.
 *
 * Material's five slots are mapped so that the components the app actually uses
 * land where they should: chips and small buttons on `extraSmall`/`small`,
 * cards and floating plates on `medium`/`large`, sheets and dialogs on
 * `extraLarge`.
 */
internal val OpenCurvShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.Xs),
    small = RoundedCornerShape(Radius.Sm),
    medium = RoundedCornerShape(Radius.Md),
    large = RoundedCornerShape(Radius.Lg),
    extraLarge = RoundedCornerShape(Radius.Xl),
)

/** Named shapes for the app's own building blocks. */
object OpenCurvShape {
    /** Cards, floating map controls, the search bar. */
    val Plate: Shape = RoundedCornerShape(Radius.Lg)

    /** A riding button: slightly tighter, so more of the 84 dp square is target. */
    val RideButton: Shape = RoundedCornerShape(Radius.Md)

    /** A resting icon button. */
    val TapButton: Shape = RoundedCornerShape(Radius.Sm)

    /** Chips and pills. */
    val Chip: Shape = RoundedCornerShape(Radius.Full)

    /** A sheet, rounded at the top only. */
    val Sheet: Shape = RoundedCornerShape(topStart = Radius.Xl, topEnd = Radius.Xl)

    /** Dialogs. */
    val Dialog: Shape = RoundedCornerShape(Radius.Xl)

    /** HUD bars run edge to edge and stay square. */
    val HudBar: Shape = RoundedCornerShape(Radius.None)
}

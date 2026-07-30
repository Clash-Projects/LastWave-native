package com.lastwave.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** app.css: --radius: 20px; --radius-sm: 12px; --radius-xs: 8px */
val LastWaveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Beyond Material3's standard 5-step Shapes() scale — for hero-style
 *  containers (the Home stats card) that should read as a single, large,
 *  continuous surface rather than a boxy web-style card. */
val ExpressiveHeroShape = RoundedCornerShape(32.dp)
val ExpressivePillShape = RoundedCornerShape(percent = 50)
val HeroInnerShape = RoundedCornerShape(24.dp)
val StatPillShape = RoundedCornerShape(16.dp)
val ListContainerShape = RoundedCornerShape(28.dp)
val BadgePillShape = RoundedCornerShape(percent = 50)
val NowPlayingCardShape = RoundedCornerShape(18.dp)
val TrackRowShape = RoundedCornerShape(16.dp)
val ArtworkShape = RoundedCornerShape(12.dp)

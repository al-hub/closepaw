/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.closepaw.ui.icons.vendor.rounded

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Rounded.SwipeVertical: ImageVector
    get() {
        if (_swipeVertical != null) {
            return _swipeVertical!!
        }
        _swipeVertical = materialIcon(name = "Rounded.SwipeVertical") {
            materialPath {
                moveTo(0.0f, 12.0f)
                curveToRelative(0.0f, 3.22f, 1.13f, 6.18f, 3.02f, 8.5f)
                horizontalLineTo(1.75f)
                curveTo(1.34f, 20.5f, 1.0f, 20.84f, 1.0f, 21.25f)
                reflectiveCurveTo(1.34f, 22.0f, 1.75f, 22.0f)
                horizontalLineTo(5.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(-3.25f)
                curveTo(6.0f, 17.34f, 5.66f, 17.0f, 5.25f, 17.0f)
                curveToRelative(-0.41f, 0.0f, -0.75f, 0.34f, -0.75f, 0.75f)
                verticalLineToRelative(2.16f)
                curveToRelative(-1.86f, -2.11f, -3.0f, -4.88f, -3.0f, -7.91f)
                reflectiveCurveToRelative(1.14f, -5.79f, 3.0f, -7.91f)
                verticalLineToRelative(2.16f)
                curveTo(4.5f, 6.66f, 4.84f, 7.0f, 5.25f, 7.0f)
                curveTo(5.66f, 7.0f, 6.0f, 6.66f, 6.0f, 6.25f)
                verticalLineTo(3.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineTo(1.75f)
                curveTo(1.34f, 2.0f, 1.0f, 2.34f, 1.0f, 2.75f)
                reflectiveCurveTo(1.34f, 3.5f, 1.75f, 3.5f)
                horizontalLineToRelative(1.27f)
                curveTo(1.13f, 5.82f, 0.0f, 8.78f, 0.0f, 12.0f)
                close()
                moveTo(8.83f, 19.1f)
                curveToRelative(-0.26f, -0.6f, 0.09f, -1.28f, 0.73f, -1.41f)
                lineToRelative(3.58f, -0.71f)
                lineTo(8.79f, 7.17f)
                curveToRelative(-0.34f, -0.76f, 0.0f, -1.64f, 0.76f, -1.98f)
                curveToRelative(0.76f, -0.34f, 1.64f, 0.0f, 1.98f, 0.76f)
                lineToRelative(2.43f, 5.49f)
                lineToRelative(0.84f, -0.37f)
                curveToRelative(0.28f, -0.13f, 0.59f, -0.18f, 0.9f, -0.17f)
                lineToRelative(4.56f, 0.21f)
                curveToRelative(0.86f, 0.04f, 1.6f, 0.63f, 1.83f, 1.45f)
                lineToRelative(1.23f, 4.33f)
                curveToRelative(0.27f, 0.96f, -0.2f, 1.97f, -1.11f, 2.37f)
                lineToRelative(-5.63f, 2.49f)
                curveToRelative(-0.48f, 0.21f, -1.26f, 0.33f, -1.76f, 0.14f)
                lineToRelative(-5.45f, -2.27f)
                curveTo(9.13f, 19.53f, 8.93f, 19.34f, 8.83f, 19.1f)
                close()
            }
        }
        return _swipeVertical!!
    }

private var _swipeVertical: ImageVector? = null

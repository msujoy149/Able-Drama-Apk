package com.example.util

import android.content.Context
import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Adaptive {
    @Composable
    fun isTv(): Boolean {
        val context = LocalContext.current
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        return uiModeManager?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    @Composable
    fun dynamicPadding(default: Dp = 12.dp): Dp {
        val isTv = isTv()
        val screenWidth = LocalContext.current.resources.configuration.screenWidthDp
        return when {
            isTv -> default * 1.8f
            screenWidth >= 840 -> default * 1.5f
            screenWidth >= 600 -> default * 1.2f
            else -> default
        }
    }

    @Composable
    fun dynamicFontSize(defaultSize: Float): androidx.compose.ui.unit.TextUnit {
        val isTv = isTv()
        val screenWidth = LocalContext.current.resources.configuration.screenWidthDp
        val multiplier = when {
            isTv -> 1.4f
            screenWidth >= 840 -> 1.2f
            screenWidth >= 600 -> 1.1f
            else -> 1.0f
        }
        return (defaultSize * multiplier).sp
    }

    @Composable
    fun getGridColumnCount(): Int {
        val isTv = isTv()
        val screenWidth = LocalContext.current.resources.configuration.screenWidthDp
        return when {
            isTv -> 4
            screenWidth >= 1200 -> 3
            screenWidth >= 600 -> 2
            else -> 1
        }
    }
}

@Composable
fun Modifier.adaptiveClickable(
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(8.dp),
    onClick: () -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    
    val animatedScale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1.0f,
        label = "focusedScale"
    )

    return this
        .scale(animatedScale)
        .focusable(enabled = enabled, interactionSource = interactionSource)
        .clickable(
            interactionSource = interactionSource,
            indication = androidx.compose.foundation.LocalIndication.current,
            enabled = enabled,
            onClick = onClick
        )
        .then(
            if (isFocused) {
                Modifier.border(
                    BorderStroke(2.5.dp, Color(0xFFD0BCFF)),
                    shape
                )
            } else {
                Modifier
            }
        )
}

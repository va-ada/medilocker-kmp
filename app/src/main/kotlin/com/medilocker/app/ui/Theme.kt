package com.medilocker.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Clinical instrument palette. Neutrals carry a slight green-cool bias so they
// read as chosen rather than inherited from a default grey.
private val FrontLight = Color(0xFF0B6E77)
private val FrontDark = Color(0xFF45B5BE)
val AttentionLight = Color(0xFFB26A16)
val AttentionDark = Color(0xFFDC9B3D)
val SecureLight = Color(0xFF2E7D5B)
val SecureDark = Color(0xFF55B98A)

private val Light = lightColorScheme(
    primary = FrontLight,
    onPrimary = Color.White,
    // Material3 falls back to its own purple for any role left undefined —
    // which is how the floating action button turned up off-palette.
    primaryContainer = FrontLight,
    onPrimaryContainer = Color.White,
    secondaryContainer = Color(0xFFD7E5E6),
    onSecondaryContainer = Color(0xFF14201F),
    tertiary = Color(0xFF4A5B7A),
    tertiaryContainer = Color(0xFFDBE2EE),
    onTertiaryContainer = Color(0xFF14201F),
    secondary = Color(0xFF4A5B7A),
    background = Color(0xFFF6F8F7),
    onBackground = Color(0xFF14201F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14201F),
    surfaceVariant = Color(0xFFEDF1F0),
    onSurfaceVariant = Color(0xFF48595B),
    outline = Color(0xFFDCE4E2),
    error = Color(0xFFA33A2A)
)

private val Dark = darkColorScheme(
    primary = FrontDark,
    onPrimary = Color(0xFF0C1516),
    primaryContainer = FrontDark,
    onPrimaryContainer = Color(0xFF0C1516),
    secondaryContainer = Color(0xFF1E3233),
    onSecondaryContainer = Color(0xFFE9EFED),
    tertiary = Color(0xFF93AAD0),
    tertiaryContainer = Color(0xFF232E3F),
    onTertiaryContainer = Color(0xFFE9EFED),
    secondary = Color(0xFF93AAD0),
    background = Color(0xFF0C1516),
    onBackground = Color(0xFFE9EFED),
    surface = Color(0xFF131F20),
    onSurface = Color(0xFFE9EFED),
    surfaceVariant = Color(0xFF1A2829),
    onSurfaceVariant = Color(0xFFA5B5B5),
    outline = Color(0xFF243536),
    error = Color(0xFFE0705C)
)

private val Type = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp)
)

@Composable
fun MediLockerTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, content = content)
}

@Composable fun attentionColor() = if (isSystemInDarkTheme()) AttentionDark else AttentionLight
@Composable fun secureColor() = if (isSystemInDarkTheme()) SecureDark else SecureLight

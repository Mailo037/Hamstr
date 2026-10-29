package app.hamstr.ui.components

import androidx.compose.runtime.Composable

// Desktops have no common switch for this; Settings > Motion & effects covers it.
@Composable
actual fun systemReducesMotion(): Boolean = false

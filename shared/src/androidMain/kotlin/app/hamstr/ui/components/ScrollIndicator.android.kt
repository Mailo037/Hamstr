package app.hamstr.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// A finger scrolls the list itself; the phone shows no scrollbar.
@Composable
actual fun ScrollIndicator(state: LazyListState, modifier: Modifier) {}

@Composable
actual fun ScrollIndicator(state: ScrollState, modifier: Modifier) {}

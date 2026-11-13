package app.hamstr.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A scrollbar for a pointer. On the desktop a thin thumb along the end of a scroll container,
 * there only while it scrolls or the pointer is on it, and it can be dragged; a phone has its
 * finger, so there it draws nothing. Put it in a Box over the container, aligned to its end
 * (`Modifier.align(Alignment.TopEnd)`, plus the container's insets as padding).
 */
@Composable
expect fun ScrollIndicator(state: LazyListState, modifier: Modifier)

@Composable
expect fun ScrollIndicator(state: ScrollState, modifier: Modifier)

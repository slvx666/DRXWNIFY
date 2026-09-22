/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.metrolist.music.R

/** How long the row takes to turn into the search field and back. */
private const val SWAP_MS = 220

/**
 * The library's header row: filters/sort normally, a search field once the magnifier is tapped.
 * The two states cross-fade and slide into each other (the field comes in from the right, the row
 * it replaced leaves to the left) instead of snapping, so the switch reads as one movement.
 */
@Composable
fun LibrarySearchHeader(
    isSearchActive: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    keyboardController: SoftwareKeyboardController?,
    modifier: Modifier = Modifier,
    inactiveContent: @Composable RowScope.() -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            // Let the field arrive before it asks for focus, or the keyboard jumps the animation.
            kotlinx.coroutines.delay(SWAP_MS.toLong())
            runCatching { focusRequester.requestFocus() }
            keyboardController?.show()
        }
    }

    AnimatedContent(
        targetState = isSearchActive,
        transitionSpec = {
            val enter = fadeIn(tween(SWAP_MS, easing = FastOutSlowInEasing)) +
                slideInHorizontally(tween(SWAP_MS, easing = FastOutSlowInEasing)) { width ->
                    if (targetState) width / 6 else -width / 6
                }
            val exit = fadeOut(tween(SWAP_MS / 2)) +
                slideOutHorizontally(tween(SWAP_MS, easing = FastOutSlowInEasing)) { width ->
                    if (targetState) -width / 6 else width / 6
                }
            enter togetherWith exit
        },
        label = "librarySearchHeader",
        modifier = modifier.fillMaxWidth(),
    ) { active ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (active) {
                TextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.search_library),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                )

                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.close),
                        contentDescription = stringResource(R.string.close),
                    )
                }
            } else {
                inactiveContent()
            }
        }
    }
}

@Composable
fun LibrarySearchEmptyPlaceholder(
    modifier: Modifier = Modifier,
    icon: Int = R.drawable.search,
    text: String? = null,
) {
    EmptyPlaceholder(
        icon = icon,
        text = text ?: stringResource(R.string.no_results_found),
        modifier = modifier,
    )
}

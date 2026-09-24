/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.sign
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.crossfade
import com.metrolist.music.R
import com.metrolist.music.utils.CoverSaver
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A cover that can be looked at: tap opens it in the app-wide photo viewer ([PhotoViewerHost],
 * the same one as in BoneCH — it grows out of the cover and shrinks back into it), long press
 * offers to save or share it. Each screen with a cover keeps one of these.
 */
class CoverViewerState(
    private val context: android.content.Context,
    private val host: PhotoViewerHostState,
) {
    /** Id of this screen's cover in the viewer (its source position and hiding). */
    val sourceId: String = "cover-" + java.util.UUID.randomUUID().toString()

    var name by mutableStateOf("")
        private set
    var actionsFor by mutableStateOf<String?>(null)
        private set

    fun open(url: String?, name: String) {
        if (url.isNullOrBlank()) return
        this.name = name
        host.onLongPress = { item -> showActions(item.model as? String, item.contentDescription.orEmpty()) }
        host.open(
            listOf(
                PhotoViewerItem(
                    id = sourceId,
                    model = url,
                    contentDescription = name,
                    hiResModel = CoverSaver.largest(url).takeIf { it != url },
                ),
            ),
        )
    }

    /** Long press: the save/share sheet (not an immediate download). */
    fun showActions(url: String?, name: String) {
        if (url.isNullOrBlank()) return
        this.name = name
        actionsFor = url
    }

    fun hideActions() {
        actionsFor = null
    }

    fun save(url: String?, name: String) = CoverSaver.saveInBackground(context, url, name)

    fun share(url: String?, name: String) = CoverSaver.shareInBackground(context, url, name)

    @Composable
    fun Content() {
        actionsFor?.let { CoverActionsSheet(state = this, url = it) }
    }
}

@Composable
fun rememberCoverViewerState(): CoverViewerState {
    val context = LocalContext.current
    val host = LocalPhotoViewer.current
    return remember(host) { CoverViewerState(context.applicationContext, host) }
}

/** Marks the cover this state opens: the viewer grows out of it and hides it while open. */
fun Modifier.coverSource(state: CoverViewerState, cornerRadius: androidx.compose.ui.unit.Dp = 12.dp): Modifier =
    this.composed {
        photoViewerSource(LocalPhotoViewer.current, state.sourceId, cornerRadius)
    }

/**
 * Save / share sheet shown on a long press: the cover, its name and two big actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverActionsSheet(state: CoverViewerState, url: String) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun dismissThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            state.hideActions()
            action()
        }
    }
    ModalBottomSheet(
        onDismissRequest = state::hideActions,
        sheetState = sheetState,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 8.dp,
                modifier = Modifier.size(200.dp),
            ) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.height(16.dp))
            if (state.name.isNotBlank()) {
                Text(
                    text = state.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(16.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                CoverAction(
                    icon = R.drawable.download,
                    label = stringResource(R.string.save_cover_action),
                    primary = true,
                    modifier = Modifier.weight(1f),
                    onClick = { dismissThen { state.save(url, state.name) } },
                )
                CoverAction(
                    icon = R.drawable.share,
                    label = stringResource(R.string.share),
                    primary = false,
                    modifier = Modifier.weight(1f),
                    onClick = { dismissThen { state.share(url, state.name) } },
                )
            }
        }
    }
}

@Composable
private fun CoverAction(icon: Int, label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 14.dp),
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

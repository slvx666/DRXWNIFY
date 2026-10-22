/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.friends.Suggestions
import java.text.DateFormat
import java.util.Date

/**
 * "Suggest": an idea, a bug or a track that isn't found, sent to the developer (see [Suggestions]).
 * On the developer's account the screen also shows what came in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestScreen(navController: NavController) {
    val context = LocalContext.current
    var topic by rememberSaveable { mutableStateOf(Suggestions.Topic.IDEA) }
    var text by rememberSaveable { mutableStateOf("") }
    val isDeveloper = Suggestions.isDeveloper
    val inbox by Suggestions.inbox.collectAsState()
    var replyTo by remember { mutableStateOf<Suggestions.Suggestion?>(null) }
    var replied by remember { mutableStateOf(Suggestions.repliedIds(context)) }

    replyTo?.let { target ->
        var answer by rememberSaveable(target.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { replyTo = null },
            title = { Text(stringResource(R.string.suggest_reply_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        target.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    OutlinedTextField(
                        value = answer,
                        onValueChange = { answer = it.take(2000) },
                        placeholder = { Text(stringResource(R.string.suggest_reply_hint)) },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.suggest_reply_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = answer.isNotBlank(),
                    onClick = {
                        if (Suggestions.reply(context, target, answer)) {
                            replied = Suggestions.repliedIds(context)
                            Toast.makeText(context, R.string.suggest_reply_sent, Toast.LENGTH_SHORT).show()
                        }
                        replyTo = null
                    },
                    shape = com.metrolist.music.ui.theme.tuiButtonShape(),
                ) { Text(stringResource(R.string.suggest_send)) }
            },
            dismissButton = {
                TextButton(onClick = { replyTo = null }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (isDeveloper) {
        DisposableEffect(Unit) {
            Suggestions.openInbox(context)
            Suggestions.markSeen(context)
            onDispose {
                Suggestions.markSeen(context)
                Suggestions.closeInbox()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.suggest_title)) },
            navigationIcon = { IconButton(onClick = navController::navigateUp) { Icon(painterResource(R.drawable.arrow_back), null) } },
        )
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "intro") {
                Text(
                    stringResource(R.string.suggest_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "topics") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Suggestions.Topic.IDEA to R.string.suggest_topic_idea,
                        Suggestions.Topic.BUG to R.string.suggest_topic_bug,
                        Suggestions.Topic.TRACK to R.string.suggest_topic_track,
                        Suggestions.Topic.OTHER to R.string.suggest_topic_other,
                    ).forEach { (t, label) ->
                        FilterChip(selected = topic == t, onClick = { topic = t }, label = { Text(stringResource(label)) })
                    }
                }
            }
            item(key = "text") {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(2000) },
                    placeholder = {
                        Text(
                            stringResource(
                                when (topic) {
                                    Suggestions.Topic.IDEA -> R.string.suggest_hint_idea
                                    Suggestions.Topic.BUG -> R.string.suggest_hint_bug
                                    Suggestions.Topic.TRACK -> R.string.suggest_hint_track
                                    Suggestions.Topic.OTHER -> R.string.suggest_hint_other
                                },
                            ),
                        )
                    },
                    minLines = 5,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                )
            }
            item(key = "send") {
                Button(
                    enabled = text.isNotBlank(),
                    onClick = {
                        if (Suggestions.send(context, topic, text)) {
                            text = ""
                            Toast.makeText(context, R.string.suggest_sent, Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, R.string.suggest_limited, Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = com.metrolist.music.ui.theme.tuiButtonShape(),
                ) { Text(stringResource(R.string.suggest_send)) }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.suggest_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
            if (isDeveloper) {
                item(key = "inbox_title") {
                    Text(
                        stringResource(R.string.suggest_inbox, inbox.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                items(inbox, key = { it.id }) { s ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                listOfNotNull(
                                    stringResource(
                                        when (s.topic) {
                                            Suggestions.Topic.IDEA -> R.string.suggest_topic_idea
                                            Suggestions.Topic.BUG -> R.string.suggest_topic_bug
                                            Suggestions.Topic.TRACK -> R.string.suggest_topic_track
                                            Suggestions.Topic.OTHER -> R.string.suggest_topic_other
                                        },
                                    ),
                                    s.name ?: stringResource(R.string.suggest_anonymous),
                                    s.version?.let { "v$it" },
                                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.at)),
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(6.dp))
                            SelectionContainer { Text(s.text, style = MaterialTheme.typography.bodyMedium) }
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (s.id in replied) {
                                    Text(
                                        stringResource(R.string.suggest_replied),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.weight(1f),
                                    )
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                                TextButton(onClick = { replyTo = s }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) {
                                    Text(stringResource(R.string.suggest_reply))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

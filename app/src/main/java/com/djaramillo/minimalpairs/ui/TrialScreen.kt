package com.djaramillo.minimalpairs.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.djaramillo.minimalpairs.R
import com.djaramillo.minimalpairs.domain.Ipa
import com.djaramillo.minimalpairs.domain.model.Feedback

/**
 * One trial: progress, the big play/replay button, two word buttons, feedback
 * per `plan.feedback`, Next. Everything sits in the lower two thirds so it is
 * reachable one-handed in portrait.
 */
@Composable
fun TrialScreen(
    ui: TrialUi?,
    onPlay: () -> Unit,
    onAnswer: (String) -> Unit,
    onHear: (String) -> Unit,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    onAbandon: () -> Unit,
) {
    var confirmQuit by remember { mutableStateOf(false) }
    BackHandler { confirmQuit = true }
    if (confirmQuit) {
        AlertDialog(
            onDismissRequest = { confirmQuit = false },
            title = { Text(stringResource(R.string.trial_quit_title)) },
            text = { Text(stringResource(R.string.trial_quit_text)) },
            confirmButton = { TextButton(onClick = { confirmQuit = false; onAbandon() }) { Text(stringResource(R.string.trial_quit_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmQuit = false }) { Text(stringResource(R.string.trial_quit_cancel)) } },
        )
    }

    Scaffold { padding ->
        if (ui == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val answered = ui.phase as? TrialPhase.Answered
        val listening = ui.phase is TrialPhase.Listening
        val failed = ui.phase as? TrialPhase.Failed
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.trial_progress, ui.index, ui.total), style = MaterialTheme.typography.titleLarge)
                Text(ui.contrastLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.weight(0.5f))

            // Big round play / replay button.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Button(
                    onClick = onPlay,
                    enabled = listening,
                    shape = CircleShape,
                    modifier = Modifier.size(132.dp),
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    if (ui.phase is TrialPhase.Loading) {
                        CircularProgressIndicator(Modifier.size(40.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.trial_play_cd),
                            modifier = Modifier.size(64.dp),
                        )
                    }
                }
            }
            Text(
                when {
                    ui.phase is TrialPhase.Loading -> stringResource(R.string.trial_preparing)
                    ui.replays > 0 -> stringResource(R.string.trial_replays, ui.replays)
                    else -> " "
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.weight(0.6f))

            // Feedback line.
            val feedbackHeight = 56.dp
            Box(Modifier.fillMaxWidth().height(feedbackHeight), contentAlignment = Alignment.Center) {
                val problem = failed?.message ?: ui.error
                if (answered != null) FeedbackLine(answered.correct, ui.feedback)
                else if (problem != null) Text(
                    stringResource(R.string.trial_error, problem),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Two word buttons side by side.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WordButton(ui, ui.leftWord, ui.leftIpa, onAnswer, onHear)
                WordButton(ui, ui.rightWord, ui.rightIpa, onAnswer, onHear)
            }

            Spacer(Modifier.height(12.dp))
            if (answered != null && ui.feedback == Feedback.FULL) {
                Text(
                    stringResource(R.string.trial_tap_to_hear),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(8.dp))
            if (failed != null) {
                // A clip that cannot be played: decode it again or draw a replacement.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onRetry, modifier = Modifier.weight(1f).height(60.dp)) {
                        Text(stringResource(R.string.trial_retry), style = MaterialTheme.typography.titleMedium)
                    }
                    Button(onClick = onSkip, modifier = Modifier.weight(1f).height(60.dp)) {
                        Text(stringResource(R.string.trial_skip), style = MaterialTheme.typography.titleMedium)
                    }
                }
            } else {
                Button(
                    onClick = onNext,
                    enabled = answered != null,
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                ) {
                    Text(
                        stringResource(if (ui.isLast) R.string.trial_finish else R.string.trial_next),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun FeedbackLine(correct: Boolean, feedback: Feedback) {
    val color = if (correct) successColor() else MaterialTheme.colorScheme.error
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (correct) Icons.Filled.Check else Icons.Filled.Close,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(36.dp),
        )
        if (feedback != Feedback.MINIMAL) {
            Spacer(Modifier.padding(6.dp))
            Text(
                stringResource(if (correct) R.string.trial_correct else R.string.trial_wrong),
                style = MaterialTheme.typography.headlineSmall,
                color = color,
            )
        }
    }
}

@Composable
private fun RowScope.WordButton(
    ui: TrialUi,
    word: String,
    ipa: Ipa.Highlight?,
    onAnswer: (String) -> Unit,
    onHear: (String) -> Unit,
) {
    val answered = ui.phase as? TrialPhase.Answered
    val listening = ui.phase is TrialPhase.Listening
    val isTarget = word == ui.target
    val chosen = answered?.chosen == word
    val success = successColor()
    val border: Color? = when {
        answered == null -> null
        isTarget -> success
        chosen -> MaterialTheme.colorScheme.error
        else -> null
    }
    val showIpa = answered != null && ui.feedback != Feedback.MINIMAL && ipa != null
    val enabled = listening || (answered != null && ui.feedback == Feedback.FULL)
    OutlinedButton(
        onClick = { if (listening) onAnswer(word) else if (answered != null) onHear(word) },
        enabled = enabled,
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 96.dp),
        border = BorderStroke(if (border != null) 3.dp else 1.dp, border ?: MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (answered != null) 1f else 0.5f),
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 8.dp)) {
            Text(word, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            if (showIpa) {
                Spacer(Modifier.height(4.dp))
                Text(
                    ipaAnnotated(ipa!!, MaterialTheme.colorScheme.tertiary),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

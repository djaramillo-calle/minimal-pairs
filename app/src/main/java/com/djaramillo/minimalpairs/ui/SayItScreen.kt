package com.djaramillo.minimalpairs.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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

/**
 * The Say-it block (docs/DESIGN.md "Say it"): one pair at a time, both words
 * with IPA (differing sound highlighted as in the Trial screen), a "hear"
 * button per word (model voice), one big record button per word with the
 * states idle / recording / scoring / scored, playback of the learner's own
 * recording, the per-word verdict and the pair's points, Next, and "Skip the
 * rest". Back asks before ending the block; scored pairs are always kept.
 */
@Composable
fun SayItScreen(
    ui: SayItUi?,
    onRecord: (String) -> Unit,
    onHear: (String) -> Unit,
    onPlayRecording: (String) -> Unit,
    onNext: () -> Unit,
    onSkipRest: () -> Unit,
) {
    var confirmEnd by remember { mutableStateOf(false) }
    BackHandler { confirmEnd = true }
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text(stringResource(R.string.sayit_end_title)) },
            text = { Text(stringResource(R.string.sayit_end_text)) },
            confirmButton = { TextButton(onClick = { confirmEnd = false; onSkipRest() }) { Text(stringResource(R.string.sayit_end_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text(stringResource(R.string.sayit_end_cancel)) } },
        )
    }

    Scaffold { padding ->
        if (ui == null || ui.preparing || ui.a == null || ui.b == null) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.sayit_preparing), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(24.dp))
                TextButton(onClick = onSkipRest) { Text(stringResource(R.string.sayit_skip_rest)) }
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(stringResource(R.string.sayit_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.sayit_progress, ui.index, ui.total),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(ui.contrastLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        stringResource(R.string.sayit_points_so_far, ui.pointsSoFar, ui.pairsDone * 2),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.sayit_instructions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            WordCard(ui, ui.a, onRecord, onHear, onPlayRecording)
            Spacer(Modifier.height(12.dp))
            WordCard(ui, ui.b, onRecord, onHear, onPlayRecording)

            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                val points = ui.points
                if (points != null) {
                    Text(
                        stringResource(R.string.sayit_pair_points, points),
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (points == 2) successColor() else if (points == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                    )
                } else if (ui.error != null) {
                    Text(
                        stringResource(R.string.sayit_error, ui.error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onNext,
                enabled = ui.bothScored && !ui.busy,
                modifier = Modifier.fillMaxWidth().height(60.dp),
            ) {
                Text(
                    stringResource(if (ui.index >= ui.total) R.string.sayit_finish else R.string.trial_next),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { confirmEnd = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(R.string.sayit_skip_rest))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun WordCard(
    ui: SayItUi,
    w: SayWordUi,
    onRecord: (String) -> Unit,
    onHear: (String) -> Unit,
    onPlayRecording: (String) -> Unit,
) {
    val phase = w.phase
    val scored = phase as? SayWordPhase.Scored
    val recording = phase as? SayWordPhase.Recording
    val border: Color? = when {
        scored == null -> null
        scored.point == 1 -> successColor()
        else -> MaterialTheme.colorScheme.error
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        border = border?.let { BorderStroke(3.dp, it) },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(w.word, style = MaterialTheme.typography.headlineMedium)
                    if (w.ipa != null) {
                        Text(ipaAnnotated(w.ipa, MaterialTheme.colorScheme.tertiary), style = MaterialTheme.typography.titleMedium)
                    }
                }
                // "Hear" the model voice; disabled when the clip is not in the pack.
                OutlinedButton(
                    onClick = { onHear(w.word) },
                    enabled = w.hearEnabled && recording == null,
                    modifier = Modifier.height(48.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.padding(2.dp))
                    Text(stringResource(R.string.sayit_hear))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RecordButton(ui, w, onRecord)
                Column(Modifier.weight(1f)) {
                    when (phase) {
                        SayWordPhase.Idle -> Text(
                            stringResource(if (w.attempts == 0) R.string.sayit_tap_to_record else R.string.sayit_tap_to_record_again),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        is SayWordPhase.Recording -> {
                            Text(
                                stringResource(if (phase.speaking) R.string.sayit_listening else R.string.sayit_recording),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(progress = { phase.level }, modifier = Modifier.fillMaxWidth())
                        }
                        SayWordPhase.Scoring -> {
                            Text(stringResource(R.string.sayit_scoring), style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        SayWordPhase.NoSpeech -> Text(
                            stringResource(R.string.sayit_no_speech),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        is SayWordPhase.Error -> Text(
                            stringResource(R.string.sayit_mic_error, phase.message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        is SayWordPhase.Scored -> ScoredText(w.word, phase)
                    }
                }
            }
            if (w.hasRecording && recording == null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { onPlayRecording(w.word) }, enabled = !ui.busy, modifier = Modifier.height(44.dp)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.padding(2.dp))
                    Text(stringResource(R.string.sayit_play_recording))
                }
            }
        }
    }
}

@Composable
private fun ScoredText(word: String, s: SayWordPhase.Scored) {
    val r = s.result
    val heardText = when (r.heard) {
        word -> stringResource(R.string.sayit_heard_as, word)
        ProductionScorerUnknown -> stringResource(R.string.sayit_heard_unclear)
        else -> stringResource(R.string.sayit_heard_as, r.heard)
    }
    Text(
        "$heardText · ${stringResource(R.string.sayit_accuracy, r.acc)}",
        style = MaterialTheme.typography.bodyLarge,
        color = if (s.point == 1) successColor() else MaterialTheme.colorScheme.onSurface,
    )
    val hint = when (val h = s.hint) {
        SayHint.Good -> stringResource(R.string.sayit_hint_good)
        is SayHint.BelowThreshold -> stringResource(R.string.sayit_hint_below, h.acc, h.threshold)
        is SayHint.HeardOther -> when {
            h.intended.isNotEmpty() && h.other.isNotEmpty() -> stringResource(R.string.sayit_hint_heard_other, h.intended, h.other)
            h.intended.isEmpty() -> stringResource(R.string.sayit_hint_extra, h.other)
            else -> stringResource(R.string.sayit_hint_missing, h.intended)
        }
        SayHint.Unclear -> stringResource(R.string.sayit_hint_unclear)
        SayHint.NoSpeech -> stringResource(R.string.sayit_hint_no_speech)
        null -> ""
    }
    if (hint.isNotEmpty()) {
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The `heard` value for a tie / no vote (docs/CONTRACT.md). */
private const val ProductionScorerUnknown = "?"

@Composable
private fun RecordButton(ui: SayItUi, w: SayWordUi, onRecord: (String) -> Unit) {
    val phase = w.phase
    val recording = phase is SayWordPhase.Recording
    val canStart = !ui.busy && (phase is SayWordPhase.Idle || phase is SayWordPhase.NoSpeech || phase is SayWordPhase.Error)
    val enabled = recording || canStart
    val container = when {
        recording -> MaterialTheme.colorScheme.error
        phase is SayWordPhase.Scored -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    Button(
        onClick = { onRecord(w.word) },
        enabled = enabled,
        shape = CircleShape,
        modifier = Modifier.size(96.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        when {
            phase is SayWordPhase.Scoring -> CircularProgressIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.onPrimary)
            recording -> Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.onError, RoundedCornerShape(4.dp)))
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(24.dp).background(if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(if (phase is SayWordPhase.Scored) R.string.sayit_recorded else R.string.sayit_record),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

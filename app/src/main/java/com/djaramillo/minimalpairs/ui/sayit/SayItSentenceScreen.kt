package com.djaramillo.minimalpairs.ui.sayit

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.djaramillo.minimalpairs.R
import com.djaramillo.minimalpairs.storage.DataFolder
import com.djaramillo.minimalpairs.ui.SectionCard

/**
 * One word of the Say-it run: the sentence in large type with the target word
 * picked out, the IPA under it, the coach's model clip, the record control and
 * then the comparison — his attempt and the model, back to back.
 *
 * The comparison is the centrepiece on purpose. The app scores nothing and
 * calls nothing (docs/CONTRACT.md), so hearing the two readings one after the
 * other is the only immediate feedback it can honestly offer; the numbers come
 * back from the cloud coach after the next sync.
 */
@Composable
fun SayItSentenceScreen(
    ui: SayItSentenceUi,
    onPlayModel: () -> Unit,
    onRecord: () -> Unit,
    onCompare: () -> Unit,
    onNext: () -> Unit,
    onLeave: () -> Unit,
    onAndroidSettings: () -> Unit,
) {
    var confirmLeave by remember { mutableStateOf(false) }
    BackHandler { confirmLeave = true }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.sayit_sentence_leave_title)) },
            text = { Text(stringResource(R.string.sayit_sentence_leave_text)) },
            confirmButton = {
                // Leaving ends the word, and ending the word deletes the cache
                // file the folder write is still reading: while it runs, the
                // only honest answer is to wait, exactly as Next and Record do.
                TextButton(
                    onClick = { confirmLeave = false; onLeave() },
                    enabled = !ui.saving,
                ) {
                    Text(stringResource(R.string.sayit_sentence_leave_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) {
                    Text(stringResource(R.string.sayit_sentence_leave_cancel))
                }
            },
        )
    }

    val word = ui.word
    Scaffold { padding ->
        if (word == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
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
                Text(stringResource(R.string.sayit_sentence_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.sayit_sentence_progress, ui.position, ui.total),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                highlightedSentence(word.sentence, word.word, MaterialTheme.colorScheme.primary),
                style = MaterialTheme.typography.headlineMedium,
            )
            val ipa = ipaLine(word.ipa)
            if (ipa != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.sayit_sentence_ipa, ipa),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(20.dp))
            ModelRow(ui, onPlayModel)

            // The card sits above the control rather than replacing it. Record
            // is the only route to the rationale and the permission request, so
            // hiding it made a refusal a latch that nothing could undo.
            if (ui.micDenied) {
                Spacer(Modifier.height(20.dp))
                MicOff(onAndroidSettings)
            }
            Spacer(Modifier.height(20.dp))
            RecordRow(ui, onRecord)

            if (ui.attempt) {
                Spacer(Modifier.height(20.dp))
                CompareCard(ui, onCompare)
            }

            val notice = ui.notice
            if (notice != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    noticeText(notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onNext,
                enabled = !ui.recording && !ui.saving,
                modifier = Modifier.fillMaxWidth().height(60.dp),
            ) {
                Text(
                    stringResource(if (ui.last) R.string.sayit_sentence_finish else R.string.sayit_sentence_next),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Play model: replayable, and honest about a clip that is not there or will not play. */
@Composable
private fun ModelRow(ui: SayItSentenceUi, onPlayModel: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = onPlayModel,
            enabled = ui.clipReady && !ui.recording,
            modifier = Modifier.height(52.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.sayit_sentence_play_model))
        }
        Spacer(Modifier.width(12.dp))
        val hint = when {
            ui.clipBusy -> stringResource(R.string.sayit_sentence_clip_loading)
            !ui.clipReady -> stringResource(R.string.sayit_sentence_no_clip)
            ui.playing == ComparePart.MODEL -> stringResource(R.string.sayit_sentence_now_playing)
            else -> ""
        }
        if (hint.isNotEmpty()) {
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The big record control: press to start, press to stop. */
@Composable
private fun RecordRow(ui: SayItSentenceUi, onRecord: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onRecord,
            enabled = !ui.saving && ui.folderProblem == null,
            shape = CircleShape,
            modifier = Modifier.size(96.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (ui.recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            ),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            if (ui.recording) {
                Box(Modifier.size(30.dp).background(MaterialTheme.colorScheme.onError, RoundedCornerShape(4.dp)))
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(26.dp).background(MaterialTheme.colorScheme.onPrimary, CircleShape))
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.sayit_sentence_record), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            when {
                ui.saving -> Text(stringResource(R.string.sayit_sentence_saving), style = MaterialTheme.typography.bodyLarge)
                ui.recording -> Text(stringResource(R.string.sayit_sentence_recording), style = MaterialTheme.typography.bodyLarge)
                ui.attempt -> Text(stringResource(R.string.sayit_sentence_record_again), style = MaterialTheme.typography.bodyMedium)
                else -> Text(stringResource(R.string.sayit_sentence_read_aloud), style = MaterialTheme.typography.bodyMedium)
            }
            if (ui.attempt && !ui.attemptSaved && !ui.saving) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.sayit_sentence_not_in_folder),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * The comparison: his attempt, then the model, with the one that is sounding
 * marked, and a control to hear the pair again.
 */
@Composable
private fun CompareCard(ui: SayItSentenceUi, onCompare: () -> Unit) {
    SectionCard(stringResource(R.string.sayit_sentence_compare)) {
        ComparePartRow(stringResource(R.string.sayit_sentence_your_attempt), ui.playing == ComparePart.ATTEMPT)
        Spacer(Modifier.height(6.dp))
        ComparePartRow(
            stringResource(R.string.sayit_sentence_the_model),
            ui.playing == ComparePart.MODEL,
            muted = !ui.clipReady,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onCompare, modifier = Modifier.height(48.dp)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.sayit_sentence_compare_again))
        }
    }
}

@Composable
private fun ComparePartRow(label: String, playing: Boolean, muted: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(12.dp).background(
                if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                CircleShape,
            )
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        if (playing) {
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.sayit_sentence_now_playing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Microphone refused: the mode carries on without it and says how to turn it back on. */
@Composable
private fun MicOff(onAndroidSettings: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.sayit_sentence_mic_off), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.sayit_sentence_mic_how), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onAndroidSettings, modifier = Modifier.height(48.dp)) {
                Text(stringResource(R.string.sayit_sentence_mic_settings))
            }
        }
    }
}

/**
 * The short page the mode opens on when there is nothing to drill: no
 * `words.json`, an unusable one, or no active word left in it. It names what
 * produces the list rather than inventing a word (docs/CONTRACT.md).
 */
@Composable
fun SayItEmptyScreen(ui: SayItSentenceUi, onHome: () -> Unit) {
    BackHandler(onBack = onHome)
    NoticePage(
        title = stringResource(R.string.sayit_sentence_empty_title),
        body = stringResource(R.string.sayit_sentence_empty_text),
        extra = ui.listMessage?.let { stringResource(R.string.sayit_sentence_empty_problem, it) },
        actionLabel = stringResource(R.string.sayit_sentence_home),
        onAction = onHome,
        // Every remaining word waiting for a person is the expected end state of
        // a word set, and this page is then the only one he ever reaches: the
        // done page is behind a run there is nothing to run (docs/CONTRACT.md).
        tutor = ui.tutor.map { it.word },
    )
}

/**
 * The folder is not chosen, its permission is gone or it cannot be written.
 * The mode says so and points at Settings rather than pretending to record
 * into nothing.
 */
@Composable
fun SayItFolderScreen(status: DataFolder.Status, onSettings: () -> Unit, onHome: () -> Unit) {
    BackHandler(onBack = onHome)
    NoticePage(
        title = stringResource(R.string.sayit_sentence_folder_title),
        body = when (status) {
            DataFolder.Status.PermissionLost -> stringResource(R.string.sayit_sentence_folder_lost)
            DataFolder.Status.Unwritable -> stringResource(R.string.sayit_sentence_folder_unwritable)
            else -> stringResource(R.string.sayit_sentence_folder_not_chosen)
        },
        extra = null,
        actionLabel = stringResource(R.string.sayit_sentence_open_settings),
        onAction = onSettings,
        secondLabel = stringResource(R.string.sayit_sentence_home),
        onSecond = onHome,
    )
}

@Composable
private fun NoticePage(
    title: String,
    body: String,
    extra: String?,
    actionLabel: String,
    onAction: () -> Unit,
    secondLabel: String? = null,
    onSecond: (() -> Unit)? = null,
    tutor: List<String> = emptyList(),
) {
    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge)
            if (extra != null) {
                Spacer(Modifier.height(10.dp))
                Text(extra, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (tutor.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                SectionCard(stringResource(R.string.sayit_sentence_done_tutor)) {
                    tutor.forEach { w ->
                        Text(w, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.sayit_sentence_done_tutor_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAction, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(actionLabel, style = MaterialTheme.typography.titleMedium)
            }
            if (secondLabel != null && onSecond != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onSecond, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(secondLabel, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** The mode is still reading the folder. */
@Composable
fun SayItLoadingScreen() {
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.sayit_sentence_loading),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun noticeText(notice: SayItNotice): String = when (notice.trouble) {
    SayItTrouble.RECORDER -> notice.detail ?: stringResource(R.string.sayit_sentence_trouble_recorder)
    SayItTrouble.TOO_SHORT -> stringResource(R.string.sayit_sentence_trouble_too_short)
    SayItTrouble.INTERRUPTED -> stringResource(R.string.sayit_sentence_trouble_interrupted)
    SayItTrouble.NOT_SAVED -> stringResource(R.string.sayit_sentence_trouble_not_saved)
    SayItTrouble.PLAYBACK -> notice.detail ?: stringResource(R.string.sayit_sentence_trouble_playback)
}

/**
 * The IPA line to draw under the sentence, or null when the word has none.
 *
 * An absent `ipa` key and an empty one must be treated alike: the coach writes
 * `"ipa": ""` for a word it has no transcription for, and `explicitNulls =
 * false` maps only the absent key to null, so a nullability check alone leaves
 * the format string drawing a bare "//" under every sentence of every run.
 */
fun ipaLine(ipa: String?): String? = ipa?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The sentence with its target word picked out in [colour].
 *
 * The sentence is drawn exactly as `words.json` wrote it: this only styles a
 * range of it, and the string the coach scores against is the [SayItWord]'s own,
 * never anything rebuilt from what is on screen. A word that somehow does not
 * occur — the coach's `word` and `sentence` disagreeing — is not worth a crash,
 * so the sentence is then shown plain.
 */
@Composable
fun highlightedSentence(sentence: String, word: String, colour: Color): AnnotatedString {
    val range = wordRange(sentence, word)
    if (range == null) return AnnotatedString(sentence)
    return buildAnnotatedString {
        append(sentence.substring(0, range.first))
        withStyle(SpanStyle(color = colour, fontWeight = FontWeight.Bold)) {
            append(sentence.substring(range.first, range.last + 1))
        }
        append(sentence.substring(range.last + 1))
    }
}

/**
 * Where [word] sits inside [sentence], matched whole and without regard to
 * case, or null when it is not there. An apostrophe counts as part of a word so
 * that "don" does not light up inside "don't".
 */
fun wordRange(sentence: String, word: String): IntRange? {
    val needle = word.trim()
    if (needle.isEmpty()) return null
    var from = 0
    while (from <= sentence.length - needle.length) {
        val at = sentence.indexOf(needle, from, ignoreCase = true)
        if (at < 0) return null
        val before = sentence.getOrNull(at - 1)
        val after = sentence.getOrNull(at + needle.length)
        if (!isWordChar(before) && !isWordChar(after)) return at until (at + needle.length)
        from = at + 1
    }
    return null
}

private fun isWordChar(c: Char?): Boolean =
    c != null && (c.isLetterOrDigit() || c == '\'' || c == '’')

package com.djaramillo.minimalpairs.ui.sayit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.djaramillo.minimalpairs.R
import com.djaramillo.minimalpairs.ui.SectionCard
import kotlin.math.roundToInt

/**
 * The end of a Say-it run: what was practised, the last score the cloud coach
 * already has for each word, and today's own score beside it, so movement
 * inside one sitting is visible without waiting for a sync.
 *
 * The two columns come from two different places and the page keeps them apart.
 * The coach's figure is whatever `results.json` held when the run opened — the
 * history, which the app never writes. Today's figure is what Azure returned
 * for this sitting's recording, scored on this phone against the word's own
 * sentence; a word whose attempt could not be scored says so rather than
 * showing a number the app made up (docs/CONTRACT.md).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SayItDoneScreen(ui: SayItSentenceUi, onHome: () -> Unit) {
    BackHandler(onBack = onHome)
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.sayit_sentence_done_title)) }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard(stringResource(R.string.sayit_sentence_done_practised)) {
                if (ui.rows.isEmpty()) {
                    Text(stringResource(R.string.sayit_sentence_done_nothing), style = MaterialTheme.typography.bodyLarge)
                }
                ui.rows.forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(row.word, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            when {
                                row.lastScore != null ->
                                    stringResource(R.string.sayit_sentence_done_last_score, row.lastScore.roundToInt())
                                else -> stringResource(R.string.sayit_sentence_done_no_score)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                        )
                    }
                    // Today's instant score, next to the coach's, so a session
                    // that moved a word shows it while he is still in it.
                    val todayPron = row.today?.assessment?.pron ?: row.today?.assessment?.accuracy
                    if (row.recorded) {
                        Text(
                            when {
                                todayPron != null ->
                                    stringResource(R.string.sayit_sentence_done_today, todayPron.roundToInt())
                                else -> stringResource(R.string.sayit_sentence_done_today_pending)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (todayPron != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!row.recorded) {
                        Text(
                            stringResource(R.string.sayit_sentence_done_not_recorded),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.sayit_sentence_done_pending),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Words the coach marked `tutor`: still failing after three weeks, so
            // they wait for a person rather than another recording. They are shown
            // greyed, outside the run, and were never recordable (docs/CONTRACT.md).
            if (ui.tutor.isNotEmpty()) {
                SectionCard(stringResource(R.string.sayit_sentence_done_tutor)) {
                    ui.tutor.forEach { w ->
                        Text(
                            w.word,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.sayit_sentence_done_tutor_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val swept = ui.swept
            if (swept != null && swept > 0) {
                Text(
                    stringResource(R.string.sayit_sentence_done_swept, swept),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(4.dp))
            Button(onClick = onHome, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                Text(stringResource(R.string.sayit_sentence_home), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

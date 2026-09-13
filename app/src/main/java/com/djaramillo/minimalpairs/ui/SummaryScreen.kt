package com.djaramillo.minimalpairs.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.djaramillo.minimalpairs.R
import com.djaramillo.minimalpairs.storage.DataFolder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(ui: SummaryUi, onHome: () -> Unit, onAgain: () -> Unit) {
    BackHandler(onBack = onHome)
    val s = ui.record.summary
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.summary_title)) }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Big(stringResource(R.string.summary_overall), pctText(s.pct), stringResource(R.string.summary_correct_of, s.correct, s.trials))
                Big(
                    stringResource(R.string.summary_untrained),
                    if (s.untrainedTrials > 0) pctText(s.untrainedPct) else stringResource(R.string.summary_no_untrained),
                    stringResource(R.string.summary_correct_of, s.untrainedCorrect, s.untrainedTrials),
                )
                Big(stringResource(R.string.summary_mean_rt), stringResource(R.string.summary_ms, s.meanRtMs), "")
            }
            if (s.untrainedShortfall > 0) {
                Text(
                    stringResource(R.string.summary_shortfall, s.untrainedShortfall),
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            SectionCard(stringResource(R.string.summary_per_contrast)) {
                ui.rows.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            if (r.untrainedPct == null) stringResource(R.string.summary_no_untrained) else pctText(r.untrainedPct),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.padding(6.dp))
                        Text(
                            stringResource(R.string.summary_trials, r.trials),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Rungs that moved when this session was applied (docs/ADAPTATION.md "The level ladder").
            if (ui.levelChanges.isNotEmpty()) {
                SectionCard(stringResource(R.string.summary_levels)) {
                    ui.levelChanges.forEach { c ->
                        Text(
                            stringResource(R.string.summary_level_change, c.label, c.to, c.from),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (c.to > c.from) successColor() else MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }

            SectionCard(stringResource(R.string.summary_file)) {
                Text(ui.fileName, style = MaterialTheme.typography.bodyLarge)
                val where = when (ui.folderOutcome) {
                    DataFolder.WriteOutcome.WRITTEN -> stringResource(R.string.summary_written_folder)
                    DataFolder.WriteOutcome.ALREADY_THERE -> stringResource(R.string.summary_already_there)
                    DataFolder.WriteOutcome.FOLDER_UNAVAILABLE -> stringResource(R.string.summary_mirrored_only)
                    DataFolder.WriteOutcome.FAILED -> stringResource(R.string.summary_write_failed)
                }
                Text(where, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!ui.mirrored) {
                    Text(stringResource(R.string.summary_not_mirrored), color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onHome, modifier = Modifier.weight(1f).height(60.dp)) {
                    Text(stringResource(R.string.summary_home), style = MaterialTheme.typography.titleMedium)
                }
                Button(onClick = onAgain, modifier = Modifier.weight(1f).height(60.dp)) {
                    Text(stringResource(R.string.summary_again), style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Big(label: String, value: String, sub: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMedium)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

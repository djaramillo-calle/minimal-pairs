package com.djaramillo.minimalpairs.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.djaramillo.minimalpairs.R
import com.djaramillo.minimalpairs.clips.ClipDownloader
import com.djaramillo.minimalpairs.domain.model.PlanSource
import com.djaramillo.minimalpairs.storage.DataFolder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    ui: HomeUi,
    download: ClipDownloader.State,
    onStart: () -> Unit,
    onSettings: () -> Unit,
    onSayIt: () -> Unit,
    onDownload: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSettings, modifier = Modifier.padding(end = 4.dp)) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.home_settings_cd))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val canStart = !ui.loading && ui.schedulerError == null
            Button(
                onClick = onStart,
                enabled = canStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp),
                colors = ButtonDefaults.buttonColors(),
            ) {
                if (ui.loading) {
                    CircularProgressIndicator(Modifier.width(28.dp).height(28.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.home_loading), style = MaterialTheme.typography.titleLarge)
                } else {
                    Text(stringResource(R.string.home_start), style = MaterialTheme.typography.headlineSmall)
                }
            }

            // The Say-it sentence mode (docs/CONTRACT.md, "`sayit.zip`"): the
            // coach's flagged sentences, read aloud into the synced folder.
            // Second, because the perception drill is still the day's main work.
            OutlinedButton(
                onClick = onSayIt,
                enabled = !ui.loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
            ) {
                Text(stringResource(R.string.sayit_sentence_start), style = MaterialTheme.typography.titleLarge)
            }
            Text(
                if (ui.sayItWords > 0) stringResource(R.string.sayit_sentence_home_count, ui.sayItWords)
                else stringResource(R.string.sayit_sentence_home_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat(stringResource(R.string.home_streak), stringResource(R.string.home_streak_days, ui.streakDays))
                Stat(stringResource(R.string.home_sessions), ui.sessionsCompleted.toString())
                Stat(
                    stringResource(R.string.home_week_minutes),
                    if (ui.weeklyTarget > 0) stringResource(R.string.home_week_minutes_value, ui.weekMinutes, ui.weeklyTarget)
                    else stringResource(R.string.home_week_minutes_no_target, ui.weekMinutes),
                )
            }
            // Consistency (docs/ADAPTATION.md): this Monday–Sunday week against the coach's weekly target.
            if (ui.weeklyTarget > 0) {
                LinearProgressIndicator(
                    progress = { (ui.weekMinutes.toFloat() / ui.weeklyTarget).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (ui.longestStreak > 0) {
                Text(
                    stringResource(R.string.home_longest_streak, ui.longestStreak),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }

            // Coach note / plan source.
            SectionCard(stringResource(R.string.home_coach_note)) {
                when {
                    ui.planSource == PlanSource.OVERRIDE -> Text(stringResource(R.string.home_plan_override))
                    ui.note != null -> Text(ui.note.take(240), style = MaterialTheme.typography.bodyLarge)
                    else -> Text(stringResource(R.string.home_no_plan), style = MaterialTheme.typography.bodyLarge)
                }
                if (ui.planMessage != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.home_plan_problem, ui.planMessage),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // Warnings.
            when (ui.folderStatus) {
                is DataFolder.Status.Ok -> {}
                DataFolder.Status.NotChosen -> Warning(stringResource(R.string.home_folder_not_chosen), stringResource(R.string.home_open_settings), onSettings)
                DataFolder.Status.PermissionLost -> Warning(stringResource(R.string.home_folder_lost), stringResource(R.string.home_open_settings), onSettings)
                DataFolder.Status.Unwritable -> Warning(stringResource(R.string.home_folder_unwritable), stringResource(R.string.home_open_settings), onSettings)
            }
            if (!ui.loading && !ui.packComplete) {
                val text = if (ui.packEmpty) stringResource(R.string.home_pack_missing)
                else stringResource(R.string.home_pack_incomplete, ui.packWordsAvailable, ui.packWordsTotal)
                if (download.isRunning) {
                    SectionCard(stringResource(R.string.home_download_progress, downloadLabel(download))) {
                        val d = download as? ClipDownloader.State.Downloading
                        if (d?.total != null && d.total > 0) {
                            LinearProgressIndicator(progress = { d.bytes.toFloat() / d.total }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    Warning(text, if (ui.canDownload) stringResource(R.string.home_download) else null, onDownload)
                }
            }
            if (ui.schedulerError != null) {
                Warning(stringResource(R.string.home_nothing_to_drill, ui.schedulerError), null, {})
            }
            if (ui.lastError != null) {
                Warning(stringResource(R.string.home_error, ui.lastError), null, {})
            }

            // Per contrast: level, trend of the untrained result, last untrained %.
            SectionCard(stringResource(R.string.home_contrasts)) {
                if (ui.rows.all { it.trials == 0 }) {
                    Text(stringResource(R.string.home_no_history), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                }
                ui.rows.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            stringResource(R.string.home_level, r.level),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        TrendArrow(r.trend)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (r.trials == 0) "—" else pctText(r.lastUntrainedPct),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (r.trials == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.width(56.dp),
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun downloadLabel(s: ClipDownloader.State): String = when (s) {
    is ClipDownloader.State.Downloading ->
        if (s.total != null) "${bytesText(s.bytes)} / ${bytesText(s.total)}" else bytesText(s.bytes)
    is ClipDownloader.State.Unpacking -> stringResource(R.string.settings_pack_unpacking, s.files)
    ClipDownloader.State.Verifying -> stringResource(R.string.settings_pack_verifying)
    else -> ""
}

/** Last untrained result against the previous one: ↑ ↓ →, or a blank of the same width. */
@Composable
private fun TrendArrow(trend: Trend?) {
    val (text, color, cd) = when (trend) {
        Trend.UP -> Triple("↑", successColor(), stringResource(R.string.home_trend_up_cd))
        Trend.DOWN -> Triple("↓", MaterialTheme.colorScheme.error, stringResource(R.string.home_trend_down_cd))
        Trend.FLAT -> Triple("→", MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.home_trend_flat_cd))
        null -> Triple(" ", MaterialTheme.colorScheme.onSurfaceVariant, null)
    }
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = color,
        modifier = Modifier.width(20.dp).let { m -> if (cd != null) m.semantics { contentDescription = cd } else m },
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMedium)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Warning(text: String, action: String?, onAction: () -> Unit) {
    androidx.compose.material3.Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Start)
            if (action != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onAction, modifier = Modifier.height(48.dp)) { Text(action) }
            }
        }
    }
}

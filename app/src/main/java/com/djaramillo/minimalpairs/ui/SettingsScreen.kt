package com.djaramillo.minimalpairs.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.djaramillo.minimalpairs.R
import com.djaramillo.minimalpairs.clips.ClipDownloader
import com.djaramillo.minimalpairs.clips.ClipIndex
import com.djaramillo.minimalpairs.domain.model.Override
import com.djaramillo.minimalpairs.domain.model.PlanDefaults
import com.djaramillo.minimalpairs.storage.DataFolder
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    ui: SettingsUi,
    download: ClipDownloader.State,
    onBack: () -> Unit,
    onFolderPicked: (android.net.Uri?) -> Unit,
    onForgetFolder: () -> Unit,
    onOverride: (Override) -> Unit,
    onDownload: () -> Unit,
    onImportPack: (android.net.Uri) -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownloaded: () -> Unit,
    onRepublish: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val picker = rememberLauncherForActivityResult(DataFolder.PickFolder()) { onFolderPicked(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back_cd))
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
            FolderSection(ui, onChoose = { picker.launch(Unit) }, onForget = onForgetFolder, onRepublish = onRepublish)
            PlanSection(ui)
            OverrideSection(ui, onOverride)
            PackSection(ui, download, onDownload, onImportPack, onCancelDownload, onDeleteDownloaded)
            SectionCard(stringResource(R.string.settings_versions)) {
                Text(stringResource(R.string.settings_app_version, ui.appVersion))
                Text(stringResource(R.string.settings_catalog_version, ui.catalogVersion))
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun FolderSection(ui: SettingsUi, onChoose: () -> Unit, onForget: () -> Unit, onRepublish: () -> Unit) {
    SectionCard(stringResource(R.string.settings_folder)) {
        val st = ui.folderStatus
        val path = when (st) {
            is DataFolder.Status.Ok -> st.path
            DataFolder.Status.NotChosen -> stringResource(R.string.settings_folder_not_chosen)
            else -> ""
        }
        if (path.isNotEmpty()) Text(path, style = MaterialTheme.typography.bodyLarge)
        val statusText = when (st) {
            is DataFolder.Status.Ok -> stringResource(R.string.settings_folder_status_ok)
            DataFolder.Status.NotChosen -> stringResource(R.string.settings_folder_hint)
            DataFolder.Status.PermissionLost -> stringResource(R.string.settings_folder_status_lost)
            DataFolder.Status.Unwritable -> stringResource(R.string.settings_folder_status_unwritable)
        }
        Text(
            statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (st is DataFolder.Status.Ok || st == DataFolder.Status.NotChosen) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onChoose, modifier = Modifier.height(48.dp)) {
                Text(stringResource(if (st == DataFolder.Status.NotChosen) R.string.settings_folder_choose else R.string.settings_folder_change))
            }
            if (st != DataFolder.Status.NotChosen) {
                TextButton(onClick = onForget, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.settings_folder_forget)) }
            }
        }
        if (st != DataFolder.Status.NotChosen) {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onRepublish, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.settings_republish)) }
        }
        val msg = ui.message
        if (msg != null && msg.startsWith("republish:")) {
            val arg = msg.removePrefix("republish:")
            Text(
                if (arg == "unavailable") stringResource(R.string.settings_republish_unavailable)
                else stringResource(R.string.settings_republished, arg.toIntOrNull() ?: 0),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun PlanSection(ui: SettingsUi) {
    val plan = ui.plan ?: return
    SectionCard(stringResource(R.string.settings_plan)) {
        Text(stringResource(R.string.settings_plan_source, plan.planSource), style = MaterialTheme.typography.bodyLarge)
        if (ui.planMessage != null) {
            Text(ui.planMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (!ui.planPresent) {
            Text(stringResource(R.string.settings_plan_absent), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(stringResource(R.string.settings_plan_written, plan.planWritten ?: "—", plan.writtenBy))
            if (plan.note.isNotBlank()) Text(stringResource(R.string.settings_plan_note, plan.note))
        }
        Text(stringResource(R.string.settings_plan_trials, plan.trialsPerSession))
        Text(stringResource(R.string.settings_plan_ratio, ratioText(plan.untrainedRatio)))
        Text(stringResource(R.string.settings_plan_band, plan.bands.joinToString(", ")))
        Text(stringResource(R.string.settings_plan_feedback, plan.feedback.key))
        Text(stringResource(R.string.settings_plan_voices, plan.voices.size))
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.settings_plan_weights), style = MaterialTheme.typography.titleMedium)
        ui.contrasts.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${c.label} (${c.id})", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(ratioText(plan.weights[c.id] ?: 0.0), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun OverrideSection(ui: SettingsUi, onOverride: (Override) -> Unit) {
    val ov = ui.override
    SectionCard(stringResource(R.string.settings_override)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.settings_override_hint), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.padding(6.dp))
            Switch(checked = ov.enabled, onCheckedChange = { onOverride(ov.copy(enabled = it)) })
        }
        if (ov.enabled) {
            Spacer(Modifier.height(8.dp))
            val trials = ov.trialsPerSession ?: ui.plan?.trialsPerSession ?: PlanDefaults.TRIALS_PER_SESSION
            var trialsPos by remember(trials) { mutableFloatStateOf(trials.toFloat()) }
            Text(stringResource(R.string.settings_override_trials, trialsPos.roundToInt()))
            Slider(
                value = trialsPos,
                onValueChange = { trialsPos = it },
                onValueChangeFinished = { onOverride(ov.copy(trialsPerSession = trialsPos.roundToInt())) },
                valueRange = PlanDefaults.MIN_TRIALS_PER_SESSION.toFloat()..PlanDefaults.MAX_TRIALS_PER_SESSION.toFloat(),
                steps = (PlanDefaults.MAX_TRIALS_PER_SESSION - PlanDefaults.MIN_TRIALS_PER_SESSION) / 5 - 1,
            )
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.settings_override_contrasts), style = MaterialTheme.typography.titleMedium)
            ui.contrasts.forEach { c ->
                val weight = ov.weights[c.id] ?: c.defaultWeight
                val on = c.usable && weight > 0.0
                var pos by remember(c.id, weight) { mutableFloatStateOf(weight.toFloat()) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (!c.usable) stringResource(R.string.settings_override_not_trainable) else "${c.id}  ${ratioText(pos.toDouble())}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = on,
                        enabled = c.usable,
                        onCheckedChange = { checked ->
                            val w = if (checked) (if (c.defaultWeight > 0.0) c.defaultWeight else 0.5) else 0.0
                            onOverride(ov.copy(weights = ov.weights + (c.id to w)))
                        },
                    )
                }
                if (on) {
                    Slider(
                        value = pos,
                        onValueChange = { pos = it },
                        onValueChangeFinished = {
                            val w = (pos * 20).roundToInt() / 20.0
                            onOverride(ov.copy(weights = ov.weights + (c.id to w.coerceIn(0.05, 1.0))))
                        },
                        valueRange = 0.05f..1f,
                    )
                }
            }
        }
    }
}

@Composable
private fun PackSection(
    ui: SettingsUi,
    download: ClipDownloader.State,
    onDownload: () -> Unit,
    onImport: (android.net.Uri) -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    // The system file picker; a clips.zip copied to the phone (Downloads, Drive) installs like a download.
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }
    SectionCard(stringResource(R.string.settings_pack)) {
        Text(stringResource(R.string.settings_pack_bundled, packDesc(ui.pack.bundled)))
        Text(stringResource(R.string.settings_pack_downloaded, packDesc(ui.pack.downloaded)))
        if (ui.pack.downloaded != null && ui.downloadedBytes > 0) {
            Text(stringResource(R.string.settings_pack_size, bytesText(ui.downloadedBytes)), style = MaterialTheme.typography.bodyMedium)
        }
        val complete = ui.packWordsTotal > 0 && ui.packWordsAvailable == ui.packWordsTotal
        Text(
            stringResource(if (complete) R.string.settings_pack_complete else R.string.settings_pack_incomplete) + " — " +
                stringResource(R.string.settings_pack_coverage, ui.packWordsAvailable, ui.packWordsTotal),
            style = MaterialTheme.typography.bodyMedium,
            color = if (complete) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.height(8.dp))
        when (download) {
            is ClipDownloader.State.Downloading -> {
                Text(stringResource(R.string.settings_pack_downloading, downloadLabel(download)))
                if (download.total != null && download.total > 0) {
                    LinearProgressIndicator(progress = { download.bytes.toFloat() / download.total }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            is ClipDownloader.State.Unpacking -> {
                Text(stringResource(R.string.settings_pack_unpacking, download.files))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            ClipDownloader.State.Verifying -> {
                Text(stringResource(R.string.settings_pack_verifying))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is ClipDownloader.State.Done -> Text(
                stringResource(R.string.settings_pack_done, download.files, bytesText(download.bytes)),
                color = MaterialTheme.colorScheme.primary,
            )
            is ClipDownloader.State.Failed -> Text(
                stringResource(R.string.settings_pack_failed, download.message),
                color = MaterialTheme.colorScheme.error,
            )
            ClipDownloader.State.Idle -> {}
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (download.isRunning) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.settings_pack_cancel)) }
            } else {
                Button(onClick = onDownload, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.settings_pack_download)) }
                OutlinedButton(
                    onClick = { importPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    modifier = Modifier.height(48.dp),
                ) { Text(stringResource(R.string.settings_pack_import)) }
                if (ui.pack.downloaded != null) {
                    TextButton(onClick = onDelete, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.settings_pack_delete)) }
                }
            }
        }
        Text(
            stringResource(R.string.settings_pack_source, ClipDownloader.RELEASE_URL),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.settings_pack_import_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun packDesc(index: ClipIndex?): String {
    if (index == null) return stringResource(R.string.settings_pack_none)
    val flag = stringResource(if (index.complete) R.string.settings_pack_complete_flag else R.string.settings_pack_partial_flag)
    return stringResource(R.string.settings_pack_desc, index.words.size, index.voices.size, flag)
}

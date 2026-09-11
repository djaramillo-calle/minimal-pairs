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
import com.djaramillo.minimalpairs.clips.PackRenderer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.mutableStateOf
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
    render: PackRenderer.State = PackRenderer.State.Idle,
    onSaveAzure: (String, String) -> Unit = { _, _ -> },
    onTestAzure: () -> Unit = {},
    onForgetAzure: () -> Unit = {},
    onRender: () -> Unit = {},
    onCancelRender: () -> Unit = {},
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
            PackSection(ui, download, onDownload, onImportPack, onCancelDownload, onDeleteDownloaded, locked = render.isRunning)
            AzureSection(ui, render, onSaveAzure, onTestAzure, onForgetAzure, onRender, onCancelRender)
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
        // Say it, the level ladder and the consistency target (docs/CONTRACT.md levers table), read-only.
        Text(
            if (plan.productionOn) stringResource(R.string.settings_plan_production_pairs, plan.productionPairs)
            else stringResource(R.string.settings_plan_production_off),
        )
        Text(stringResource(R.string.settings_plan_production_threshold, plan.productionThreshold))
        Text(stringResource(R.string.settings_plan_max_level, plan.maxLevel))
        Text(
            stringResource(
                R.string.settings_plan_levels,
                if (plan.levels.isEmpty()) stringResource(R.string.settings_plan_levels_none)
                else plan.levels.entries.joinToString(", ") { "${it.key} ${it.value}" },
            ),
        )
        Text(stringResource(R.string.settings_plan_weekly_target, plan.weeklyMinutesTarget))
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
    /** True while the on-phone renderer writes the same directory. */
    locked: Boolean = false,
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
                Button(onClick = onDownload, enabled = !locked, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.settings_pack_download)) }
                OutlinedButton(
                    onClick = { importPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    enabled = !locked,
                    modifier = Modifier.height(48.dp),
                ) { Text(stringResource(R.string.settings_pack_import)) }
                if (ui.pack.downloaded != null) {
                    TextButton(onClick = onDelete, enabled = !locked, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.settings_pack_delete)) }
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


@Composable
private fun AzureSection(
    ui: SettingsUi,
    render: PackRenderer.State,
    onSave: (String, String) -> Unit,
    onTest: () -> Unit,
    onForget: () -> Unit,
    onRender: () -> Unit,
    onCancel: () -> Unit,
) {
    var region by remember(ui.azureRegion) { mutableStateOf(ui.azureRegion) }
    var key by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    SectionCard(stringResource(R.string.settings_azure)) {
        Text(stringResource(R.string.settings_azure_intro), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.settings_azure_privacy), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = region,
            onValueChange = { region = it },
            label = { Text(stringResource(R.string.settings_azure_region)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(stringResource(if (ui.azureKeySet) R.string.settings_azure_key_stored else R.string.settings_azure_key)) },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "hide" else "show") } },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(region, key); key = "" }, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.settings_azure_save)) }
            OutlinedButton(onClick = onTest, enabled = ui.azureKeySet, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.settings_azure_test)) }
            if (ui.azureKeySet) {
                TextButton(onClick = onForget, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.settings_azure_forget)) }
            }
        }
        ui.azureMessage?.let { m ->
            val text = when {
                m == "azure:saved" -> stringResource(R.string.settings_azure_msg_saved)
                m == "azure:bad-region" -> stringResource(R.string.settings_azure_msg_bad_region)
                m == "azure:bad-key" -> stringResource(R.string.settings_azure_msg_bad_key)
                m == "azure:no-key" -> stringResource(R.string.settings_azure_msg_no_key)
                m == "azure:testing" -> stringResource(R.string.settings_azure_msg_testing)
                m.startsWith("azure:ok:") -> {
                    val parts = m.removePrefix("azure:ok:").split(":")
                    stringResource(R.string.settings_azure_msg_ok, parts.getOrNull(0)?.toIntOrNull() ?: 0, parts.getOrNull(1)?.toIntOrNull() ?: 0)
                }
                m.startsWith("azure:missing:") -> stringResource(R.string.settings_azure_msg_missing, m.removePrefix("azure:missing:"))
                else -> m
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = when (ui.azureOk) {
                    true -> MaterialTheme.colorScheme.primary
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.height(12.dp))
        when (render) {
            is PackRenderer.State.Running -> {
                Text(stringResource(R.string.settings_azure_rendering, render.wordsDone, render.wordsTotal, render.clipsDone, render.currentWord))
                LinearProgressIndicator(
                    progress = { if (render.clipsTotal > 0) render.clipsDone.toFloat() / render.clipsTotal else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (render.failures > 0) {
                    Text(stringResource(R.string.settings_azure_render_failures, render.failures), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onCancel, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.settings_azure_cancel)) }
            }
            PackRenderer.State.Cancelling -> {
                Text(stringResource(R.string.settings_azure_cancelling))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is PackRenderer.State.Done -> {
                val tail = if (render.complete) stringResource(R.string.settings_azure_render_done_complete)
                else stringResource(R.string.settings_azure_render_done_partial, render.failures)
                Text(stringResource(R.string.settings_azure_render_done, render.words, render.clips, tail), color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                RenderButton(ui, onRender)
            }
            is PackRenderer.State.Failed -> {
                Text(stringResource(R.string.settings_azure_render_failed, render.message), color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                RenderButton(ui, onRender)
            }
            PackRenderer.State.Idle -> RenderButton(ui, onRender)
        }
        Text(
            stringResource(R.string.settings_azure_render_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RenderButton(ui: SettingsUi, onRender: () -> Unit) {
    Button(onClick = onRender, enabled = ui.azureKeySet && !ui.packBusy, modifier = Modifier.height(48.dp)) {
        Text(stringResource(R.string.settings_azure_render))
    }
}

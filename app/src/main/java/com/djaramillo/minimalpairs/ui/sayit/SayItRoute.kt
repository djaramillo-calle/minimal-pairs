package com.djaramillo.minimalpairs.ui.sayit

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.djaramillo.minimalpairs.R

/**
 * The whole Say-it sentence mode behind one entry point: which of its pages is
 * showing, the microphone permission, and the lifecycle rule that a recording
 * must not survive the app going away.
 *
 * The permission is asked for at the moment he first taps record, and the
 * rationale is shown **before** the system dialog, because the honest thing to
 * say is where the recording goes: into his own synced folder for the coach,
 * and — only when he has entered this phone's own Azure key — straight to Azure
 * for the score he sees at once (docs/CONTRACT.md). This launcher is the mode's
 * own — the pairs block's `micPrompt` plumbing in `AppViewModel` belongs to that
 * block and is left alone.
 */
@Composable
fun SayItRoute(
    onHome: () -> Unit,
    onSettings: () -> Unit,
    vm: SayItViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var rationale by remember { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.onMicPermission(granted)
    }
    if (rationale) {
        AlertDialog(
            onDismissRequest = { rationale = false },
            title = { Text(stringResource(R.string.sayit_sentence_mic_title)) },
            text = { Text(stringResource(R.string.sayit_sentence_mic_text)) },
            confirmButton = {
                TextButton(onClick = {
                    rationale = false
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text(stringResource(R.string.sayit_sentence_mic_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { rationale = false }) {
                    Text(stringResource(R.string.sayit_sentence_mic_not_now))
                }
            },
        )
    }

    // The app going to the background, the screen going off and an incoming call
    // all stop the activity. Any running recording is cancelled and discarded and
    // the player is stopped. A configuration change stops the activity too but is
    // not the app going away, so it is skipped exactly as MainActivity does for
    // the pairs block: it must not throw away a sentence half read.
    val activity = remember(context) {
        var c: Context? = context
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        c as? Activity
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isChangingConfigurations != true) vm.onBackground()
    }
    // He may have granted the microphone in Android's own settings, which the
    // "Recording is off" card sends him to; granting it does not restart the
    // process, so nothing else would ever notice.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onForeground() }
    // The ViewModel is scoped to the activity and outlives this screen, so a
    // finished run is thrown away and the folder read again on the way in.
    LaunchedEffect(Unit) { vm.onEnter() }
    // Reading a whole sentence takes most of the twenty-second cap and he
    // touches nothing meanwhile: without this the display times out, the
    // activity stops and the take is discarded as an interruption. MainActivity
    // does the same while the pack is being rendered.
    DisposableEffect(ui.recording) {
        val window = activity?.window
        if (ui.recording) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val problem = ui.folderProblem
    when {
        ui.loading -> SayItLoadingScreen()
        problem != null -> SayItFolderScreen(problem, onSettings = onSettings, onHome = onHome)
        ui.done -> SayItDoneScreen(ui, onHome = onHome)
        ui.empty -> SayItEmptyScreen(ui, onHome = onHome)
        else -> SayItSentenceScreen(
            ui = ui,
            onPlayModel = vm::onPlayModel,
            onRecord = {
                // Stopping never needs the permission; only the first start does.
                if (ui.recording || vm.hasMicPermission()) vm.onRecordTapped() else rationale = true
            },
            onCompare = vm::onCompare,
            onNext = vm::onNext,
            onLeave = { vm.onLeaveRun() },
            onAndroidSettings = {
                // Once Android has stopped asking, the only way back is the app's
                // own settings page; the mode keeps working without the microphone.
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                } catch (e: android.content.ActivityNotFoundException) {
                    // Nothing to show: the line above the button already says what to do.
                }
            },
        )
    }
}

package com.djaramillo.minimalpairs

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.djaramillo.minimalpairs.audio.Player
import com.djaramillo.minimalpairs.ui.AppViewModel
import com.djaramillo.minimalpairs.ui.HomeScreen
import com.djaramillo.minimalpairs.ui.MinimalPairsTheme
import com.djaramillo.minimalpairs.ui.SayItScreen
import com.djaramillo.minimalpairs.ui.Screen
import com.djaramillo.minimalpairs.ui.SettingsScreen
import com.djaramillo.minimalpairs.ui.SummaryScreen
import com.djaramillo.minimalpairs.ui.TrialScreen
import com.djaramillo.minimalpairs.ui.shouldPromptMic

/**
 * Single activity; the screen state machine lives in [AppViewModel.screen]
 * (Home → Trial → Say it → Summary → Home, Home ↔ Settings).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        volumeControlStream = Player.VOLUME_STREAM
        setContent {
            MinimalPairsTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App()
                }
            }
        }
    }
}

@Composable
private fun App(vm: AppViewModel = viewModel()) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val download by vm.downloadState.collectAsStateWithLifecycle()
    val render by vm.renderState.collectAsStateWithLifecycle()
    // Rendering the pack with the learner's key takes a while: keep the screen on meanwhile.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.LaunchedEffect(render.isRunning) {
        val window = (view.context as? android.app.Activity)?.window ?: return@LaunchedEffect
        if (render.isRunning) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    // Give audio focus back while the app is in the background so other apps stop ducking, and
    // end a running Say-it recording. A configuration change (a rotation) stops the activity too
    // but is not the app going away: it must not cut the learner's word in half and send the
    // truncated recording to Azure, so the stop is skipped there.
    val activity = view.context as? android.app.Activity
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isChangingConfigurations != true) vm.onBackground()
    }
    // Coming back to the foreground: pick up a plan.json that arrived meanwhile (docs/CONTRACT.md).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    // RECORD_AUDIO for the Say-it block: the ViewModel asks (`micPrompt` = the pending request id,
    // 0 for none), the activity shows the system prompt once per request and hands the answer back,
    // which clears the request. The launched id survives rotation so a recreated activity does not
    // prompt twice for the same request, and a request of 0 clears it again — after process death
    // the restored id would otherwise sit above a ViewModel that counts from scratch and the next
    // block would wait for a prompt that never comes (see [shouldPromptMic]).
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.onMicPermission(granted)
    }
    val micPrompt by vm.micPrompt.collectAsStateWithLifecycle()
    var micLaunched by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(micPrompt) {
        if (shouldPromptMic(micPrompt, micLaunched)) {
            micLaunched = micPrompt
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else if (micPrompt == 0) {
            micLaunched = 0
        }
    }

    when (screen) {
        Screen.HOME -> {
            val home by vm.home.collectAsStateWithLifecycle()
            HomeScreen(
                ui = home,
                download = download,
                onStart = vm::startSession,
                onSettings = vm::openSettings,
                onDownload = vm::startDownload,
            )
        }
        Screen.TRIAL -> {
            val trial by vm.trial.collectAsStateWithLifecycle()
            TrialScreen(
                ui = trial,
                onPlay = vm::onPlayTapped,
                onAnswer = vm::onAnswer,
                onHear = vm::onHearWord,
                onNext = vm::onNext,
                onRetry = vm::retryTrial,
                onSkip = vm::skipTrial,
                onAbandon = vm::abandonSession,
            )
        }
        Screen.SAY_IT -> {
            val sayIt by vm.sayIt.collectAsStateWithLifecycle()
            SayItScreen(
                ui = sayIt,
                onRecord = vm::onSayRecord,
                onHear = vm::onSayHear,
                onPlayRecording = vm::onSayPlayRecording,
                onNext = vm::onSayNext,
                onSkipRest = vm::onSaySkipRest,
            )
        }
        Screen.SUMMARY -> {
            val summary by vm.summary.collectAsStateWithLifecycle()
            val s = summary
            if (s != null) SummaryScreen(s, onHome = vm::goHome, onAgain = vm::startSession)
        }
        Screen.SETTINGS -> {
            val settings by vm.settings.collectAsStateWithLifecycle()
            SettingsScreen(
                ui = settings,
                download = download,
                onBack = vm::goHome,
                onFolderPicked = vm::onFolderPicked,
                onForgetFolder = vm::forgetFolder,
                onOverride = vm::setOverride,
                onDownload = vm::startDownload,
                onImportPack = vm::importPack,
                onCancelDownload = vm::cancelDownload,
                onDeleteDownloaded = vm::deleteDownloaded,
                onRepublish = vm::republish,
                render = render,
                onSaveAzure = vm::saveAzure,
                onTestAzure = vm::testAzure,
                onForgetAzure = vm::forgetAzure,
                onRender = vm::startRender,
                onCancelRender = vm::cancelRender,
            )
        }
    }
}

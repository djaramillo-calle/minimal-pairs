package com.djaramillo.minimalpairs

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.djaramillo.minimalpairs.audio.Player
import com.djaramillo.minimalpairs.ui.AppViewModel
import com.djaramillo.minimalpairs.ui.HomeScreen
import com.djaramillo.minimalpairs.ui.MinimalPairsTheme
import com.djaramillo.minimalpairs.ui.Screen
import com.djaramillo.minimalpairs.ui.SettingsScreen
import com.djaramillo.minimalpairs.ui.SummaryScreen
import com.djaramillo.minimalpairs.ui.TrialScreen
import com.djaramillo.minimalpairs.ui.sayit.SayItRoute

/**
 * Single activity; the screen state machine lives in [AppViewModel.screen]
 * (Home → Trial → Summary → Home, Home ↔ Settings, Home ↔ the Say-it
 * sentence mode).
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
    // Give audio focus back while the app is in the background so other apps stop ducking. A
    // configuration change (a rotation) stops the activity too but is not the app going away.
    val activity = view.context as? android.app.Activity
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isChangingConfigurations != true) vm.onBackground()
    }
    // Coming back to the foreground: pick up a plan.json that arrived meanwhile (docs/CONTRACT.md).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    when (screen) {
        Screen.HOME -> {
            val home by vm.home.collectAsStateWithLifecycle()
            HomeScreen(
                ui = home,
                download = download,
                onStart = vm::startSession,
                onSettings = vm::openSettings,
                onSayIt = vm::openSayItSentences,
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
        Screen.SUMMARY -> {
            val summary by vm.summary.collectAsStateWithLifecycle()
            val s = summary
            if (s != null) SummaryScreen(s, onHome = vm::goHome, onAgain = vm::startSession)
        }
        Screen.SENTENCES -> {
            // The mode owns its own ViewModel, its own microphone permission request
            // and its own lifecycle handling (docs/CONTRACT.md, "`sayit.zip`"), so
            // nothing of it leaks into the perception drill's state machine.
            SayItRoute(onHome = vm::goHome, onSettings = vm::openSettings)
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

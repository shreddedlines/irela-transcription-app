package com.whispercppdemo.ui.nav

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.whispercppdemo.capture.CaptureMode
import com.whispercppdemo.capture.CaptureNotice
import com.whispercppdemo.history.TranscriptRecord
import com.whispercppdemo.transcribe.Engine
import com.whispercppdemo.transcribe.EngineSelector
import com.whispercppdemo.transcribe.TranscriptionState
import com.whispercppdemo.transcribe.TranscriptionStore
import com.whispercppdemo.ui.common.AppMenu
import com.whispercppdemo.ui.common.ConfirmDestructiveDialog
import com.whispercppdemo.ui.common.FailureCategory
import com.whispercppdemo.ui.common.MenuEntry
import com.whispercppdemo.ui.common.NavTab
import com.whispercppdemo.ui.common.PrivacyCopy
import com.whispercppdemo.ui.common.RenameDialog
import com.whispercppdemo.ui.common.StatusBarInset
import com.whispercppdemo.ui.common.AppBottomNav
import com.whispercppdemo.ui.common.AppTopBar
import com.whispercppdemo.ui.common.TerminalRoute
import com.whispercppdemo.ui.common.Titled
import com.whispercppdemo.ui.common.TopBarIcon
import com.whispercppdemo.ui.common.UiPrefs
import com.whispercppdemo.ui.common.rememberTranscriptTitles
import com.whispercppdemo.ui.common.terminalRoute
import com.whispercppdemo.ui.detail.TranscriptDetailScreen
import com.whispercppdemo.ui.edit.TranscriptEditScreen
import com.whispercppdemo.ui.history.HistoryScreen
import com.whispercppdemo.ui.home.HomeScreen
import com.whispercppdemo.ui.main.MainScreenViewModel
import com.whispercppdemo.ui.permission.MicrophonePermissionScreen
import com.whispercppdemo.ui.privacy.CloudDisclosureScreen
import com.whispercppdemo.ui.processing.CancelledScreen
import com.whispercppdemo.ui.processing.FailedScreen
import com.whispercppdemo.ui.processing.ProcessingScreen
import com.whispercppdemo.ui.recording.RecordMode
import com.whispercppdemo.ui.recording.RecordModeSheet
import com.whispercppdemo.ui.recording.RecordingNotice
import com.whispercppdemo.ui.recording.RecordingScreen
import com.whispercppdemo.ui.theme.Spacing
import com.whispercppdemo.ui.usage.UsageScreen
import com.whispercppdemo.ui.usage.UsageViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

/**
 * One screen slides, nothing else moves: only Home <-> History animates, as
 * lateral movement between peers. See the history of this file for the
 * rejected alternatives (parallax, fades, scale).
 */
private const val NAV_MS = 130
private val NAV_OFFSET_SPEC = tween<IntOffset>(NAV_MS, easing = LinearOutSlowInEasing)

private val TAB_ROUTES = setOf(Dest.HOME, Dest.HISTORY)

private val TAB_ENTER_FORWARD = slideInHorizontally(NAV_OFFSET_SPEC) { full -> full }
private val TAB_ENTER_BACK = slideInHorizontally(NAV_OFFSET_SPEC) { full -> -full }

private fun tabTransition(from: String?, to: String?) = when {
    from !in TAB_ROUTES || to !in TAB_ROUTES || from == to -> null
    to == Dest.HISTORY -> TAB_ENTER_FORWARD
    else -> TAB_ENTER_BACK
}

/**
 * Routes that carry the bottom bar (Concept A): the two tabs and the outcome
 * screens. Recording, Processing, Disclosure, Permission, Detail and Edit are
 * tasks and show none.
 */
private val BOTTOM_BAR_ROUTES = setOf(
    Dest.HOME, Dest.HISTORY, Dest.CANCELLED, Dest.FAILED, Dest.ATTEMPT_ROUTE
)

/** Routes that draw their own header, so the scaffold only reserves the status bar. */
private val SELF_HEADER_ROUTES = setOf(Dest.HOME, Dest.HISTORY, Dest.EDIT_ROUTE)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun AppNavHost(
    viewModel: MainScreenViewModel,
    navController: NavHostController = rememberNavController()
) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val isDetail = currentRoute == Dest.DETAIL_ROUTE
    val showBottomBar = currentRoute in BOTTOM_BAR_ROUTES
    val context = LocalContext.current
    val titles = rememberTranscriptTitles()
    val engine = EngineSelector.effective()

    // While a job is running or audio is being captured the bar cannot be used
    // to walk away from it (it is not shown on those screens either).
    val navEnabled = !viewModel.serviceState.isRunning && !viewModel.isRecording
    // A stored attempt is opened from History, so History stays the current tab.
    val selectedTab = if (currentRoute == Dest.HISTORY || currentRoute == Dest.ATTEMPT_ROUTE) NavTab.HISTORY else NavTab.HOME

    // Which record Detail is showing, for its ⋮ actions.
    val openRecordId = backStack?.arguments?.getString(Dest.DETAIL_ARG)

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun message(text: String) {
        scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) }
    }

    var confirmDeleteOne by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<TranscriptRecord?>(null) }
    var detailMenuOpen by remember { mutableStateOf(false) }
    var historySelection by rememberSaveable { mutableStateOf(listOf<String>()) }
    var modeSheetOpen by rememberSaveable { mutableStateOf(false) }
    /** A capture mode chosen while RECORD_AUDIO was not granted, started once it is. */
    var pendingModeName by rememberSaveable { mutableStateOf<String?>(null) }
    var holdHintSeen by remember { mutableStateOf(UiPrefs.historyHintSeen(context)) }

    // One Titled per stored item, so same-minute twins are resolved the same
    // way on every screen.
    val titledItems = remember(viewModel.history, viewModel.attempts) {
        viewModel.history.map { it.id to Titled(it.displayName, it.createdAt) } +
            viewModel.attempts.map { it.jobId to Titled(it.displayName, it.createdAt) }
    }
    val peers = titledItems.map { it.second }
    fun titleForRecord(id: String): String? = titledItems.firstOrNull { it.first == id }?.second?.let { titles.full(it, peers) }
    /** A live job's name: a stored kind name becomes "Recording · Today, 10:42 AM" etc. */
    fun titleForLiveName(name: String?): String? = name?.let { titles.full(Titled(it, System.currentTimeMillis()), peers) }

    // The service state drives navigation; there is no second, UI-side state
    // machine. Each transition is keyed so it fires once per state change.
    val serviceState = viewModel.serviceState
    LaunchedEffect(
        serviceState,
        viewModel.completedRecordId,
        viewModel.followedJobId,
        viewModel.pendingConsent
    ) {
        // A parked job means the user asked to transcribe but has not yet been
        // told what that does. Nothing is running, so this is decided before
        // the job states below.
        if (viewModel.pendingConsent != null) {
            if (navController.currentDestination?.route != Dest.DISCLOSURE) {
                navController.navigate(Dest.DISCLOSURE) { launchSingleTop = true }
            }
            return@LaunchedEffect
        }

        when (serviceState) {
            // Every non-terminal state routes to Processing. Queued, Uploading
            // and Retrying were falling through to `else` and leaving the user
            // on Home while the job actually ran.
            is TranscriptionState.Queued,
            is TranscriptionState.Staging,
            is TranscriptionState.Uploading,
            is TranscriptionState.Decoding,
            is TranscriptionState.Transcribing,
            is TranscriptionState.Retrying -> {
                if (navController.currentDestination?.route != Dest.PROCESSING) {
                    navController.navigate(Dest.PROCESSING) { launchSingleTop = true }
                }
            }

            // Terminal states route ONLY for the job this screen is following.
            // A lingering FAILED/CANCELLED from an older job can no longer
            // consume a newer job's completion. See ui/common/JobFollow.kt.
            else -> when (val route = terminalRoute(
                state = serviceState,
                followedJobId = viewModel.followedJobId,
                aliases = TranscriptionStore.aliases.value,
                completedRecordId = viewModel.completedRecordId,
                onProcessing = navController.currentDestination?.route == Dest.PROCESSING
            )) {
                is TerminalRoute.OpenTranscript -> {
                    viewModel.consumeTerminalState()
                    navController.navigate(Dest.detail(route.recordId)) {
                        popUpTo(Dest.HOME)
                        launchSingleTop = true
                    }
                }
                TerminalRoute.ShowFailed -> {
                    viewModel.consumeTerminalState()
                    navController.navigate(Dest.FAILED) {
                        popUpTo(Dest.HOME); launchSingleTop = true
                    }
                }
                TerminalRoute.ShowCancelled -> {
                    viewModel.consumeTerminalState()
                    navController.navigate(Dest.CANCELLED) {
                        popUpTo(Dest.HOME); launchSingleTop = true
                    }
                }
                // Nothing running and nothing followed: never strand the user
                // on "No transcription in progress."
                TerminalRoute.LeaveProcessing ->
                    navController.popBackStack(Dest.HOME, false)
                TerminalRoute.None -> Unit
            }
        }
    }

    // ---- capture start: Record audio -> mode sheet -> (permission) -> Recording ----

    // Android's own MediaProjection consent dialog. Never bypassed, never
    // pre-accepted; a denial is a visible, safe state (captureBlock).
    val consent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> viewModel.onPlaybackConsentResult(result.resultCode, result.data) }

    val micPermission = rememberPermissionState(android.Manifest.permission.RECORD_AUDIO) {
        UiPrefs.markMicPermissionAsked(context)
    }

    /** Starts one mode through the ViewModel's existing entry points. */
    fun startMode(mode: RecordMode) {
        when (mode) {
            RecordMode.VOICE -> viewModel.startRecording()
            RecordMode.CONVERSATION -> viewModel.startConversation()
            RecordMode.PLAYBACK -> if (viewModel.preparePlaybackCapture()) {
                val mpm = context.getSystemService(android.media.projection.MediaProjectionManager::class.java)
                consent.launch(mpm.createScreenCaptureIntent())
            }
        }
    }

    fun chooseMode(mode: RecordMode) {
        viewModel.clearCaptureBlock()
        // Every mode needs RECORD_AUDIO (CapturePolicy). Ask first, start after.
        if (!micPermission.status.isGranted) {
            pendingModeName = mode.name
            modeSheetOpen = false
            navController.navigate(Dest.PERMISSION) { launchSingleTop = true }
            return
        }
        // The sheet stays open until capture has really started (then it closes
        // and Recording opens) or a block is reported inside it.
        startMode(mode)
    }

    // Capture actually started -> the live Recording screen. Also re-attaches a
    // session that outlived the previous Activity.
    LaunchedEffect(viewModel.recordingStartedAt) {
        if (viewModel.recordingStartedAt != null) {
            modeSheetOpen = false
            if (navController.currentDestination?.route != Dest.RECORDING) {
                navController.navigate(Dest.RECORDING) { popUpTo(Dest.HOME); launchSingleTop = true }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shape = MaterialTheme.shapes.small
                )
            }
        },
        topBar = {
            when {
                // Edit draws its own bar below this inset (close + unsaved-changes check).
                currentRoute == null || currentRoute in SELF_HEADER_ROUTES -> StatusBarInset()
                isDetail && openRecordId != null -> AppTopBar(
                    title = titleForRecord(openRecordId) ?: "Transcript",
                    onBack = { navController.popBackStack() },
                    actions = {
                        Box {
                            TopBarIcon(Icons.Filled.MoreVert, "Transcript options", { detailMenuOpen = true })
                            AppMenu(
                                expanded = detailMenuOpen,
                                onDismiss = { detailMenuOpen = false },
                                entries = listOf(
                                    MenuEntry("Rename", Icons.Outlined.DriveFileRenameOutline) {
                                        renameTarget = viewModel.history.firstOrNull { it.id == openRecordId }
                                    },
                                    MenuEntry("Edit transcript", Icons.Outlined.Edit) {
                                        navController.navigate(Dest.edit(openRecordId))
                                    },
                                    MenuEntry("Delete", Icons.Outlined.DeleteOutline, destructive = true, dividerBefore = true) {
                                        confirmDeleteOne = true
                                    }
                                )
                            )
                        }
                    }
                )
                else -> {
                    val attempt = if (currentRoute == Dest.ATTEMPT_ROUTE)
                        viewModel.attempts.firstOrNull { it.jobId == backStack?.arguments?.getString(Dest.ATTEMPT_ARG) }
                    else null
                    AppTopBar(
                        title = when (currentRoute) {
                            Dest.RECORDING, Dest.PERMISSION -> "Record audio"
                            Dest.PROCESSING -> "Transcribing"
                            Dest.CANCELLED -> "Cancelled"
                            Dest.FAILED -> "Failed"
                            Dest.ATTEMPT_ROUTE -> if (attempt?.cancelled == true) "Cancelled" else "Failed"
                            Dest.DISCLOSURE -> "How transcription works"
                            Dest.USAGE -> "Usage"
                            Dest.UPGRADE -> "Irela Pro"
                            else -> ""
                        },
                        onBack = when (currentRoute) {
                            Dest.CANCELLED, Dest.FAILED -> { { navController.popBackStack(Dest.HOME, false) } }
                            Dest.ATTEMPT_ROUTE, Dest.USAGE, Dest.UPGRADE -> { { navController.popBackStack() } }
                            Dest.PERMISSION -> { { pendingModeName = null; navController.popBackStack() } }
                            else -> null
                        }
                    )
                }
            }
        },
        bottomBar = {
            if (showBottomBar) {
                AppBottomNav(
                    selected = selectedTab,
                    enabled = navEnabled,
                    onSelect = { tab ->
                        historySelection = emptyList()   // switching tabs leaves selection mode
                        val route = if (tab == NavTab.HISTORY) Dest.HISTORY else Dest.HOME
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Dest.HOME,
            modifier = Modifier.padding(padding),
            enterTransition = {
                tabTransition(initialState.destination.route, targetState.destination.route) ?: EnterTransition.None
            },
            exitTransition = { ExitTransition.None },
            popEnterTransition = {
                tabTransition(initialState.destination.route, targetState.destination.route) ?: EnterTransition.None
            },
            popExitTransition = { ExitTransition.None }
        ) {
            composable(Dest.HOME) {
                // SAF picker: supplies the content:// URI a share intent would
                // otherwise deliver to the unchanged importAudio() path.
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let(viewModel::importAudio) }

                HomeScreen(
                    canTranscribe = viewModel.canStartJob,
                    preparing = !viewModel.canTranscribe,
                    onRecord = { viewModel.clearCaptureBlock(); modeSheetOpen = true },
                    onImport = { picker.launch(arrayOf("audio/*")) },
                    engine = engine
                )
            }

            composable(Dest.HISTORY) {
                HistoryScreen(
                    history = viewModel.history,
                    attempts = viewModel.attempts,
                    selection = historySelection,
                    onSelectionChange = { historySelection = it },
                    onOpenTranscript = { navController.navigate(Dest.detail(it.id)) },
                    onOpenAttempt = { navController.navigate(Dest.attempt(it.jobId)) },
                    onEdit = { navController.navigate(Dest.edit(it.id)) },
                    onRename = { record, name -> viewModel.renameTranscript(record.id, name) },
                    onDelete = { transcriptIds, jobIds -> viewModel.deleteSelection(transcriptIds, jobIds) },
                    onRetry = { viewModel.retryJob(it.jobId) },
                    onDeleteAll = { viewModel.deleteAllTranscripts() },
                    onStartTranscription = {
                        navController.navigate(Dest.HOME) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onMessage = ::message,
                    // A claim about where audio is processed: what this build actually does.
                    privacyNote = PrivacyCopy.audioHandling(engine),
                    cloud = engine == Engine.CLOUD,
                    showHoldHint = !holdHintSeen,
                    onHoldHintSeen = {
                        if (!holdHintSeen) { holdHintSeen = true; UiPrefs.markHistoryHintSeen(context) }
                    },
                    // Usage is about the cloud allowance: a local build has none.
                    onOpenUsage = if (engine == Engine.CLOUD) ({ navController.navigate(Dest.USAGE) }) else null
                )
            }

            composable(Dest.USAGE) {
                val usage: UsageViewModel = viewModel()
                UsageScreen(
                    state = usage.state,
                    claiming = usage.claiming,
                    onRetry = usage::refresh,
                    onClaim = usage::claim,
                    onMessage = ::message,
                    onUpgrade = if (usage.billingAvailable) ({ navController.navigate(Dest.UPGRADE) }) else null,
                    onManageSubscription = if (usage.billingAvailable) usage::manageSubscription else null,
                    onRestorePurchases = if (usage.billingAvailable) usage::restorePurchases else null,
                    billingStatus = usage.billingStatus
                )
            }

            composable(Dest.UPGRADE) {
                val upgrade: com.whispercppdemo.ui.upgrade.UpgradeViewModel = viewModel()
                val activity = context.findActivity()
                com.whispercppdemo.ui.upgrade.UpgradeScreen(
                    state = upgrade.state,
                    onSubscribe = { offer -> activity?.let { upgrade.subscribe(it, offer) } },
                    onRestore = upgrade::restore,
                    onRetryOffers = upgrade::loadOffers,
                    onDone = { navController.popBackStack() }
                )
            }

            composable(Dest.PERMISSION) {
                val permanentlyDenied = !micPermission.status.isGranted &&
                    !micPermission.status.shouldShowRationale &&
                    UiPrefs.micPermissionAsked(context)
                // Granted from Android's dialog, or after a trip to Settings:
                // continue with the mode the user already chose.
                LaunchedEffect(micPermission.status.isGranted) {
                    val mode = pendingModeName?.let { runCatching { RecordMode.valueOf(it) }.getOrNull() }
                    if (micPermission.status.isGranted) {
                        pendingModeName = null
                        navController.popBackStack(Dest.HOME, false)
                        if (mode != null) {
                            if (mode == RecordMode.PLAYBACK) modeSheetOpen = true
                            startMode(mode)
                        }
                    }
                }
                MicrophonePermissionScreen(
                    permanentlyDenied = permanentlyDenied,
                    onAllow = { micPermission.launchPermissionRequest() },
                    onOpenSettings = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                       Uri.fromParts("package", context.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    onNotNow = { pendingModeName = null; navController.popBackStack() },
                    engine = engine
                )
            }

            composable(Dest.RECORDING) {
                // Nothing to show once there is no session and nothing to route
                // to (discarded, a failed start, or a restored back stack after
                // process death): return Home rather than strand the user on
                // an idle Recording screen. A stopped recording is NOT this
                // case -- it has a followed job or a parked disclosure, and the
                // routing above takes it onward.
                LaunchedEffect(
                    viewModel.recordingStartedAt, viewModel.isRecording,
                    viewModel.followedJobId, viewModel.pendingConsent, viewModel.serviceState
                ) {
                    if (viewModel.recordingStartedAt == null && !viewModel.isRecording &&
                        viewModel.followedJobId == null && viewModel.pendingConsent == null &&
                        !viewModel.serviceState.isRunning
                    ) {
                        navController.popBackStack(Dest.HOME, false)
                        // A playback capture Android refused: show why, in the sheet.
                        if (viewModel.captureBlock != null) modeSheetOpen = true
                    }
                }
                RecordingScreen(
                    state = viewModel.recordingState,
                    amplitude = viewModel.micAmplitude,
                    recordedMillis = viewModel.recordedMillis,
                    onStop = viewModel::stopRecordingAndTranscribe,
                    onPauseToggle = {
                        if (viewModel.isPaused) viewModel.resumeRecording() else viewModel.pauseRecording()
                    },
                    onDiscard = {
                        viewModel.cancelRecording()
                        navController.popBackStack(Dest.HOME, false)
                    },
                    mode = when {
                        !viewModel.conversationMode -> RecordMode.VOICE
                        viewModel.captureMode == CaptureMode.PLAYBACK -> RecordMode.PLAYBACK
                        else -> RecordMode.CONVERSATION
                    },
                    notices = viewModel.captureNotices
                        .filterNot { it == CaptureNotice.MICROPHONE_ONLY }
                        .map { RecordingNotice(it.message, warning = it != CaptureNotice.PLAYBACK_ONLY) },
                    continuesInBackground = com.whispercppdemo.recorder.Recordings.foregroundStarted
                )
            }

            composable(Dest.PROCESSING) {
                // While a job is running, system Back does not abandon this
                // screen (leaving it caused an unexpected jump to Detail later).
                BackHandler(enabled = viewModel.serviceState.isRunning) { }
                ProcessingScreen(
                    state = viewModel.serviceState,
                    isCancelling = viewModel.isCancelling,
                    continuesInBackground = viewModel.jobContinuesInBackground,
                    onCancel = viewModel::cancelTranscription
                )
            }

            composable(Dest.EDIT_ROUTE) { entry ->
                val id = entry.arguments?.getString(Dest.DETAIL_ARG)
                val record = viewModel.history.firstOrNull { it.id == id }
                if (record == null) {
                    Column { AppTopBar("Edit transcript", onBack = { navController.popBackStack() }); MissingItem() }
                } else {
                    TranscriptEditScreen(
                        record = record,
                        onSave = { text ->
                            viewModel.updateTranscriptText(record.id, text)
                            navController.popBackStack()
                            message("Transcript saved")
                        },
                        onCancel = { navController.popBackStack() }
                    )
                }
            }

            composable(Dest.DETAIL_ROUTE) { entry ->
                val id = entry.arguments?.getString(Dest.DETAIL_ARG)
                val record = viewModel.history.firstOrNull { it.id == id }
                if (record == null) MissingItem() else TranscriptDetailScreen(record = record, onMessage = ::message)
            }

            // Terminal states, driven only by the real service state.
            composable(Dest.CANCELLED) {
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let(viewModel::importAudio) }
                val cancelled = viewModel.serviceState as? TranscriptionState.Cancelled
                CancelledScreen(
                    name = titleForLiveName(cancelled?.name),
                    onBackHome = { navController.popBackStack(Dest.HOME, false) },
                    onChooseAnother = { picker.launch(arrayOf("audio/*")) },
                    // Offered only when the service still holds the audio.
                    onRetry = cancelled?.takeIf { it.retryable }?.jobId?.let { id ->
                        {
                            viewModel.retryJob(id)
                            navController.popBackStack(Dest.HOME, false)
                        }
                    }
                )
            }
            composable(Dest.DISCLOSURE) {
                CloudDisclosureScreen(
                    onAccept = {
                        // Persists the acknowledgement and starts the parked
                        // job; the job states then route onward as usual.
                        viewModel.acceptCloudDisclosure()
                        navController.popBackStack(Dest.HOME, false)
                    },
                    onDecline = {
                        viewModel.declineCloudDisclosure()
                        navController.popBackStack(Dest.HOME, false)
                    }
                )
            }
            composable(Dest.FAILED) {
                val failed = viewModel.serviceState as? TranscriptionState.Failed
                // A fresh pick carries its own read grant, unlike a dead share URI.
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let(viewModel::importAudio) }
                FailedScreen(
                    name = titleForLiveName(failed?.name),
                    reason = failed?.reason,
                    onChooseAnother = { picker.launch(arrayOf("audio/*")) },
                    onBackHome = { navController.popBackStack(Dest.HOME, false) },
                    onRetry = failed?.takeIf { it.retryable }?.jobId?.let { id ->
                        {
                            viewModel.retryJob(id)
                            // Back to Home so the Processing screen the retry
                            // produces is routed to normally.
                            navController.popBackStack(Dest.HOME, false)
                        }
                    }
                )
            }
            // A stored failed/cancelled attempt opened from History: the same
            // outcome screens, built from the persisted job rather than the
            // live service state.
            composable(Dest.ATTEMPT_ROUTE) { entry ->
                val jobId = entry.arguments?.getString(Dest.ATTEMPT_ARG)
                val attempt = viewModel.attempts.firstOrNull { it.jobId == jobId }
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let(viewModel::importAudio) }
                if (attempt == null) {
                    MissingItem("This item is no longer in History.")
                } else {
                    val name = titles.full(Titled(attempt.displayName, attempt.createdAt), peers)
                    val retry: (() -> Unit)? = if (attempt.retryable) {
                        { viewModel.retryJob(attempt.jobId); navController.popBackStack(Dest.HOME, false) }
                    } else null
                    if (attempt.cancelled) {
                        CancelledScreen(
                            name = name,
                            onBackHome = { navController.popBackStack(Dest.HOME, false) },
                            onChooseAnother = { picker.launch(arrayOf("audio/*")) },
                            onRetry = retry
                        )
                    } else {
                        FailedScreen(
                            name = name,
                            reason = attempt.reason,
                            category = FailureCategory.forReason(attempt.failureReason),
                            onChooseAnother = { picker.launch(arrayOf("audio/*")) },
                            onBackHome = { navController.popBackStack(Dest.HOME, false) },
                            onRetry = retry
                        )
                    }
                }
            }
        }


        if (confirmDeleteOne && openRecordId != null) {
            ConfirmDestructiveDialog(
                title = "Delete transcript?",
                body = "This can't be undone.",
                confirmLabel = "Delete",
                onDismiss = { confirmDeleteOne = false },
                onConfirm = {
                    confirmDeleteOne = false
                    viewModel.deleteTranscript(openRecordId)
                    // Land on History, and drop the now-dead Detail entry so
                    // Back cannot walk into a record that no longer exists.
                    navController.navigate(Dest.HISTORY) {
                        popUpTo(Dest.HOME)
                        launchSingleTop = true
                    }
                    message("Transcript deleted")
                }
            )
        }

        renameTarget?.let { target ->
            val item = titledItems.firstOrNull { it.first == target.id }?.second ?: Titled(target.displayName, target.createdAt)
            RenameDialog(
                current = titles.full(item, peers),
                onDismiss = { renameTarget = null },
                onConfirm = { typed ->
                    renameTarget = null
                    // Saving the automatic title unchanged keeps it automatic.
                    titles.nameToStore(item, typed, peers)?.let { stored ->
                        viewModel.renameTranscript(target.id, stored)
                        message("Transcript renamed")
                    }
                }
            )
        }
    }

    // Over the whole window, bottom bar included, like a Material modal sheet.
    RecordModeSheet(
        visible = modeSheetOpen && currentRoute == Dest.HOME,
        playbackAvailable = viewModel.playbackCaptureAvailable,
        block = viewModel.captureBlock,
        onChoose = ::chooseMode,
        onDismiss = { modeSheetOpen = false; viewModel.clearCaptureBlock() }
    )
    }
}

@Composable
private fun MissingItem(text: String = "That transcript is no longer available.") {
    Column(Modifier.fillMaxSize().padding(Spacing.edgeMargin)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The Activity behind a Compose context (Play's purchase sheet needs one). */
private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

package app.noti.ui

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.noti.assaad.AssaadSession
import app.noti.data.LibraryStore
import app.noti.data.SettingsStore
import app.noti.data.ThemeMode
import app.noti.exams.ExamStore
import app.noti.ink.StylusKeyHost
import app.noti.ui.assaad.AssaadPairingDialog
import app.noti.ui.assaad.AssaadTabletBrowserHost
import app.noti.ui.assaad.LocalAssaadSession
import app.noti.ui.editor.EditorScreen
import app.noti.ui.exams.ChooseCanvasDialog
import app.noti.ui.exams.StudyHubScreen
import app.noti.ui.library.LibraryScreen
import app.noti.ui.settings.SettingsScreen
import app.noti.ui.study.StudyLaunchTarget
import app.noti.ui.theme.NotiTheme
import java.io.File

private const val LIBRARY = "library"
private const val SETTINGS = "settings"
private const val EDITOR = "editor:"
private const val STUDY = "study"

/** Plain route state instead of a navigation library. */
@Composable
fun NotiApp(library: LibraryStore, settings: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exams = remember(library) {
        ExamStore(
            file = File(library.appContext.filesDir, "exams.json"),
            legacyFile = File(library.appContext.filesDir, "fuvest.json"),
        )
    }
    val platform = remember(library) { AssaadSession(context.applicationContext, library, scope) }
    DisposableEffect(platform) {
        val lifecycle = (context as? ComponentActivity)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) platform.start()
            if (event == Lifecycle.Event.ON_STOP) platform.stop()
        }
        lifecycle?.addObserver(observer)
        if (lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true) platform.start()
        onDispose { lifecycle?.removeObserver(observer); platform.dispose() }
    }
    CompositionLocalProvider(LocalAssaadSession provides platform) {
        NotiTheme(settings.theme) {
            SystemBarIcons(settings.theme)
            XiaomiStylusHostSync(settings.penButtonsEnabled && settings.penLegacyKeys)
            var route by rememberSaveable { mutableStateOfRoute() }
            var returnTo by rememberSaveable { mutableStateOf(LIBRARY) }
            var studyTarget by remember { mutableStateOf<StudyLaunchTarget?>(null) }
            var pendingAssaadCanvas by remember { mutableStateOf<Pair<String, String?>?>(null) }
            val destinations = rememberSaveableStateHolder()

            fun openEditor(id: String, back: String = LIBRARY, target: StudyLaunchTarget? = null) {
                returnTo = back
                studyTarget = target
                route = EDITOR + id
            }

            fun openAssaadOnCanvas(questionId: String?) {
                val openNotebook = route.takeIf { it.startsWith(EDITOR) }?.removePrefix(EDITOR)?.let(library::notebook)
                if (openNotebook != null && !openNotebook.isPdf) {
                    // Already inside a notebook: stay right on this canvas and focus Assaad on its Study sheet.
                    platform.focus = questionId ?: ""
                } else {
                    // Outside a notebook: always ask which notebook to open.
                    platform.focus = null
                    val suggested = platform.batch?.questions?.firstOrNull()?.tags?.firstOrNull()?.takeIf { it.isNotBlank() }
                        ?: "Plataforma Assaad"
                    pendingAssaadCanvas = suggested to questionId
                }
            }

            AssaadPairingDialog(platform, onOpenCanvas = ::openAssaadOnCanvas)

            when {
                route == SETTINGS -> SettingsScreen(settings, openRouter = returnTo == STUDY, onBack = { route = returnTo })
                route == STUDY -> destinations.SaveableStateProvider(STUDY) {
                    StudyHubScreen(
                        library = library,
                        settings = settings,
                        store = exams,
                        onBack = { route = LIBRARY },
                        onSettings = { returnTo = STUDY; route = SETTINGS },
                        onSource = { id, page ->
                            library.rememberPage(id, page - 1)
                            openEditor(id, STUDY)
                        },
                        onOpenOnCanvas = { notebookId, target ->
                            openEditor(notebookId, STUDY, target)
                        },
                    )
                }
                route.startsWith(EDITOR) -> {
                    val id = route.removePrefix(EDITOR)
                    val notebook = library.notebook(id)
                    if (notebook == null) {
                        LaunchedEffect(id) { route = returnTo }
                    } else {
                        LaunchedEffect(id) { library.markOpened(id) }
                        EditorScreen(
                            notebook = notebook,
                            library = library,
                            settings = settings,
                            examStore = exams,
                            initialStudyTarget = studyTarget,
                            onOpenStudyHub = { route = STUDY },
                            onBack = { studyTarget = null; route = returnTo },
                        )
                    }
                }
                else -> destinations.SaveableStateProvider(LIBRARY) {
                    LibraryScreen(
                        library = library,
                        settings = settings,
                        onOpen = { openEditor(it) },
                        onSettings = { returnTo = LIBRARY; route = SETTINGS },
                        onStudy = { route = STUDY },
                    )
                }
            }

            AssaadTabletBrowserHost(platform, onCanvas = {
                openAssaadOnCanvas(platform.focus?.takeIf { it.isNotEmpty() } ?: platform.current?.id)
            })

            pendingAssaadCanvas?.let { (suggested, questionId) ->
                ChooseCanvasDialog(
                    library = library,
                    settings = settings,
                    suggestedName = suggested,
                    onDismiss = { pendingAssaadCanvas = null },
                    onChosen = { notebookId ->
                        pendingAssaadCanvas = null
                        openEditor(notebookId, back = if (route == STUDY) STUDY else LIBRARY, target = StudyLaunchTarget.Assaad(questionId))
                    },
                )
            }
        }
    }
}

private fun mutableStateOfRoute() = androidx.compose.runtime.mutableStateOf(LIBRARY)

@Composable
private fun XiaomiStylusHostSync(hijack: Boolean) {
    val view = LocalView.current
    SideEffect {
        val activity = view.context as? Activity ?: return@SideEffect
        (activity as? StylusKeyHost)?.stylusKeys?.hijackXiaomiOsShortcuts = hijack
    }
}

/** Dark status/navigation bar icons on the light theme, light ones on the dark theme. */
@Composable
private fun SystemBarIcons(mode: ThemeMode) {
    val view = LocalView.current
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

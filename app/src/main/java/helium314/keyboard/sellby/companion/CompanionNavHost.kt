// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.sellby.companion.screens.AboutScreen
import helium314.keyboard.sellby.companion.screens.DashboardScreen
import helium314.keyboard.sellby.companion.screens.LoadingScreen
import helium314.keyboard.sellby.companion.screens.ProfilTokoScreen
import helium314.keyboard.sellby.companion.screens.PurchaseScreen
import helium314.keyboard.sellby.companion.screens.TrialScreen
import helium314.keyboard.sellby.companion.screens.WelcomeScreen
import helium314.keyboard.sellby.companion.tutorial.LessonId
import helium314.keyboard.sellby.companion.tutorial.TutorialMode
import helium314.keyboard.sellby.companion.tutorial.TutorialScreen
import helium314.keyboard.sellby.companion.tutorial.TutorialScript

private object Routes {
    const val LOADING = "loading"
    const val WELCOME = "welcome"
    const val PROFIL_TOKO = "profil_toko"
    const val TUTORIAL = "tutorial"
    const val TUTORIAL_REPLAY = "tutorial_replay"
    const val TRIAL = "trial"
    const val PURCHASE = "purchase"
    /** Purchase opened from the keyboard because a locked tab was tapped: "Kembali" closes the app. */
    const val PURCHASE_LOCKED = "purchase_locked"
    /** Purchase opened from "Tentang & Lisensi" (also while the trial is running): "Kembali" goes back there. */
    const val PURCHASE_FROM_ABOUT = "purchase_from_about"
    const val DASHBOARD = "dashboard"
    /** "Tentang & Lisensi", opened from the Dashboard's Support card. */
    const val ABOUT = "about"
    /** One stand-alone tutorial lesson opened from the keyboard's Settings -> Tutorial. */
    const val LESSON_PATTERN = "lesson/{lesson}"
    fun lesson(id: LessonId) = "lesson/${id.name}"
}

/** Loading -> Welcome -> Profil Toko -> Tutorial -> Trial -> Dashboard. The Trial page starts the 3-day
 *  free trial with every feature open; the Purchase page (still a placeholder) only appears once the
 *  trial has run out - at app start (Loading), after a data reset past the trial, or from the keyboard
 *  when a locked toolbar tab is tapped. Access Code is gone for good. The interactive Tutorial can
 *  also be replayed from the Dashboard. Loading->Welcome clears the splash from the back stack;
 *  Trial/Purchase->Dashboard clears the WHOLE stack (mirrors profile_setup_page.dart's
 *  `Navigator.pushAndRemoveUntil`) so back-from-Dashboard never re-shows onboarding -
 *  [helium314.keyboard.sellby.companion.screens.DashboardScreen]'s own BackHandler exits the app instead. */
private const val SLIDE_DURATION_MS = 300

/** The store name the user just entered, only to personalise the tutorial's dummy invoice text. Read
 *  here (not inside the tutorial package, which must never touch storage) and passed in as a plain String. */
@Composable
private fun tutorialStoreName(): String {
    val context = LocalContext.current
    return remember { context.prefs().getString(PREF_STORE_NAME, "")?.trim().orEmpty().ifEmpty { "Toko Kamu" } }
}

@Composable
fun CompanionNavHost(startAtPurchase: Boolean = false, startLesson: LessonId? = null, onFinish: () -> Unit) {
    val navController = rememberNavController()
    val context = LocalContext.current
    NavHost(
        navController = navController,
        startDestination = when {
            startLesson != null -> Routes.lesson(startLesson)
            startAtPurchase -> Routes.PURCHASE_LOCKED
            else -> Routes.LOADING
        },
        // Classic "push" stack transition: forward navigation slides the new screen in from the
        // right while the old one slides out to the left; back navigation (pop) does the reverse.
        // Set once here at the NavHost level rather than per-composable() since every destination
        // in this app wants the same feel - no screen has a reason to opt out.
        enterTransition = { slideInHorizontally(animationSpec = tween(SLIDE_DURATION_MS)) { it } },
        exitTransition = { slideOutHorizontally(animationSpec = tween(SLIDE_DURATION_MS)) { -it } },
        popEnterTransition = { slideInHorizontally(animationSpec = tween(SLIDE_DURATION_MS)) { -it } },
        popExitTransition = { slideOutHorizontally(animationSpec = tween(SLIDE_DURATION_MS)) { it } },
    ) {
        composable(Routes.LOADING) {
            LoadingScreen(
                onOnboardingDone = {
                    // Installs that finished onboarding before the trial existed get their 3 days now.
                    TrialPolicy.migrateIfNeeded(context)
                    val next = if (TrialPolicy.state(context) is TrialState.Expired) Routes.PURCHASE else Routes.DASHBOARD
                    navController.navigate(next) { popUpTo(Routes.LOADING) { inclusive = true } }
                },
                onNeedsOnboarding = {
                    navController.navigate(Routes.WELCOME) { popUpTo(Routes.LOADING) { inclusive = true } }
                },
            )
        }
        composable(Routes.WELCOME) {
            WelcomeScreen(onStart = { navController.navigate(Routes.PROFIL_TOKO) })
        }
        composable(Routes.PROFIL_TOKO) {
            ProfilTokoScreen(onSaved = { navController.navigate(Routes.TUTORIAL) })
        }
        composable(Routes.TUTORIAL) {
            TutorialScreen(
                mode = TutorialMode.Onboarding,
                storeName = tutorialStoreName(),
                onBack = { navController.popBackStack() },
                // Finished or skipped: both continue the onboarding. The trial/premium branching
                // lives here (not in the tutorial package, which must never touch prefs).
                onFinished = {
                    when (TrialPolicy.state(context)) {
                        // Premium (Round 2 Billing) skips Trial & Purchase altogether; a trial that is
                        // already running (data was reset / restored from Block Store mid-trial) just
                        // carries on - the "Coba GRATIS" page would promise a fresh start it can't give.
                        TrialState.Premium, is TrialState.Active -> {
                            context.prefs().edit { putBoolean(PREF_ONBOARDING_COMPLETED, true) }
                            navController.navigate(Routes.DASHBOARD) { popUpTo(Routes.WELCOME) { inclusive = true } }
                        }
                        // The trial already ran out (e.g. data was reset after day 3): no second trial.
                        TrialState.Expired ->
                            navController.navigate(Routes.PURCHASE) { popUpTo(Routes.TUTORIAL) { inclusive = true } }
                        else ->
                            navController.navigate(Routes.TRIAL) { popUpTo(Routes.TUTORIAL) { inclusive = true } }
                    }
                },
            )
        }
        composable(Routes.TRIAL) {
            TrialScreen(onStart = {
                // Idempotent: never restarts a trial that already began (see TrialPolicy.startIfNeeded).
                TrialPolicy.startIfNeeded(context)
                context.prefs().edit { putBoolean(PREF_ONBOARDING_COMPLETED, true) }
                navController.navigate(Routes.DASHBOARD) { popUpTo(navController.graph.id) { inclusive = true } }
            })
        }
        composable(Routes.TUTORIAL_REPLAY) {
            TutorialScreen(
                mode = TutorialMode.Replay,
                storeName = tutorialStoreName(),
                onBack = { navController.popBackStack() },
                onFinished = { navController.popBackStack() },
            )
        }
        composable(
            Routes.LESSON_PATTERN,
            arguments = listOf(navArgument("lesson") { type = NavType.StringType }),
        ) { entry ->
            val lesson = LessonId.entries.firstOrNull { it.name == entry.arguments?.getString("lesson") } ?: LessonId.Invoice
            TutorialScreen(
                mode = TutorialMode.Lesson,
                storeName = tutorialStoreName(),
                chapters = TutorialScript.chaptersFor(lesson),
                // The lesson is the only thing this launch of the app shows: leaving it (finished,
                // skipped or back on the first step) closes the page and returns to the app the
                // user was typing in.
                onBack = onFinish,
                onFinished = { onFinish() },
            )
        }
        composable(Routes.PURCHASE) {
            PurchaseScreen(onContinue = {
                navController.navigate(Routes.DASHBOARD) { popUpTo(navController.graph.id) { inclusive = true } }
            })
        }
        composable(Routes.PURCHASE_LOCKED) {
            PurchaseScreen(onContinue = onFinish, cameFromLockedFeature = true)
        }
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onExit = onFinish,
                onDataReset = {
                    navController.navigate(Routes.WELCOME) { popUpTo(Routes.DASHBOARD) { inclusive = true } }
                },
                onReplayTutorial = { navController.navigate(Routes.TUTORIAL_REPLAY) },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(
                onBack = { navController.popBackStack() },
                onOpenPurchase = { navController.navigate(Routes.PURCHASE_FROM_ABOUT) },
            )
        }
        composable(Routes.PURCHASE_FROM_ABOUT) {
            PurchaseScreen(onContinue = { navController.popBackStack() }, cameFromLockedFeature = true)
        }
    }
}

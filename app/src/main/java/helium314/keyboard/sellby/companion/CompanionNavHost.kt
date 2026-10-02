// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.sellby.companion.screens.DashboardScreen
import helium314.keyboard.sellby.companion.screens.LoadingScreen
import helium314.keyboard.sellby.companion.screens.ProfilTokoScreen
import helium314.keyboard.sellby.companion.screens.PurchaseScreen
import helium314.keyboard.sellby.companion.screens.WelcomeScreen

private object Routes {
    const val LOADING = "loading"
    const val WELCOME = "welcome"
    const val PROFIL_TOKO = "profil_toko"
    const val PURCHASE = "purchase"
    const val DASHBOARD = "dashboard"
}

/** Loading -> Welcome -> Profil Toko -> Purchase -> Dashboard, per the confirmed Fase 5 Round 1
 *  alur (Access Code removed entirely, Purchase is new and Round-1-placeholder). Loading->Welcome
 *  clears the splash from the back stack; Purchase->Dashboard clears the WHOLE onboarding stack
 *  (mirrors profile_setup_page.dart's `Navigator.pushAndRemoveUntil`) so back-from-Dashboard never
 *  re-shows onboarding - [helium314.keyboard.sellby.companion.screens.DashboardScreen]'s own
 *  BackHandler exits the app instead. */
private const val SLIDE_DURATION_MS = 300

@Composable
fun CompanionNavHost(onFinish: () -> Unit) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = Routes.LOADING,
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
                    navController.navigate(Routes.DASHBOARD) { popUpTo(Routes.LOADING) { inclusive = true } }
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
            val context = LocalContext.current
            ProfilTokoScreen(onSaved = {
                if (context.prefs().getBoolean(PREF_PREMIUM_PURCHASED, false)) {
                    // Round 2 Billing sudah aktif utk akun ini - lewati Purchase sepenuhnya.
                    context.prefs().edit { putBoolean(PREF_ONBOARDING_COMPLETED, true) }
                    navController.navigate(Routes.DASHBOARD) { popUpTo(Routes.WELCOME) { inclusive = true } }
                } else {
                    navController.navigate(Routes.PURCHASE)
                }
            })
        }
        composable(Routes.PURCHASE) {
            PurchaseScreen(onContinue = {
                navController.navigate(Routes.DASHBOARD) { popUpTo(Routes.WELCOME) { inclusive = true } }
            })
        }
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onExit = onFinish,
                onDataReset = {
                    navController.navigate(Routes.WELCOME) { popUpTo(Routes.DASHBOARD) { inclusive = true } }
                },
            )
        }
    }
}

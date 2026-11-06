package app.noti.ui.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.noti.data.PenButtonAction
import app.noti.data.SettingsStore
import app.noti.ink.PenHold
import app.noti.ui.theme.NotiTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp-mdpi")
class PenButtonsSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun usersCanAssignButtonActionsAndReachTheHardwareTester() {
        compose.activity.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = SettingsStore(compose.activity)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                NotiTheme(settings.theme) { SettingsScreen(settings, onBack = {}) }
            }
        }
        compose.onNodeWithText("Pen buttons").performClick()
        compose.onNodeWithText("Writing button click").performClick()
        compose.onNodeWithText("Undo").performClick()
        compose.runOnIdle { assertEquals(PenButtonAction.Undo, settings.primaryButtonAction) }
        compose.onNodeWithText("Focus button click").performClick()
        compose.onNodeWithText("Next quick color").performClick()
        compose.runOnIdle { assertEquals(PenButtonAction.NextColor, SettingsStore(compose.activity).focusButtonAction) }
        compose.onNodeWithText("Swap Xiaomi button order").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(settings.penSwapLegacyKeys) }
        compose.runOnIdle { assertEquals(PenHold.Dial, settings.penButtonHold); assertFalse(settings.toolDialAlways) }
        compose.onNodeWithText("Always show the tool dial").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(SettingsStore(compose.activity).toolDialAlways) }
        compose.onNodeWithText("Hold writing button").performScrollTo().performClick()
        compose.onNodeWithText("Erase temporarily").performClick()
        compose.runOnIdle { assertEquals(PenHold.Erase, SettingsStore(compose.activity).penButtonHold) }
        compose.onNodeWithText("Always show the tool dial").assertDoesNotExist()
        compose.onNodeWithText("Test click and hold").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Waiting for pen button input…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Scratch area").assertIsDisplayed()
        compose.onNodeWithText("S Pen button clicks").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(SettingsStore(compose.activity).penMotionClicks) }
    }

    @Test fun samsungTabletsShowTheSPenButtonAndHideXiaomiOnlyRows() {
        ShadowBuild.setManufacturer("samsung")
        ShadowBuild.setBrand("samsung")
        compose.activity.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = SettingsStore(compose.activity)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                NotiTheme(settings.theme) { SettingsScreen(settings, onBack = {}) }
            }
        }
        compose.onNodeWithText("Pen buttons").performClick()
        compose.onNodeWithText("S Pen button click").performClick()
        compose.onNodeWithText("Toggle lasso").performClick()
        compose.runOnIdle { assertEquals(PenButtonAction.ToggleLasso, settings.primaryButtonAction) }
        compose.onNodeWithText("Hold S Pen button").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("S Pen button clicks").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Screenshot button click").assertDoesNotExist()
        compose.onNodeWithText("Focus button click").assertDoesNotExist()
        compose.onNodeWithText("Swap Xiaomi button order").assertDoesNotExist()
    }
}

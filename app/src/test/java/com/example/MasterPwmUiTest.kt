package com.example

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.example.model.AcousticFrameTelemetry
import com.example.model.EngineStatus
import com.example.model.HapticHardwareCapabilities
import com.example.model.MirFeatureSnapshot
import com.example.model.TuningConfig
import com.example.ui.screens.ConsoleScreen
import com.example.ui.screens.TuningScreen
import com.example.ui.theme.SubPulseTheme
import com.example.viewmodel.SubwooferUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MasterPwmUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun tuningScreen_rendersMasterPwmNode_andPreservesAllExistingTags() {
        val tuning = TuningConfig()
        val telemetry = AcousticFrameTelemetry(
            estimatedBpm = 128,
            pwmSilenceGapMs = 142,
            isPwmPulseActive = true,
            isPwmBpmLocked = true,
            features = MirFeatureSnapshot(subRms = 0.25f)
        )

        composeTestRule.setContent {
            SubPulseTheme {
                TuningScreen(
                    tuning = tuning,
                    telemetry = telemetry,
                    hardware = HapticHardwareCapabilities(),
                    onUpdateTuning = {},
                    onResetDefaults = {},
                    onPlayTestPulse = {}
                )
            }
        }

        val expectedTags = listOf(
            "hardware_actuator_badge",
            "meter_master_vu",
            "button_toggle_bass",
            "button_toggle_kick",
            "slider_master_threshold",
            "slider_master_drive_ceiling",
            "toggle_pwm_mode",
            "slider_pwm_pulse_gap",
            "toggle_force_erm",
            "toggle_master_lpf",
            "slider_master_lpf_cutoff",
            "toggle_engine_throttle",
            "badge_engine_cadence_rpm",
            "slider_engine_cadence_rpm",
            "action_test_master_pulse"
        )

        expectedTags.forEach { tag ->
            composeTestRule.onNodeWithTag(tag)
                .performScrollTo()
                .assertIsDisplayed()
        }

        composeTestRule.onNodeWithText("PULSE ACTIVE").assertExists()
        composeTestRule.onNodeWithText("GAP: 142 ms").assertExists()
        composeTestRule.onNodeWithText("128 BPM LOCK").assertExists()
        composeTestRule.onNodeWithText("142 ms • 128 BPM LOCK").assertExists()
    }

    @Test
    fun tuningScreen_channelButtonsAndPwmControlsMutateStateWithoutClearingGains() {
        var currentTuning by mutableStateOf(
            TuningConfig(
                kickGain = 0.91f,
                bassGain = 0.83f,
                pwmEnabled = true,
                pwmPulseGapMs = 135f,
                kickEnabled = true,
                bassEnabled = true
            )
        )

        composeTestRule.setContent {
            SubPulseTheme {
                TuningScreen(
                    tuning = currentTuning,
                    telemetry = AcousticFrameTelemetry(),
                    hardware = HapticHardwareCapabilities(),
                    onUpdateTuning = { transform -> currentTuning = transform(currentTuning) },
                    onResetDefaults = {},
                    onPlayTestPulse = {}
                )
            }
        }

        // Turn off Bass without clearing bassGain
        composeTestRule.onNodeWithTag("button_toggle_bass")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        assertFalse(currentTuning.bassEnabled)
        assertEquals(0.83f, currentTuning.bassGain, 0.0001f)
        composeTestRule.onNodeWithText("Bass: OFF").assertExists()

        // Turn off Kick without clearing kickGain
        composeTestRule.onNodeWithTag("button_toggle_kick")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        assertFalse(currentTuning.kickEnabled)
        assertEquals(0.91f, currentTuning.kickGain, 0.0001f)
        composeTestRule.onNodeWithText("Kick: OFF").assertExists()

        // Toggle PWM mode off then back on
        composeTestRule.onNodeWithTag("toggle_pwm_mode")
            .performScrollTo()
            .assertIsOn()
            .performClick()
        composeTestRule.waitForIdle()
        assertFalse(currentTuning.pwmEnabled)
        composeTestRule.onNodeWithTag("toggle_pwm_mode").assertIsOff()

        composeTestRule.onNodeWithTag("toggle_pwm_mode").performClick()
        composeTestRule.waitForIdle()
        assertTrue(currentTuning.pwmEnabled)
        composeTestRule.onNodeWithTag("toggle_pwm_mode").assertIsOn()

        // Adjust PWM Pulse Gap slider
        composeTestRule.onNodeWithTag("slider_pwm_pulse_gap")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(210f)
            }
        composeTestRule.waitForIdle()
        assertEquals(210f, currentTuning.pwmPulseGapMs, 1f)
    }

    @Test
    fun consoleScreen_displaysLiveMasterPwmBadge_andNavigatesOnClick() {
        var uiState by mutableStateOf(
            SubwooferUiState(
                status = EngineStatus.RUNNING,
                tuning = TuningConfig(
                    masterThreshold = 0.45f,
                    masterDrive = 0.88f,
                    pwmEnabled = true,
                    pwmPulseGapMs = 135f,
                    kickEnabled = true,
                    bassEnabled = false
                ),
                telemetry = AcousticFrameTelemetry(
                    estimatedBpm = 136,
                    pwmSilenceGapMs = 148,
                    isPwmPulseActive = true,
                    isPwmBpmLocked = true,
                    features = MirFeatureSnapshot(subRms = 0.30f, kickBody = 0.10f)
                )
            )
        )
        var openTuningCalls = 0

        composeTestRule.setContent {
            SubPulseTheme {
                ConsoleScreen(
                    uiState = uiState,
                    onSelectSource = {},
                    onToggleEngine = {},
                    onToggleHapticMute = {},
                    onSelectPreset = {},
                    onSelectSubLabPattern = {},
                    onTriggerKickPad = {},
                    onSetBassPadHeld = {},
                    onDismissError = {},
                    onOpenTuning = { openTuningCalls++ }
                )
            }
        }

        composeTestRule.onNodeWithTag("console_master_pwm_badge").assertIsDisplayed()
        composeTestRule.onNodeWithText("OPEN").assertExists()
        composeTestRule.onNodeWithText("GATE 45%").assertExists()
        composeTestRule.onNodeWithText("PWM 148ms • 136 BPM").assertExists()
        composeTestRule.onNodeWithText("KICK ON").assertExists()
        composeTestRule.onNodeWithText("BASS OFF").assertExists()

        // Verify GATED state when energy drops below physical gate (0.45 * 0.40 = 0.18)
        uiState = uiState.copy(
            telemetry = uiState.telemetry.copy(
                features = MirFeatureSnapshot(subRms = 0.05f, kickBody = 0.02f)
            )
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("GATED").assertExists()

        // Verify MUTED state when haptics are muted
        uiState = uiState.copy(
            tuning = uiState.tuning.copy(hapticsMuted = true)
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("MUTED").assertExists()

        // Verify STANDBY state when engine is idle (ConsoleHeader also shows STANDBY, so match on the merged badge node)
        uiState = uiState.copy(status = EngineStatus.IDLE)
        composeTestRule.waitForIdle()
        composeTestRule.onNode(
            hasTestTag("console_master_pwm_badge") and hasText("STANDBY")
        ).assertExists()

        // Verify tapping the badge invokes onOpenTuning
        composeTestRule.onNodeWithTag("console_master_pwm_badge").performClick()
        assertEquals(1, openTuningCalls)
    }
}

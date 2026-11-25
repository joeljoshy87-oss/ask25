package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.AcousticFrameTelemetry
import com.example.model.ActuatorType
import com.example.model.HapticHardwareCapabilities
import com.example.model.TuningConfig
import com.example.ui.theme.LocalSubPulseColors
import com.example.ui.theme.SubPulseSignalColors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Master PWM Threshold & Intensity Node.
 *
 * Central command node for the SubPulse haptic engine providing:
 * 1. Dynamic Hardware Status Badge: Green for Flagship LRA, Amber for Budget ERM.
 * 2. Live VU & Threshold Needle Meter: Real-time progress bar of acoustic low-end energy
 *    with vertical gate threshold needle and live PWM pulse / silence gap / BPM sync readout.
 * 3. Channel Isolation Row: Independent Bass & Kick turn-off toggle buttons preserving gains.
 * 4. Master Trigger Gate Slider: Sets acoustic energy threshold (0% to 100%).
 * 5. Master Drive Ceiling Slider: Sets peak output drive ceiling (0% to 100%).
 * 6. PWM Switch & Bass Pulse Silence Gap Slider: Pitch-timed pulses with real silence gaps (45ms to 320ms).
 * 7. Force ERM Mode Toggle: Manual fallback override for legacy coin/budget motors.
 * 8. Master Input Low-Pass Filter: Switch toggle and Cutoff Slider (80 Hz to 450 Hz).
 * 9. Engine Throttle Cadence: Switch toggle, virtual cadence RPM slider (18 Hz to 48 Hz), and animated RPM badge.
 * 10. Test Gate Pulse Button: Instantly fires a calibrated gate test pulse.
 */
@Composable
fun HapticMasterNodeCard(
    tuning: TuningConfig,
    telemetry: AcousticFrameTelemetry,
    hardware: HapticHardwareCapabilities,
    onUpdateTuning: ((TuningConfig) -> TuningConfig) -> Unit,
    onPlayTestPulse: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val signalColors = LocalSubPulseColors.current

    // Isolate 60fps telemetry reads inside dedicated leaf sub-composables so sliders never recompose every frame.
    val latestTelemetryState = rememberUpdatedState(telemetry)
    val telemetryProvider = remember(latestTelemetryState) { { latestTelemetryState.value } }

    val isErm = tuning.forceErmMode || hardware.actuatorType == ActuatorType.BUDGET_LEGACY_ERM
    val badgeText = if (isErm) {
        stringResource(R.string.badge_erm_budget)
    } else {
        stringResource(R.string.badge_lra_flagship)
    }
    val badgeColor = if (isErm) signalColors.actuator else signalColors.liveGreen
    val badgeContainerColor = if (isErm) signalColors.actuatorContainer else signalColors.liveGreenContainer
    val gateThreshold = tuning.masterThreshold.coerceIn(0f, 1f)

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, colorScheme.outlineVariant, RoundedCornerShape(22.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header: Title, Subtitle, and Dynamic Hardware Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = null,
                        tint = signalColors.bass,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.card_master_node_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.card_master_node_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Dynamic Hardware Status Badge (Flagship LRA green vs Budget ERM amber)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(badgeContainerColor)
                        .border(1.dp, badgeColor.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                        .testTag("hardware_actuator_badge"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(badgeColor)
                        )
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = badgeColor
                        )
                    }
                }
            }

            // Live VU, Threshold Needle & PWM Status Readout (isolated 60fps telemetry scope)
            MasterThresholdVuMeter(
                telemetryProvider = telemetryProvider,
                threshold = gateThreshold,
                hapticsMuted = tuning.hapticsMuted,
                masterDrive = tuning.masterDrive,
                pwmEnabled = tuning.pwmEnabled,
                pwmPulseGapMs = tuning.pwmPulseGapMs,
                signalColors = signalColors,
                backgroundColor = colorScheme.background,
                outlineColor = colorScheme.outlineVariant,
                textColor = colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            // Side-by-side Bass & Kick Turn-Off Toggle Row (isolates channels without clearing gains)
            ChannelIsolationToggleRow(
                bassEnabled = tuning.bassEnabled,
                kickEnabled = tuning.kickEnabled,
                signalColors = signalColors,
                onToggleBass = { onUpdateTuning { it.copy(bassEnabled = !it.bassEnabled) } },
                onToggleKick = { onUpdateTuning { it.copy(kickEnabled = !it.kickEnabled) } }
            )

            // Master Trigger Gate Slider
            MasterNodeSliderRow(
                label = stringResource(R.string.label_master_threshold),
                valueText = "${(tuning.masterThreshold * 100).roundToInt()}%",
                value = tuning.masterThreshold,
                range = 0f..1f,
                accent = signalColors.bass,
                testTag = "slider_master_threshold",
                onValueChange = { v -> onUpdateTuning { it.copy(masterThreshold = v) } }
            )

            // Master Output Drive Ceiling Slider
            MasterNodeSliderRow(
                label = stringResource(R.string.label_master_drive_ceiling),
                valueText = "${(tuning.masterDrive * 100).roundToInt()}%",
                value = tuning.masterDrive,
                range = 0f..1f,
                accent = signalColors.actuator,
                testTag = "slider_master_drive_ceiling",
                onValueChange = { v -> onUpdateTuning { it.copy(masterDrive = v) } }
            )

            // PWM Pitch-Timed Pulse Switch
            MasterNodeToggleRow(
                label = stringResource(R.string.label_pwm_mode),
                subtitle = stringResource(R.string.desc_pwm_mode),
                checked = tuning.pwmEnabled,
                accent = signalColors.bass,
                testTag = "toggle_pwm_mode",
                onCheckedChange = { checked -> onUpdateTuning { it.copy(pwmEnabled = checked) } }
            )

            // Bass Pulse Silence Gap Slider with isolated live ms + BPM lock badge
            MasterNodePulseGapRow(
                label = stringResource(R.string.label_pwm_pulse_gap),
                pwmEnabled = tuning.pwmEnabled,
                pulseGapMs = tuning.pwmPulseGapMs,
                telemetryProvider = telemetryProvider,
                accent = if (tuning.pwmEnabled) signalColors.bass else colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                signalColors = signalColors,
                onValueChange = { v -> onUpdateTuning { it.copy(pwmPulseGapMs = v) } }
            )

            // Force ERM Pulse Mode Toggle
            MasterNodeToggleRow(
                label = stringResource(R.string.label_force_erm),
                subtitle = stringResource(R.string.desc_force_erm),
                checked = tuning.forceErmMode,
                accent = signalColors.actuator,
                testTag = "toggle_force_erm",
                onCheckedChange = { checked -> onUpdateTuning { it.copy(forceErmMode = checked) } }
            )

            // Master Input Low-Pass Filter Block
            MasterNodeToggleRow(
                label = stringResource(R.string.label_master_lpf),
                subtitle = stringResource(R.string.desc_master_lpf),
                checked = tuning.masterLpfEnabled,
                accent = signalColors.bass,
                testTag = "toggle_master_lpf",
                onCheckedChange = { checked -> onUpdateTuning { it.copy(masterLpfEnabled = checked) } }
            )

            val lpfCutoffHz = tuning.masterLpfCutoffHz.roundToInt()
            val lpfCutoffDescription = when {
                lpfCutoffHz <= 140 -> "$lpfCutoffHz Hz — Strict Sub Only"
                lpfCutoffHz <= 280 -> "$lpfCutoffHz Hz — Vocals & Noise Rejection"
                else -> "$lpfCutoffHz Hz — Wide Bass & Kick Body"
            }
            MasterNodeSliderRow(
                label = stringResource(R.string.label_master_lpf_cutoff),
                valueText = lpfCutoffDescription,
                value = tuning.masterLpfCutoffHz,
                range = 80f..450f,
                accent = signalColors.bass,
                testTag = "slider_master_lpf_cutoff",
                onValueChange = { v -> onUpdateTuning { it.copy(masterLpfCutoffHz = v) } }
            )

            // Engine Throttle Cadence Block
            MasterNodeToggleRow(
                label = stringResource(R.string.label_engine_throttle),
                subtitle = stringResource(R.string.desc_engine_throttle),
                checked = tuning.engineThrottleEnabled,
                accent = signalColors.actuator,
                testTag = "toggle_engine_throttle",
                onCheckedChange = { checked -> onUpdateTuning { it.copy(engineThrottleEnabled = checked) } }
            )

            // Engine Throttle Cadence Range Header & Animated RPM Badge (isolated 60fps telemetry scope)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.desc_cadence_range),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                )

                EngineCadenceBadge(
                    engineThrottleEnabled = tuning.engineThrottleEnabled,
                    engineCadenceRpm = tuning.engineCadenceRpm,
                    hapticsMuted = tuning.hapticsMuted,
                    masterDrive = tuning.masterDrive,
                    telemetryProvider = telemetryProvider,
                    signalColors = signalColors
                )
            }

            val nominalCadenceHz = (20f * (0.60f + 0.60f * tuning.engineCadenceRpm)).coerceIn(18f, 48f).roundToInt()
            MasterNodeSliderRow(
                label = stringResource(R.string.label_engine_cadence_rpm),
                valueText = "${(tuning.engineCadenceRpm * 100).roundToInt()}% ($nominalCadenceHz Hz)",
                value = tuning.engineCadenceRpm,
                range = 0f..1f,
                accent = signalColors.actuator,
                testTag = "slider_engine_cadence_rpm",
                onValueChange = { v -> onUpdateTuning { it.copy(engineCadenceRpm = v) } }
            )

            // Test Gate Pulse Button
            Button(
                onClick = onPlayTestPulse,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = signalColors.actuator.copy(alpha = 0.18f),
                    contentColor = signalColors.actuator
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, signalColors.actuator.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                    .minimumInteractiveComponentSize()
                    .testTag("action_test_master_pulse")
            ) {
                Icon(
                    imageVector = Icons.Filled.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.action_test_master_pulse),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun MasterThresholdVuMeter(
    telemetryProvider: () -> AcousticFrameTelemetry,
    threshold: Float,
    hapticsMuted: Boolean,
    masterDrive: Float,
    pwmEnabled: Boolean,
    pwmPulseGapMs: Float,
    signalColors: SubPulseSignalColors,
    backgroundColor: Color,
    outlineColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val telemetry = telemetryProvider()

    // Acoustic MIR low-end RMS energy: max(subRms, kickBody)
    val rawAcousticLevel = max(telemetry.features.subRms, telemetry.features.kickBody)
    val physicalGateLevel = threshold * 0.40f
    // Normalized against maximum gate scale (0.40f) so threshold needle and signal match 1:1
    val normalizedEnergy = (rawAcousticLevel / 0.40f).coerceIn(0f, 1f)
    val isBreached = rawAcousticLevel >= physicalGateLevel &&
        rawAcousticLevel > 0.001f &&
        !hapticsMuted &&
        masterDrive > 0.02f

    val energy by animateFloatAsState(
        targetValue = normalizedEnergy,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 600f),
        label = "master_vu_energy"
    )

    val barGlowColor by animateColorAsState(
        targetValue = if (isBreached) signalColors.bass else textColor.copy(alpha = 0.35f),
        animationSpec = tween(durationMillis = 120),
        label = "vu_bar_color"
    )

    val pwmStateText = when {
        !pwmEnabled -> "PWM OFF"
        telemetry.isPwmPulseActive -> "PULSE ACTIVE"
        telemetry.pwmSilenceGapMs > 0 -> "SILENCE GAP"
        else -> "PWM READY"
    }
    val pwmStateColor = when {
        !pwmEnabled -> textColor.copy(alpha = 0.65f)
        telemetry.isPwmPulseActive -> signalColors.liveGreen
        telemetry.pwmSilenceGapMs > 0 -> signalColors.bass
        else -> textColor
    }
    val gapReadoutText = if (telemetry.pwmSilenceGapMs > 0) {
        "GAP: ${telemetry.pwmSilenceGapMs} ms"
    } else {
        "TARGET GAP: ${pwmPulseGapMs.roundToInt()} ms"
    }
    val bpmSyncText = if (telemetry.isPwmBpmLocked && telemetry.estimatedBpm > 0) {
        "${telemetry.estimatedBpm} BPM LOCK"
    } else {
        "PITCH TIMED"
    }

    Column(
        modifier = modifier.testTag("meter_master_vu"),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // Readout row: Energy % | Gate % | Open / Gated pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = if (isBreached) signalColors.bass else textColor,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "ACOUSTIC ENERGY: ${(energy * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isBreached) signalColors.bass else textColor
                )
            }
            Text(
                text = "GATE: ${(threshold * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = signalColors.actuator
            )
            Text(
                text = if (isBreached) "TRIGGER OPEN" else "GATED",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = if (isBreached) signalColors.bass else textColor.copy(alpha = 0.6f)
            )
        }

        // Live VU canvas with energy progress fill and vertical threshold needle
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(backgroundColor)
                .border(1.dp, outlineColor, RoundedCornerShape(8.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(24.dp)) {
                val corner = CornerRadius(6.dp.toPx(), 6.dp.toPx())

                // Energy fill
                val fillWidth = (energy.coerceIn(0f, 1f) * size.width).coerceAtLeast(0f)
                if (fillWidth > 0f) {
                    if (isBreached) {
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(signalColors.bassDeep, signalColors.bass),
                                startX = 0f,
                                endX = fillWidth
                            ),
                            size = Size(fillWidth, size.height),
                            cornerRadius = corner
                        )
                    } else {
                        drawRoundRect(
                            color = barGlowColor,
                            size = Size(fillWidth, size.height),
                            cornerRadius = corner
                        )
                    }
                }

                // Vertical gate threshold needle
                val needleX = (threshold.coerceIn(0f, 1f) * size.width).coerceIn(0f, size.width)
                val needleColor = if (isBreached) Color.White else signalColors.actuator
                drawLine(
                    color = needleColor,
                    start = Offset(needleX, 0f),
                    end = Offset(needleX, size.height),
                    strokeWidth = 2.5.dp.toPx()
                )
                // Small indicator beads at top and bottom of needle
                drawCircle(
                    color = needleColor,
                    radius = 2.5.dp.toPx(),
                    center = Offset(needleX, 2.5.dp.toPx())
                )
                drawCircle(
                    color = needleColor,
                    radius = 2.5.dp.toPx(),
                    center = Offset(needleX, size.height - 2.5.dp.toPx())
                )
            }
        }

        // Live PWM pulse / real silence gap / BPM sync telemetry row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(pwmStateColor)
                )
                Text(
                    text = pwmStateText,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = pwmStateColor
                )
            }
            Text(
                text = gapReadoutText,
                style = MaterialTheme.typography.labelSmall,
                color = if (pwmEnabled) signalColors.bass else textColor.copy(alpha = 0.6f)
            )
            Text(
                text = bpmSyncText,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (telemetry.isPwmBpmLocked && telemetry.estimatedBpm > 0) {
                    signalColors.liveGreen
                } else {
                    textColor.copy(alpha = 0.8f)
                }
            )
        }
    }
}

@Composable
private fun ChannelIsolationToggleRow(
    bassEnabled: Boolean,
    kickEnabled: Boolean,
    signalColors: SubPulseSignalColors,
    onToggleBass: () -> Unit,
    onToggleKick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.label_channel_isolation),
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ChannelToggleButton(
                label = stringResource(if (bassEnabled) R.string.action_bass_on else R.string.action_bass_off),
                enabled = bassEnabled,
                activeIcon = Icons.Filled.Waves,
                activeAccent = signalColors.bass,
                activeContainer = signalColors.bassContainer,
                testTag = "button_toggle_bass",
                onClick = onToggleBass,
                modifier = Modifier.weight(1f)
            )
            ChannelToggleButton(
                label = stringResource(if (kickEnabled) R.string.action_kick_on else R.string.action_kick_off),
                enabled = kickEnabled,
                activeIcon = Icons.Filled.Bolt,
                activeAccent = signalColors.kick,
                activeContainer = signalColors.kickContainer,
                testTag = "button_toggle_kick",
                onClick = onToggleKick,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ChannelToggleButton(
    label: String,
    enabled: Boolean,
    activeIcon: ImageVector,
    activeAccent: Color,
    activeContainer: Color,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val containerColor = if (enabled) activeContainer else colorScheme.background
    val borderColor = if (enabled) activeAccent.copy(alpha = 0.75f) else colorScheme.error.copy(alpha = 0.55f)
    val contentTint = if (enabled) activeAccent else colorScheme.error

    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentTint
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        modifier = modifier
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .minimumInteractiveComponentSize()
            .testTag(testTag)
    ) {
        Icon(
            imageVector = if (enabled) activeIcon else Icons.Filled.VolumeOff,
            contentDescription = null,
            tint = contentTint,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = if (enabled) colorScheme.onSurface else colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MasterNodePulseGapRow(
    label: String,
    pwmEnabled: Boolean,
    pulseGapMs: Float,
    telemetryProvider: () -> AcousticFrameTelemetry,
    accent: Color,
    signalColors: SubPulseSignalColors,
    onValueChange: (Float) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            )
            PwmGapLiveReadout(
                pwmEnabled = pwmEnabled,
                pulseGapMs = pulseGapMs,
                telemetryProvider = telemetryProvider,
                accent = accent,
                signalColors = signalColors
            )
        }
        Slider(
            value = pulseGapMs.coerceIn(45f, 320f),
            onValueChange = onValueChange,
            valueRange = 45f..320f,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = colorScheme.background
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("slider_pwm_pulse_gap")
        )
    }
}

@Composable
private fun PwmGapLiveReadout(
    pwmEnabled: Boolean,
    pulseGapMs: Float,
    telemetryProvider: () -> AcousticFrameTelemetry,
    accent: Color,
    signalColors: SubPulseSignalColors
) {
    val telemetry = telemetryProvider()
    val targetMs = pulseGapMs.roundToInt()
    val text = when {
        !pwmEnabled -> "OFF ($targetMs ms)"
        telemetry.isPwmBpmLocked && telemetry.estimatedBpm > 0 -> {
            val activeGap = if (telemetry.pwmSilenceGapMs > 0) telemetry.pwmSilenceGapMs else targetMs
            "$activeGap ms • ${telemetry.estimatedBpm} BPM LOCK"
        }
        telemetry.pwmSilenceGapMs > 0 -> "${telemetry.pwmSilenceGapMs} ms LIVE"
        else -> "$targetMs ms"
    }
    val textColor = when {
        !pwmEnabled -> accent
        telemetry.isPwmBpmLocked && telemetry.estimatedBpm > 0 -> signalColors.liveGreen
        else -> accent
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = textColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun EngineCadenceBadge(
    engineThrottleEnabled: Boolean,
    engineCadenceRpm: Float,
    hapticsMuted: Boolean,
    masterDrive: Float,
    telemetryProvider: () -> AcousticFrameTelemetry,
    signalColors: SubPulseSignalColors
) {
    val colorScheme = MaterialTheme.colorScheme
    val telemetry = telemetryProvider()

    val isBassActive = telemetry.bassDepth > 0.05f &&
        !hapticsMuted &&
        masterDrive > 0.02f
    val basePitchFactor = (telemetry.dominantLowFreqHz - 30f).coerceIn(0f, 60f) * 0.28f
    val liveCadenceHz = ((20f + basePitchFactor) * (0.60f + 0.60f * engineCadenceRpm)).coerceIn(18f, 48f)
    val targetCadenceHz = if (engineThrottleEnabled) {
        if (isBassActive) liveCadenceHz else (20f * (0.60f + 0.60f * engineCadenceRpm)).coerceIn(18f, 48f)
    } else {
        0f
    }

    val animatedCadenceHz by animateFloatAsState(
        targetValue = targetCadenceHz,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
        label = "cadence_hz_anim"
    )
    val displayCadenceHz = animatedCadenceHz.roundToInt()
    val displayRpm = (animatedCadenceHz * 60f).roundToInt()

    val cadenceBadgeText = if (!engineThrottleEnabled) {
        "OFF (CONTINUOUS)"
    } else if (isBassActive) {
        "$displayCadenceHz Hz • $displayRpm RPM"
    } else {
        "$displayCadenceHz Hz • $displayRpm RPM (IDLE)"
    }

    val cadenceBadgeTextColor = if (!engineThrottleEnabled) {
        colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    } else if (isBassActive) {
        signalColors.liveGreen
    } else {
        signalColors.actuator
    }

    val cadenceBadgeBorderColor = if (!engineThrottleEnabled) {
        colorScheme.outlineVariant.copy(alpha = 0.6f)
    } else if (isBassActive) {
        signalColors.liveGreen.copy(alpha = 0.7f)
    } else {
        signalColors.actuator.copy(alpha = 0.7f)
    }

    val cadenceBadgeContainerColor = if (!engineThrottleEnabled) {
        colorScheme.background
    } else if (isBassActive) {
        signalColors.liveGreenContainer
    } else {
        signalColors.actuatorContainer
    }

    val cadenceBadgeDotColor = if (!engineThrottleEnabled) {
        colorScheme.outlineVariant
    } else if (isBassActive) {
        signalColors.liveGreen
    } else {
        signalColors.actuator
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(cadenceBadgeContainerColor)
            .border(1.dp, cadenceBadgeBorderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("badge_engine_cadence_rpm"),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(cadenceBadgeDotColor)
            )
            Text(
                text = cadenceBadgeText,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = cadenceBadgeTextColor
            )
        }
    }
}

@Composable
private fun MasterNodeSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: Color,
    testTag: String,
    onValueChange: (Float) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = accent
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = colorScheme.background
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag)
        )
    }
}

@Composable
private fun MasterNodeToggleRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    accent: Color,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colorScheme.background,
                checkedTrackColor = accent
            ),
            modifier = Modifier.testTag(testTag)
        )
    }
}

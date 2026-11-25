package com.example.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.example.model.AcousticFrameTelemetry
import com.example.model.ActuatorType
import com.example.model.HapticHardwareCapabilities
import com.example.model.TuningConfig
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Multi-tier 2026 Android Haptic Subwoofer Actuator Engine.
 *
 * Tier 1 (Android 16 / API 36 BAKLAVA):
 *   - Piecewise Linear Envelope (PWLE) synthesis via VibrationEffect.BasicEnvelopeBuilder
 *     (perceptual dB SL curve + active braking to 0f intensity/sharpness) and guarded
 *     VibrationEffect.WaveformEnvelopeBuilder (when VibratorFrequencyProfile reports valid
 *     bounds and positive acceleration), strictly honoring >= 20ms minControlPointDurationMillis.
 *
 * Tier 2 (Android 12–15 / API 31–35):
 *   - Hardware-calibrated LRA Composition Primitives (PRIMITIVE_THUD / PRIMITIVE_LOW_TICK)
 *     timed via Vibrator.getPrimitiveDurations(...) for both Kick punch and pitch-timed Bass pulses.
 *   - Tagged with VibrationAttributes.USAGE_MEDIA (API 33+).
 *
 * Tier 3 (Android 8–11 / API 26–30):
 *   - 3-stage raised-amplitude createWaveform bass pulses (zero square-edge clicks) and
 *     amplitude-scaled createOneShot kick pulses with real silence lockouts.
 *
 * Includes Master PWM Threshold Node, Pitch-Timed + BPM-Matched Pulse & Real Silence Scheduler,
 * Kick/Bass channel isolation, Kick-over-Bass Sidechain Ducking, and Bluetooth Latency Sync (0–250ms).
 */
class SubwooferHapticEngine private constructor(
    private val vibrator: Vibrator?,
    testCapabilities: HapticHardwareCapabilities?,
    private val testDispatcher: ((String, Long) -> Unit)?
) {

    constructor(context: Context) : this(
        vibrator = resolveVibrator(context),
        testCapabilities = null,
        testDispatcher = null
    )

    internal constructor(
        capabilities: HapticHardwareCapabilities,
        onDispatch: (kind: String, durationMs: Long) -> Unit
    ) : this(
        vibrator = null,
        testCapabilities = capabilities,
        testDispatcher = onDispatch
    )

    companion object {
        private const val TAG = "SubwooferHapticEngine"
        private const val BASS_FRAME_INTERVAL_MS = 34L
        private const val ERM_REFRACTORY_MS = 120L
        internal const val MIN_PWM_SILENCE_MS = 45L
        private const val MIN_PWLE_CONTROL_POINT_MS = 20L

        private fun resolveVibrator(context: Context): Vibrator? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                mgr?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }
    }

    private val delayScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    @Volatile
    private var pwleMinControlPointMs: Long = MIN_PWLE_CONTROL_POINT_MS

    val capabilities: HapticHardwareCapabilities = testCapabilities ?: probeHardwareCapabilities()

    @Volatile
    private var lastBassDispatchMs = 0L

    @Volatile
    private var lastCadencePacketDispatchMs = 0L

    @Volatile
    private var prevBassDepthTracker = 0f

    @Volatile
    private var throttleSurgeUntilMs = 0L

    @Volatile
    private var lastErmKickMs = 0L

    @Volatile
    private var sidechainUntilMs = 0L

    @Volatile
    private var nextBassPulseAllowedMs = 0L

    @Volatile
    private var activeBassPulseUntilMs = 0L

    @Volatile
    private var lastPwmSilenceGapMs = 0

    @Volatile
    private var lastPwmBpmLocked = false

    @Volatile
    private var currentDriveLevel = 0f

    @Volatile
    private var currentMotorFreqHz = 0f

    data class HapticOutputState(
        val driveLevel: Float,
        val motorFreqHz: Float,
        val isSidechainDucking: Boolean,
        val pwmSilenceGapMs: Int = 0,
        val isPwmPulseActive: Boolean = false,
        val isPwmBpmLocked: Boolean = false
    )

    internal data class PwmPulseTiming(
        val pulseDurationMs: Long,
        val silenceGapMs: Long,
        val isBpmLocked: Boolean
    )

    private data class PreparedPwmPulse(
        val dispatchedDurationMs: Long,
        val dispatch: () -> Unit
    )

    private fun probeHardwareCapabilities(): HapticHardwareCapabilities {
        val v = vibrator ?: return HapticHardwareCapabilities(
            hasVibrator = false,
            activeEngineTier = "No Actuator Detected",
            actuatorType = ActuatorType.BUDGET_LEGACY_ERM
        )
        if (!v.hasVibrator()) {
            return HapticHardwareCapabilities(
                hasVibrator = false,
                activeEngineTier = "No Actuator Detected",
                actuatorType = ActuatorType.BUDGET_LEGACY_ERM
            )
        }

        val hasAmp = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.hasAmplitudeControl()

        var resonantHz: Float? = null
        var qFactor: Float? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val rf = v.resonantFrequency
                if (!rf.isNaN() && rf > 0f) resonantHz = rf
                val qf = v.qFactor
                if (!qf.isNaN() && qf > 0f) qFactor = qf
            } catch (t: Throwable) {
                Log.w(TAG, "Resonant frequency query fallback: ${t.message}")
            }
        }

        var thudSupported = false
        var lowTickSupported = false
        var thudDurationMs = 0
        var lowTickDurationMs = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val support = v.arePrimitivesSupported(
                    VibrationEffect.Composition.PRIMITIVE_THUD,
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK
                )
                thudSupported = support.getOrNull(0) == true
                lowTickSupported = support.getOrNull(1) == true

                val durations = v.getPrimitiveDurations(
                    VibrationEffect.Composition.PRIMITIVE_THUD,
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK
                )
                if (thudSupported) {
                    thudDurationMs = durations.getOrNull(0)?.coerceAtLeast(0) ?: 0
                }
                if (lowTickSupported) {
                    lowTickDurationMs = durations.getOrNull(1)?.coerceAtLeast(0) ?: 0
                }
            } catch (t: Throwable) {
                Log.w(TAG, "API 31 primitive capability query fallback: ${t.message}")
            }
        }

        var pwleSupported = false
        var minFreq: Float? = null
        var maxFreq: Float? = null
        var maxPoints = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            try {
                pwleSupported = v.areEnvelopeEffectsSupported()
                if (pwleSupported) {
                    val info = v.envelopeEffectInfo
                    maxPoints = info.maxSize
                    pwleMinControlPointMs = max(MIN_PWLE_CONTROL_POINT_MS, info.minControlPointDurationMillis)
                    val profile = v.frequencyProfile
                    if (profile != null) {
                        val lo = profile.minFrequencyHz
                        val hi = profile.maxFrequencyHz
                        if (!lo.isNaN() && !hi.isNaN() && hi > lo && profile.maxOutputAccelerationGs > 0f) {
                            minFreq = lo
                            maxFreq = hi
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "API 36 envelope profile query fallback: ${t.message}")
            }
        }

        val actuatorType = when {
            pwleSupported -> ActuatorType.MODERN_LRA_PWLE
            thudSupported -> ActuatorType.MODERN_LRA_PRIMITIVES
            hasAmp -> ActuatorType.MODERN_LRA_AMPLITUDE
            else -> ActuatorType.BUDGET_LEGACY_ERM
        }

        val tier = when {
            pwleSupported && minFreq != null -> "Android 16 PWLE Frequency Envelope"
            pwleSupported -> "Android 16 Basic PWLE Envelope"
            thudSupported -> "LRA Rich Composition Primitives"
            hasAmp -> "HD Amplitude Modulation"
            else -> "Standard Pulse Fallback"
        }

        return HapticHardwareCapabilities(
            hasVibrator = true,
            hasAmplitudeControl = hasAmp,
            resonantFrequencyHz = resonantHz,
            qFactor = qFactor,
            supportsThudPrimitive = thudSupported,
            supportsLowTickPrimitive = lowTickSupported,
            thudDurationMs = thudDurationMs,
            lowTickDurationMs = lowTickDurationMs,
            supportsPwleEnvelopes = pwleSupported,
            minFrequencyHz = minFreq,
            maxFrequencyHz = maxFreq,
            maxEnvelopePoints = maxPoints,
            activeEngineTier = tier,
            actuatorType = actuatorType
        )
    }

    /**
     * Computes pitch-timed pulse duration, pitch-scaled silence gap (>= 45ms floor),
     * and optional musical BPM subdivision snapping (45..220 BPM).
     */
    internal fun computePwmBassPulseTiming(
        audioFreqHz: Float,
        estimatedBpm: Int,
        config: TuningConfig,
        hardware: HapticHardwareCapabilities,
        minPwlePointMs: Long = pwleMinControlPointMs
    ): PwmPulseTiming {
        val freqNorm = ((audioFreqHz.coerceIn(28f, 130f) - 28f) / 102f).coerceIn(0f, 1f)
        val pitchPulseMs = (36f - freqNorm * 14f).roundToInt().toLong().coerceIn(22L, 36L)

        val hardwarePulseFloorMs = when {
            hardware.supportsPwleEnvelopes -> max(MIN_PWLE_CONTROL_POINT_MS, minPwlePointMs) * 2L
            hardware.supportsThudPrimitive && audioFreqHz <= 78f && hardware.thudDurationMs > 0 ->
                hardware.thudDurationMs.toLong()
            hardware.supportsLowTickPrimitive && audioFreqHz > 78f && hardware.lowTickDurationMs > 0 ->
                hardware.lowTickDurationMs.toLong()
            hardware.supportsThudPrimitive && hardware.thudDurationMs > 0 ->
                hardware.thudDurationMs.toLong()
            else -> 0L
        }
        val pulseDurationMs = max(pitchPulseMs, hardwarePulseFloorMs)

        val baseGapMs = config.pwmPulseGapMs.coerceIn(45f, 320f)
        // Deeper notes get wider gaps (1.25x at 28Hz) so LRA settles cleanly; upper bass tightens (0.72x at 130Hz)
        val pitchGapScale = 1.25f - freqNorm * 0.53f
        val rawGapMs = (baseGapMs * pitchGapScale).roundToInt().toLong().coerceAtLeast(MIN_PWM_SILENCE_MS)

        if (estimatedBpm !in 45..220) {
            return PwmPulseTiming(
                pulseDurationMs = pulseDurationMs,
                silenceGapMs = rawGapMs,
                isBpmLocked = false
            )
        }

        val beatMs = 60_000f / estimatedBpm.toFloat()
        val subdivisions = floatArrayOf(1.5f, 1.0f, 0.5f, 1f / 3f, 0.25f, 1f / 6f, 0.125f)
        val targetPeriodMs = (pulseDurationMs + rawGapMs).toFloat()

        var bestPeriodMs: Long? = null
        var bestDist = Float.MAX_VALUE
        for (mult in subdivisions) {
            val candidatePeriodMs = (beatMs * mult).roundToInt().toLong()
            val candidateGapMs = candidatePeriodMs - pulseDurationMs
            if (candidateGapMs >= MIN_PWM_SILENCE_MS) {
                val dist = abs(candidatePeriodMs - targetPeriodMs)
                if (dist < bestDist) {
                    bestDist = dist
                    bestPeriodMs = candidatePeriodMs
                }
            }
        }

        return if (bestPeriodMs != null) {
            PwmPulseTiming(
                pulseDurationMs = pulseDurationMs,
                silenceGapMs = (bestPeriodMs - pulseDurationMs).coerceAtLeast(MIN_PWM_SILENCE_MS),
                isBpmLocked = true
            )
        } else {
            PwmPulseTiming(
                pulseDurationMs = pulseDurationMs,
                silenceGapMs = rawGapMs,
                isBpmLocked = false
            )
        }
    }

    /**
     * Evaluates the latest neural classification frame and dispatches synchronized Kick or Sub-Bass
     * haptic envelopes with Master PWM Gate, Kick/Bass isolation, sidechain ducking, and delay sync.
     */
    fun processTelemetry(
        telemetry: AcousticFrameTelemetry,
        config: TuningConfig,
        nowMs: Long
    ): HapticOutputState {
        // Acoustic Noise Gate: Physical low-end MIR energy must breach master threshold
        val acousticLevel = max(telemetry.features.subRms, telemetry.features.kickBody)
        val gateLevel = config.masterThreshold * 0.40f
        if (config.hapticsMuted || !capabilities.hasVibrator || config.masterDrive <= 0.02f) {
            currentDriveLevel *= 0.75f
            activeBassPulseUntilMs = 0L
            nextBassPulseAllowedMs = 0L
            lastPwmSilenceGapMs = 0
            lastPwmBpmLocked = false
            sidechainUntilMs = 0L
            return HapticOutputState(
                driveLevel = currentDriveLevel,
                motorFreqHz = currentMotorFreqHz,
                isSidechainDucking = false,
                pwmSilenceGapMs = 0,
                isPwmPulseActive = false,
                isPwmBpmLocked = false
            )
        }

        if (acousticLevel < gateLevel) {
            currentDriveLevel *= 0.75f
            activeBassPulseUntilMs = 0L
            lastPwmSilenceGapMs = 0
            lastPwmBpmLocked = false
            return HapticOutputState(
                driveLevel = currentDriveLevel,
                motorFreqHz = currentMotorFreqHz,
                isSidechainDucking = false,
                pwmSilenceGapMs = 0,
                isPwmPulseActive = false,
                isPwmBpmLocked = false
            )
        }

        val delayMs = config.latencyOffsetMs.coerceIn(0, 250).toLong()
        val effectiveType = if (config.forceErmMode) ActuatorType.BUDGET_LEGACY_ERM else capabilities.actuatorType

        // BUDGET / LEGACY ERM (or Manual Override): Single-shot stiction-breaking burst mode
        if (effectiveType == ActuatorType.BUDGET_LEGACY_ERM) {
            if (!config.kickEnabled) {
                sidechainUntilMs = 0L
            } else if (telemetry.kickTriggered && (nowMs - lastErmKickMs >= ERM_REFRACTORY_MS) && config.kickGain > 0.04f) {
                lastErmKickMs = nowMs
                currentDriveLevel = 1.0f
                dispatchWithDelay(delayMs) {
                    if (testDispatcher != null) {
                        testDispatcher.invoke("erm_kick", 28L)
                    } else {
                        val v = vibrator ?: return@dispatchWithDelay
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrateSafe(v, VibrationEffect.createOneShot(28L, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            v.vibrate(28L)
                        }
                    }
                }
                return HapticOutputState(
                    driveLevel = 1.0f,
                    motorFreqHz = capabilities.resonantFrequencyHz ?: 160f,
                    isSidechainDucking = true,
                    pwmSilenceGapMs = 0,
                    isPwmPulseActive = false,
                    isPwmBpmLocked = false
                )
            }
            // Suppress continuous sub-bass rumble on ERM to eliminate noisy PWM micro-pulse clatter
            currentDriveLevel *= 0.80f
            return HapticOutputState(
                driveLevel = currentDriveLevel.coerceIn(0f, 1f),
                motorFreqHz = capabilities.resonantFrequencyHz ?: 160f,
                isSidechainDucking = false,
                pwmSilenceGapMs = 0,
                isPwmPulseActive = false,
                isPwmBpmLocked = false
            )
        }

        // MODERN LRA / VOICE COIL MODES (PWLE, Rich Primitives, HD Amplitude Modulation)
        // 1. KICK TRANSIENT PRIORITY — crisp high-impact punch with sidechain bass ducking
        if (!config.kickEnabled) {
            sidechainUntilMs = 0L
        } else if (telemetry.kickTriggered && config.kickGain > 0.04f) {
            val kickIntensity = (telemetry.kickImpactStrength * config.kickGain * config.masterDrive)
                .coerceIn(0.12f, 1.0f)

            // Sidechain ducks continuous sub-bass during kick punch so kick feels razor sharp
            val duckDurationMs = (36L + (config.sidechainDucking * 44f).toLong())
            sidechainUntilMs = nowMs + duckDurationMs
            // Push next PWM bass pulse past the kick sidechain window
            nextBassPulseAllowedMs = max(nextBassPulseAllowedMs, sidechainUntilMs)
            activeBassPulseUntilMs = 0L

            val targetMotorFreq = computeKickMotorFrequency(config.kickTightness)
            currentDriveLevel = kickIntensity
            currentMotorFreqHz = targetMotorFreq

            dispatchWithDelay(delayMs) {
                fireKickEffect(kickIntensity, config.kickTightness)
            }
            return HapticOutputState(
                driveLevel = currentDriveLevel,
                motorFreqHz = currentMotorFreqHz,
                isSidechainDucking = true,
                pwmSilenceGapMs = if (config.pwmEnabled && config.bassEnabled) lastPwmSilenceGapMs else 0,
                isPwmPulseActive = false,
                isPwmBpmLocked = if (config.pwmEnabled && config.bassEnabled) lastPwmBpmLocked else false
            )
        }

        val isDucking = config.kickEnabled && nowMs < sidechainUntilMs

        // 2. BASS CHANNEL TURN-OFF CHECK
        if (!config.bassEnabled) {
            activeBassPulseUntilMs = 0L
            nextBassPulseAllowedMs = 0L
            lastPwmSilenceGapMs = 0
            lastPwmBpmLocked = false
            prevBassDepthTracker = 0f
            currentDriveLevel *= 0.78f
            return HapticOutputState(
                driveLevel = currentDriveLevel.coerceIn(0f, 1f),
                motorFreqHz = currentMotorFreqHz,
                isSidechainDucking = isDucking,
                pwmSilenceGapMs = 0,
                isPwmPulseActive = false,
                isPwmBpmLocked = false
            )
        }

        // 3. SUSTAINED SUB-BASS: 2026 PWM PULSE ENGINE vs. LEGACY MODES
        val duckMultiplier = if (isDucking) (1f - config.sidechainDucking * 0.85f) else 1f
        val effectiveBass = (telemetry.bassDepth * config.bassGain * config.masterDrive * duckMultiplier)
            .coerceIn(0f, 1f)

        if (config.pwmEnabled) {
            prevBassDepthTracker = effectiveBass
            if (effectiveBass > 0.09f && !isDucking) {
                if (nowMs >= nextBassPulseAllowedMs) {
                    val timing = computePwmBassPulseTiming(
                        audioFreqHz = telemetry.dominantLowFreqHz,
                        estimatedBpm = telemetry.estimatedBpm,
                        config = config,
                        hardware = capabilities,
                        minPwlePointMs = pwleMinControlPointMs
                    )
                    val prepared = preparePwmBassPulse(
                        intensity = effectiveBass,
                        audioFreqHz = telemetry.dominantLowFreqHz,
                        pulseDurationMs = timing.pulseDurationMs,
                        config = config
                    )
                    val dispatchedDurationMs = prepared.dispatchedDurationMs
                    val motorFreq = mapAudioSubToMotorHz(telemetry.dominantLowFreqHz)
                    currentDriveLevel = effectiveBass
                    currentMotorFreqHz = motorFreq
                    activeBassPulseUntilMs = nowMs + dispatchedDurationMs
                    nextBassPulseAllowedMs = nowMs + dispatchedDurationMs + timing.silenceGapMs
                    lastPwmSilenceGapMs = timing.silenceGapMs.toInt()
                    lastPwmBpmLocked = timing.isBpmLocked

                    dispatchWithDelay(delayMs, prepared.dispatch)
                } else {
                    // Inside lockout: either active pulse ring or true zero-vibration silence gap
                    val inActivePulse = nowMs < activeBassPulseUntilMs
                    if (inActivePulse) {
                        currentDriveLevel = max(currentDriveLevel * 0.92f, effectiveBass * 0.78f)
                    } else {
                        // Decay drive rapidly in real silence gap so VU meter reflects distinct pulses
                        currentDriveLevel *= 0.60f
                    }
                }
            } else {
                val inActivePulse = nowMs < activeBassPulseUntilMs
                if (inActivePulse) {
                    currentDriveLevel = max(currentDriveLevel * 0.85f, effectiveBass * 0.65f)
                } else {
                    currentDriveLevel *= 0.75f
                    if (effectiveBass <= 0.09f) {
                        lastPwmSilenceGapMs = 0
                        lastPwmBpmLocked = false
                    }
                }
            }

            val isPulseActive = nowMs < activeBassPulseUntilMs
            return HapticOutputState(
                driveLevel = currentDriveLevel.coerceIn(0f, 1f),
                motorFreqHz = currentMotorFreqHz,
                isSidechainDucking = isDucking,
                pwmSilenceGapMs = lastPwmSilenceGapMs,
                isPwmPulseActive = isPulseActive,
                isPwmBpmLocked = lastPwmBpmLocked
            )
        }

        // LEGACY MODES (pwmEnabled = false): preserved 100% intact
        activeBassPulseUntilMs = 0L
        nextBassPulseAllowedMs = 0L
        lastPwmSilenceGapMs = 0
        lastPwmBpmLocked = false

        if (config.engineThrottleEnabled) {
            // Engine Throttle Cadence Mode: virtual RPM, throttle surge acceleration, 2-stroke compound cadence packets
            val bassDelta = effectiveBass - prevBassDepthTracker
            prevBassDepthTracker = effectiveBass
            if (bassDelta > 0.08f) {
                throttleSurgeUntilMs = nowMs + 140L
            }

            if (effectiveBass > 0.09f && !isDucking) {
                val isSurging = nowMs < throttleSurgeUntilMs
                val surgeBoost = if (isSurging) 10f else 0f
                val baseHz = 20f + (telemetry.dominantLowFreqHz - 30f).coerceIn(0f, 60f) * 0.28f
                val cadenceHz = (baseHz * (0.60f + 0.60f * config.engineCadenceRpm) + surgeBoost).coerceIn(18f, 48f)
                val strokePeriodMs = (1000f / cadenceHz).roundToInt().coerceIn(22, 55)
                val packetDurationMs = strokePeriodMs * 2L

                if (nowMs - lastCadencePacketDispatchMs >= packetDurationMs - 4L) {
                    lastCadencePacketDispatchMs = nowMs
                    val motorFreq = mapAudioSubToMotorHz(telemetry.dominantLowFreqHz)
                    currentDriveLevel = effectiveBass
                    currentMotorFreqHz = motorFreq

                    dispatchWithDelay(delayMs) {
                        fireEngineCadencePacket(effectiveBass, telemetry.dominantLowFreqHz, strokePeriodMs)
                    }
                }
            } else {
                currentDriveLevel = max(effectiveBass * 0.85f, currentDriveLevel * 0.86f)
            }
        } else {
            // Legacy Continuous Frame Overlap Mode
            prevBassDepthTracker = effectiveBass
            if (effectiveBass > 0.09f && (nowMs - lastBassDispatchMs >= BASS_FRAME_INTERVAL_MS) && !isDucking) {
                lastBassDispatchMs = nowMs
                val motorFreq = mapAudioSubToMotorHz(telemetry.dominantLowFreqHz)
                currentDriveLevel = effectiveBass
                currentMotorFreqHz = motorFreq

                dispatchWithDelay(delayMs) {
                    fireBassFrameEffect(effectiveBass, telemetry.dominantLowFreqHz, config.bassSmoothness)
                }
            } else {
                currentDriveLevel = max(effectiveBass * 0.85f, currentDriveLevel * 0.86f)
            }
        }

        return HapticOutputState(
            driveLevel = currentDriveLevel.coerceIn(0f, 1f),
            motorFreqHz = currentMotorFreqHz,
            isSidechainDucking = isDucking,
            pwmSilenceGapMs = 0,
            isPwmPulseActive = false,
            isPwmBpmLocked = false
        )
    }

    /** Direct calibration impulse: Master gate pulse test (scaled by masterDrive, ERM burst or LRA kick) */
    fun playMasterPulseCalibrationTest(config: TuningConfig) {
        if (!capabilities.hasVibrator) return
        val effectiveType = if (config.forceErmMode) ActuatorType.BUDGET_LEGACY_ERM else capabilities.actuatorType
        if (effectiveType == ActuatorType.BUDGET_LEGACY_ERM) {
            val v = vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrateSafe(v, VibrationEffect.createOneShot(28L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(28L)
            }
        } else {
            val strength = (config.masterDrive * 0.95f).coerceIn(0.20f, 1.0f)
            fireKickEffect(strength, config.kickTightness)
        }
    }

    /** Direct calibration impulse: Kick Drum downward pitch-chirp */
    fun playKickCalibrationTest(config: TuningConfig) {
        if (!capabilities.hasVibrator) return
        val strength = (0.92f * config.masterDrive.coerceAtLeast(0.5f)).coerceIn(0.3f, 1f)
        fireKickEffect(strength, config.kickTightness)
    }

    /** Direct calibration impulse: Warm 48Hz Sub-Bass sustain swell */
    fun playBassCalibrationTest(config: TuningConfig) {
        if (!capabilities.hasVibrator) return
        val strength = (0.82f * config.masterDrive.coerceAtLeast(0.5f)).coerceIn(0.3f, 1f)
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && capabilities.supportsPwleEnvelopes) {
            try {
                val effect = buildBassCalibrationEnvelope(v, strength)
                vibrateSafe(v, effect)
                return
            } catch (_: Throwable) {
                // Fall through to legacy waveform
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amp = (strength * 210f).roundToInt().coerceIn(40, 235)
            val timings = longArrayOf(40, 260, 120)
            val amps = intArrayOf((amp * 0.55f).roundToInt(), amp, (amp * 0.35f).roundToInt())
            val effect = if (capabilities.hasAmplitudeControl) {
                VibrationEffect.createWaveform(timings, amps, -1)
            } else {
                VibrationEffect.createOneShot(320L, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            vibrateSafe(v, effect)
        }
    }

    /** Direct calibration impulse: Kick punch + sidechained 808 sub-bass swell */
    fun playSidechainComboTest(config: TuningConfig) {
        if (!capabilities.hasVibrator) return
        playKickCalibrationTest(config)
        delayScheduler.schedule({
            playBassCalibrationTest(config)
        }, 95L, TimeUnit.MILLISECONDS)
    }

    fun stopAll() {
        currentDriveLevel = 0f
        currentMotorFreqHz = 0f
        lastErmKickMs = 0L
        lastBassDispatchMs = 0L
        lastCadencePacketDispatchMs = 0L
        prevBassDepthTracker = 0f
        throttleSurgeUntilMs = 0L
        sidechainUntilMs = 0L
        nextBassPulseAllowedMs = 0L
        activeBassPulseUntilMs = 0L
        lastPwmSilenceGapMs = 0
        lastPwmBpmLocked = false
        try {
            vibrator?.cancel()
        } catch (_: Throwable) {
        }
    }

    fun release() {
        stopAll()
        delayScheduler.shutdownNow()
    }

    private fun dispatchWithDelay(delayMs: Long, action: () -> Unit) {
        if (delayMs <= 2L) {
            action()
        } else {
            try {
                delayScheduler.schedule(action, delayMs, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
            }
        }
    }

    internal fun firePwmBassPulseEffect(
        intensity: Float,
        audioFreqHz: Float,
        pulseDurationMs: Long,
        config: TuningConfig
    ): Long {
        val prepared = preparePwmBassPulse(intensity, audioFreqHz, pulseDurationMs, config)
        prepared.dispatch()
        return prepared.dispatchedDurationMs
    }

    private fun preparePwmBassPulse(
        intensity: Float,
        audioFreqHz: Float,
        pulseDurationMs: Long,
        config: TuningConfig
    ): PreparedPwmPulse {
        val shaped = intensity.pow(1.30f).coerceIn(0.08f, 0.94f)

        // Pure JVM unit test hook: mirror exact tier duration computation without calling android.jar stubs
        if (testDispatcher != null) {
            val expectedDurationMs = when {
                capabilities.supportsPwleEnvelopes -> {
                    val minPt = max(MIN_PWLE_CONTROL_POINT_MS, pwleMinControlPointMs)
                    val attackMs = max(minPt, (pulseDurationMs * 0.55f).roundToInt().toLong())
                    val brakeMs = max(minPt, pulseDurationMs - attackMs)
                    attackMs + brakeMs
                }
                capabilities.supportsThudPrimitive && (audioFreqHz <= 78f || !capabilities.supportsLowTickPrimitive) ->
                    max(pulseDurationMs, capabilities.thudDurationMs.toLong())
                capabilities.supportsLowTickPrimitive ->
                    max(pulseDurationMs, capabilities.lowTickDurationMs.toLong())
                else -> {
                    val attackMs = max(6L, (pulseDurationMs * 0.24f).roundToInt().toLong())
                    val releaseMs = max(7L, (pulseDurationMs * (0.28f + config.bassSmoothness * 0.12f)).roundToInt().toLong())
                    val bodyMs = max(8L, pulseDurationMs - attackMs - releaseMs)
                    attackMs + bodyMs + releaseMs
                }
            }
            return PreparedPwmPulse(expectedDurationMs) {
                testDispatcher.invoke("pwm_bass", expectedDurationMs)
            }
        }

        val v = vibrator ?: return PreparedPwmPulse(pulseDurationMs) {}

        // Tier 1: Android 16 (API 36 BAKLAVA) — Primary BasicEnvelopeBuilder (perceptual dB SL + active brake)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && capabilities.supportsPwleEnvelopes) {
            try {
                val minPointMs = max(MIN_PWLE_CONTROL_POINT_MS, v.envelopeEffectInfo.minControlPointDurationMillis)
                val freqNorm = ((audioFreqHz.coerceIn(25f, 130f) - 25f) / 105f).coerceIn(0f, 1f)
                val targetSharpness = (0.06f + freqNorm * 0.34f).coerceIn(0.06f, 0.40f)
                val attackMs = max(minPointMs, (pulseDurationMs * 0.55f).roundToInt().toLong())
                val brakeMs = max(minPointMs, pulseDurationMs - attackMs)
                val totalMs = attackMs + brakeMs

                val effect = try {
                    VibrationEffect.BasicEnvelopeBuilder()
                        .setInitialSharpness((targetSharpness * 0.75f).coerceIn(0.06f, 0.40f))
                        .addControlPoint(shaped, targetSharpness, attackMs)
                        .addControlPoint(0f, 0f, brakeMs)
                        .build()
                } catch (basicErr: Throwable) {
                    // Optional safe WaveformEnvelopeBuilder fallback only when FOAM bounds & acceleration are valid
                    val profile = v.frequencyProfile
                    if (profile != null &&
                        capabilities.minFrequencyHz != null &&
                        capabilities.maxFrequencyHz != null &&
                        profile.maxOutputAccelerationGs > 0.05f
                    ) {
                        val targetHz = mapAudioSubToMotorHz(audioFreqHz)
                            .coerceIn(capabilities.minFrequencyHz, capabilities.maxFrequencyHz)
                        VibrationEffect.WaveformEnvelopeBuilder()
                            .addControlPoint(shaped, targetHz, attackMs)
                            .addControlPoint(0f, targetHz, brakeMs)
                            .build()
                    } else {
                        throw basicErr
                    }
                }
                return PreparedPwmPulse(totalMs) { vibrateSafe(v, effect) }
            } catch (t: Throwable) {
                Log.w(TAG, "API 36 PWM envelope fallback: ${t.message}")
            }
        }

        // Tier 2: Android 12–15 (API 31–35) — Hardware-calibrated Composition Primitives
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (capabilities.supportsThudPrimitive || capabilities.supportsLowTickPrimitive)
        ) {
            val useThud = capabilities.supportsThudPrimitive &&
                (audioFreqHz <= 78f || !capabilities.supportsLowTickPrimitive)
            val primitiveId = if (useThud) {
                VibrationEffect.Composition.PRIMITIVE_THUD
            } else {
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK
            }
            val scale = if (useThud) {
                (shaped * 0.95f).coerceIn(0.18f, 1.0f)
            } else {
                (shaped * 0.88f).coerceIn(0.18f, 1.0f)
            }
            val queriedDurationMs = if (useThud) {
                capabilities.thudDurationMs.toLong()
            } else {
                capabilities.lowTickDurationMs.toLong()
            }
            val dispatchedDurationMs = max(pulseDurationMs, queriedDurationMs)
            val effect = VibrationEffect.startComposition()
                .addPrimitive(primitiveId, scale)
                .compose()
            return PreparedPwmPulse(dispatchedDurationMs) { vibrateSafe(v, effect) }
        }

        // Tier 3: API 26–30 (or LRAs without THUD) — 3-stage raised-amplitude createWaveform
        val attackMs = max(6L, (pulseDurationMs * 0.24f).roundToInt().toLong())
        val releaseMs = max(7L, (pulseDurationMs * (0.28f + config.bassSmoothness * 0.12f)).roundToInt().toLong())
        val bodyMs = max(8L, pulseDurationMs - attackMs - releaseMs)
        val totalMs = attackMs + bodyMs + releaseMs

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = if (capabilities.hasAmplitudeControl) {
                val peakAmp = (shaped * 232f).roundToInt().coerceIn(28, 240)
                val biteFactor = if (config.engineThrottleEnabled) {
                    0.72f
                } else {
                    (0.55f - config.bassSmoothness * 0.15f).coerceIn(0.35f, 0.70f)
                }
                val attackAmp = (peakAmp * biteFactor).roundToInt().coerceIn(16, peakAmp)
                val tailAmp = (peakAmp * (0.26f + config.bassSmoothness * 0.16f)).roundToInt().coerceIn(12, peakAmp)
                VibrationEffect.createWaveform(
                    longArrayOf(attackMs, bodyMs, releaseMs),
                    intArrayOf(attackAmp, peakAmp, tailAmp),
                    -1
                )
            } else {
                VibrationEffect.createOneShot(totalMs, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            return PreparedPwmPulse(totalMs) { vibrateSafe(v, effect) }
        }

        return PreparedPwmPulse(totalMs) {
            @Suppress("DEPRECATION")
            v.vibrate(totalMs)
        }
    }

    private fun fireKickEffect(intensity: Float, tightness: Float) {
        if (testDispatcher != null) {
            val durationMs = (38L - (tightness * 16f).toLong()).coerceIn(18L, 42L)
            testDispatcher.invoke("kick", durationMs)
            return
        }
        val v = vibrator ?: return

        // Tier 1: Android 16 (API 36) PWLE Kick Downward Pitch-Chirp Envelope
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && capabilities.supportsPwleEnvelopes) {
            try {
                val minPointMs = max(MIN_PWLE_CONTROL_POINT_MS, v.envelopeEffectInfo.minControlPointDurationMillis)
                val profile = v.frequencyProfile
                val resonant = capabilities.resonantFrequencyHz
                val effect = if (profile != null &&
                    capabilities.minFrequencyHz != null &&
                    capabilities.maxFrequencyHz != null &&
                    profile.maxOutputAccelerationGs > 0.05f
                ) {
                    val loFreq = capabilities.minFrequencyHz
                    val hiFreq = capabilities.maxFrequencyHz
                    val res = (resonant ?: ((loFreq + hiFreq) * 0.5f)).coerceIn(loFreq, hiFreq)
                    val startFreq = (res * (1.08f + tightness * 0.25f)).coerceIn(loFreq, hiFreq)
                    val endFreq = (res * 0.82f).coerceIn(loFreq, hiFreq)
                    val bodyMs = (28L - (tightness * 14f).toLong()).coerceAtLeast(minPointMs)

                    VibrationEffect.WaveformEnvelopeBuilder()
                        .addControlPoint(intensity, startFreq, minPointMs)
                        .addControlPoint(intensity * 0.82f, endFreq, bodyMs)
                        .addControlPoint(0f, endFreq, minPointMs)
                        .build()
                } else {
                    val sharpStart = (0.65f + tightness * 0.30f).coerceIn(0.4f, 0.98f)
                    val sharpEnd = (0.35f + tightness * 0.15f).coerceIn(0.2f, 0.65f)
                    val decayMs = (36L - (tightness * 16f).toLong()).coerceAtLeast(minPointMs)

                    VibrationEffect.BasicEnvelopeBuilder()
                        .setInitialSharpness(sharpStart)
                        .addControlPoint(intensity, sharpStart, minPointMs)
                        .addControlPoint(0f, sharpEnd, decayMs)
                        .build()
                }
                vibrateSafe(v, effect)
                return
            } catch (t: Throwable) {
                Log.w(TAG, "PWLE kick fallback: ${t.message}")
            }
        }

        // Tier 2: Android 12+ Rich LRA Composition Primitives (THUD + LOW_TICK beater snap)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && capabilities.supportsThudPrimitive) {
            val comp = VibrationEffect.startComposition()
            if (capabilities.supportsLowTickPrimitive && tightness > 0.55f) {
                comp.addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                    (intensity * 0.68f).coerceIn(0.15f, 1f)
                )
            }
            comp.addPrimitive(
                VibrationEffect.Composition.PRIMITIVE_THUD,
                intensity.coerceIn(0.2f, 1f)
            )
            vibrateSafe(v, comp.compose())
            return
        }

        // Tier 3: HD Amplitude OneShot Punch
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val durationMs = (38L - (tightness * 16f).toLong()).coerceIn(18L, 42L)
            val amp = if (capabilities.hasAmplitudeControl) {
                (intensity.pow(1.15f) * 255f).roundToInt().coerceIn(28, 255)
            } else {
                VibrationEffect.DEFAULT_AMPLITUDE
            }
            vibrateSafe(v, VibrationEffect.createOneShot(durationMs, amp))
        }
    }

    @Volatile
    private var lastBassMotorFreqHz = 0f

    private fun fireBassFrameEffect(intensity: Float, audioFreqHz: Float, smoothness: Float) {
        if (testDispatcher != null) {
            val frameDuration = (36L + (smoothness * 16f).toLong()).coerceIn(32L, 54L)
            testDispatcher.invoke("legacy_bass", frameDuration)
            return
        }
        val v = vibrator ?: return
        // Perceptual gamma curve prevents harsh buzzing on quiet bass while preserving deep swells
        val shaped = intensity.pow(1.30f).coerceIn(0.06f, 0.92f)

        // Tier 1: Android 16 (API 36) PWLE Continuous Frequency-Gliding Sub-Bass Envelope
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && capabilities.supportsPwleEnvelopes) {
            try {
                val minPointMs = max(MIN_PWLE_CONTROL_POINT_MS, v.envelopeEffectInfo.minControlPointDurationMillis)
                val sustainMs = max(minPointMs, 26L + (smoothness * 16f).toLong())
                val profile = v.frequencyProfile
                val effect = if (profile != null &&
                    capabilities.minFrequencyHz != null &&
                    capabilities.maxFrequencyHz != null &&
                    profile.maxOutputAccelerationGs > 0.05f
                ) {
                    val targetHz = mapAudioSubToMotorHz(audioFreqHz)
                        .coerceIn(capabilities.minFrequencyHz, capabilities.maxFrequencyHz)
                    val startHz = if (lastBassMotorFreqHz in capabilities.minFrequencyHz..capabilities.maxFrequencyHz) {
                        lastBassMotorFreqHz
                    } else {
                        targetHz
                    }
                    lastBassMotorFreqHz = targetHz

                    // Smooth piecewise-linear frequency glide from previous bass pitch -> current pitch
                    VibrationEffect.WaveformEnvelopeBuilder()
                        .addControlPoint(shaped * 0.88f, startHz, minPointMs)
                        .addControlPoint(shaped, targetHz, sustainMs)
                        .addControlPoint(0f, targetHz, minPointMs)
                        .build()
                } else {
                    // Hardware-agnostic pitch tracking: map 25–130 Hz bass pitch into actuator sharpness
                    val freqNorm = ((audioFreqHz.coerceIn(25f, 130f) - 25f) / 105f).coerceIn(0f, 1f)
                    val targetSharpness = (0.08f + freqNorm * 0.34f + (1f - smoothness) * 0.08f)
                        .coerceIn(0.06f, 0.48f)
                    VibrationEffect.BasicEnvelopeBuilder()
                        .setInitialSharpness(targetSharpness)
                        .addControlPoint(shaped, targetSharpness, minPointMs)
                        .addControlPoint(shaped * 0.55f, targetSharpness, sustainMs)
                        .addControlPoint(0f, targetSharpness, minPointMs)
                        .build()
                }
                vibrateSafe(v, effect)
                return
            } catch (t: Throwable) {
                Log.w(TAG, "PWLE bass fallback: ${t.message}")
            }
        }

        // Tier 2/3: Smooth continuous amplitude-modulated low-latency frame
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val frameDuration = (36L + (smoothness * 16f).toLong()).coerceIn(32L, 54L)
            val amp = if (capabilities.hasAmplitudeControl) {
                (shaped * 225f).roundToInt().coerceIn(18, 228)
            } else {
                VibrationEffect.DEFAULT_AMPLITUDE
            }
            vibrateSafe(v, VibrationEffect.createOneShot(frameDuration, amp))
        }
    }

    private fun fireEngineCadencePacket(intensity: Float, audioFreqHz: Float, strokePeriodMs: Int) {
        if (testDispatcher != null) {
            testDispatcher.invoke("legacy_cadence", strokePeriodMs * 2L)
            return
        }
        val v = vibrator ?: return
        val shaped = intensity.pow(1.25f).coerceIn(0.08f, 0.95f)

        val burstMs = (strokePeriodMs * 0.38f).roundToInt().coerceIn(10, 16)
        val gapMs = (strokePeriodMs - burstMs).coerceAtLeast(10)

        // Tier 1: Android 16 (API 36) PWLE 2-Stroke Compound Cadence Envelope
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && capabilities.supportsPwleEnvelopes) {
            try {
                val minPointMs = max(MIN_PWLE_CONTROL_POINT_MS, v.envelopeEffectInfo.minControlPointDurationMillis)
                val stroke1Burst = max(minPointMs, burstMs.toLong())
                val stroke1Gap = max(minPointMs, gapMs.toLong())
                val stroke2Burst = max(minPointMs, burstMs.toLong())
                val stroke2Gap = max(minPointMs, gapMs.toLong())

                val profile = v.frequencyProfile
                val effect = if (profile != null &&
                    capabilities.minFrequencyHz != null &&
                    capabilities.maxFrequencyHz != null &&
                    profile.maxOutputAccelerationGs > 0.05f
                ) {
                    val targetHz = mapAudioSubToMotorHz(audioFreqHz)
                        .coerceIn(capabilities.minFrequencyHz, capabilities.maxFrequencyHz)

                    // 2-stroke firing packet with active mechanical braking during rest gaps
                    VibrationEffect.WaveformEnvelopeBuilder()
                        .addControlPoint(shaped, targetHz, stroke1Burst)
                        .addControlPoint(0f, targetHz, stroke1Gap)
                        .addControlPoint(shaped * 0.92f, targetHz, stroke2Burst)
                        .addControlPoint(0f, targetHz, stroke2Gap)
                        .build()
                } else {
                    val freqNorm = ((audioFreqHz.coerceIn(25f, 130f) - 25f) / 105f).coerceIn(0f, 1f)
                    val sharpness = (0.12f + freqNorm * 0.30f).coerceIn(0.08f, 0.45f)

                    VibrationEffect.BasicEnvelopeBuilder()
                        .setInitialSharpness(sharpness)
                        .addControlPoint(shaped, sharpness, stroke1Burst)
                        .addControlPoint(0f, sharpness, stroke1Gap)
                        .addControlPoint(shaped * 0.92f, sharpness, stroke2Burst)
                        .addControlPoint(0f, sharpness, stroke2Gap)
                        .build()
                }
                vibrateSafe(v, effect)
                return
            } catch (t: Throwable) {
                Log.w(TAG, "PWLE cadence packet fallback: ${t.message}")
            }
        }

        // Tier 2/3: Android 8–15 Compound 2-Stroke Waveform
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = if (capabilities.hasAmplitudeControl) {
                val amp = (shaped * 235f).roundToInt().coerceIn(24, 245)
                val amp2 = (amp * 0.92f).roundToInt().coerceIn(20, 245)
                val amps = intArrayOf(amp, 0, amp2, 0)
                val timings = longArrayOf(burstMs.toLong(), gapMs.toLong(), burstMs.toLong(), gapMs.toLong())
                VibrationEffect.createWaveform(timings, amps, -1)
            } else {
                val timings = longArrayOf(0L, burstMs.toLong(), gapMs.toLong(), burstMs.toLong())
                VibrationEffect.createWaveform(timings, -1)
            }
            vibrateSafe(v, effect)
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(longArrayOf(0L, burstMs.toLong(), gapMs.toLong(), burstMs.toLong()), -1)
        }
    }

    private fun buildBassCalibrationEnvelope(v: Vibrator, strength: Float): VibrationEffect {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            return VibrationEffect.createOneShot(300L, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        val minPointMs = max(MIN_PWLE_CONTROL_POINT_MS, v.envelopeEffectInfo.minControlPointDurationMillis)
        val profile = v.frequencyProfile
        return if (profile != null &&
            capabilities.minFrequencyHz != null &&
            capabilities.maxFrequencyHz != null &&
            profile.maxOutputAccelerationGs > 0.05f
        ) {
            val lo = capabilities.minFrequencyHz
            val res = (capabilities.resonantFrequencyHz ?: ((lo + capabilities.maxFrequencyHz) * 0.5f))
                .coerceIn(lo, capabilities.maxFrequencyHz)
            val targetLow = (lo + (res - lo) * 0.45f).coerceIn(lo, capabilities.maxFrequencyHz)
            VibrationEffect.WaveformEnvelopeBuilder()
                .addControlPoint(strength * 0.5f, targetLow, max(minPointMs, 45L))
                .addControlPoint(strength, targetLow, max(minPointMs, 220L))
                .addControlPoint(0f, targetLow, max(minPointMs, 90L))
                .build()
        } else {
            VibrationEffect.BasicEnvelopeBuilder()
                .setInitialSharpness(0.16f)
                .addControlPoint(strength, 0.18f, max(minPointMs, 50L))
                .addControlPoint(strength * 0.9f, 0.16f, max(minPointMs, 220L))
                .addControlPoint(0f, 0.12f, max(minPointMs, 90L))
                .build()
        }
    }

    private fun computeKickMotorFrequency(tightness: Float): Float {
        val res = capabilities.resonantFrequencyHz ?: 150f
        return (res * (0.95f + tightness * 0.25f)).coerceIn(80f, 260f)
    }

    private fun mapAudioSubToMotorHz(audioFreqHz: Float): Float {
        val lo = capabilities.minFrequencyHz ?: 65f
        val hi = capabilities.resonantFrequencyHz ?: capabilities.maxFrequencyHz ?: 155f
        val norm = ((audioFreqHz.coerceIn(25f, 130f) - 25f) / 105f).coerceIn(0f, 1f)
        return (lo + norm * max(15f, (hi - lo) * 0.72f))
    }

    private fun vibrateSafe(v: Vibrator, effect: VibrationEffect) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val attrs = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_MEDIA)
                    .build()
                v.vibrate(effect, attrs)
            } else {
                v.vibrate(effect)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Vibrate dispatch skipped: ${t.message}")
        }
    }
}

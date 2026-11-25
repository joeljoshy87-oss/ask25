package com.example

import com.example.dsp.AcousticDspPipeline
import com.example.haptics.SubwooferHapticEngine
import com.example.model.AcousticFrameTelemetry
import com.example.model.ActuatorType
import com.example.model.HapticHardwareCapabilities
import com.example.model.MirFeatureSnapshot
import com.example.model.SubwooferPreset
import com.example.model.TuningConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class PwmHapticEngineTest {

    private fun testLraCapabilities(
        supportsPwle: Boolean = false,
        supportsThud: Boolean = false,
        thudDurationMs: Int = 0
    ) = HapticHardwareCapabilities(
        hasVibrator = true,
        hasAmplitudeControl = true,
        resonantFrequencyHz = 145f,
        supportsThudPrimitive = supportsThud,
        supportsLowTickPrimitive = false,
        thudDurationMs = thudDurationMs,
        supportsPwleEnvelopes = supportsPwle,
        activeEngineTier = "Test LRA",
        actuatorType = when {
            supportsPwle -> ActuatorType.MODERN_LRA_PWLE
            supportsThud -> ActuatorType.MODERN_LRA_PRIMITIVES
            else -> ActuatorType.MODERN_LRA_AMPLITUDE
        }
    )

    private fun bassFrame(
        subRms: Float = 0.65f,
        kickBody: Float = 0.25f,
        bassDepth: Float = 0.78f,
        freqHz: Float = 48f,
        bpm: Int = 0,
        kickTriggered: Boolean = false,
        kickImpactStrength: Float = 0f
    ) = AcousticFrameTelemetry(
        kickTriggered = kickTriggered,
        kickImpactStrength = kickImpactStrength,
        bassDepth = bassDepth,
        dominantLowFreqHz = freqHz,
        estimatedBpm = bpm,
        features = MirFeatureSnapshot(
            subRms = subRms,
            kickBody = kickBody
        )
    )

    @Test
    fun thresholdGate_mutesBelowGateAndOpensAboveGate() {
        val dispatches = mutableListOf<Pair<String, Long>>()
        val engine = SubwooferHapticEngine(testLraCapabilities()) { kind, dur ->
            dispatches += kind to dur
        }
        val config = TuningConfig(masterThreshold = 0.60f, pwmEnabled = true)
        // Gate = 0.60 * 0.40 = 0.24f
        val belowGate = engine.processTelemetry(
            bassFrame(subRms = 0.18f, kickBody = 0.15f, bassDepth = 0.70f),
            config,
            nowMs = 1_000L
        )
        assertEquals(0, dispatches.size)
        assertFalse(belowGate.isPwmPulseActive)
        assertEquals(0, belowGate.pwmSilenceGapMs)

        val aboveGate = engine.processTelemetry(
            bassFrame(subRms = 0.30f, kickBody = 0.20f, bassDepth = 0.70f),
            config,
            nowMs = 1_020L
        )
        assertEquals(1, dispatches.size)
        assertEquals("pwm_bass", dispatches[0].first)
        assertTrue(aboveGate.isPwmPulseActive)
        assertTrue(aboveGate.pwmSilenceGapMs >= SubwooferHapticEngine.MIN_PWM_SILENCE_MS.toInt())
    }

    @Test
    fun computePwmBassPulseTiming_scalesPitchAndEnforcesMinimumSilenceFloor() {
        val engine = SubwooferHapticEngine(testLraCapabilities()) { _, _ -> }
        val hw = testLraCapabilities()
        val config = TuningConfig(pwmPulseGapMs = 135f)

        val deep = engine.computePwmBassPulseTiming(28f, 0, config, hw)
        val high = engine.computePwmBassPulseTiming(130f, 0, config, hw)

        assertTrue("Expected 28Hz pulse in 22..36ms, got ${deep.pulseDurationMs}", deep.pulseDurationMs in 22L..36L)
        assertTrue("Expected 130Hz pulse in 22..36ms, got ${high.pulseDurationMs}", high.pulseDurationMs in 22L..36L)
        assertTrue("Expected 28Hz pulse (${deep.pulseDurationMs}) > 130Hz pulse (${high.pulseDurationMs})", deep.pulseDurationMs > high.pulseDurationMs)
        assertTrue("Expected 28Hz gap (${deep.silenceGapMs}) > 130Hz gap (${high.silenceGapMs})", deep.silenceGapMs > high.silenceGapMs)
        assertFalse(deep.isBpmLocked)

        // Even at minimum slider (45ms) and 130Hz (0.72x -> 32.4ms raw), floor must hold at >= 45ms
        val minConfig = TuningConfig(pwmPulseGapMs = 45f)
        val floored = engine.computePwmBassPulseTiming(130f, 0, minConfig, hw)
        assertTrue("Expected >= 45ms silence floor, got ${floored.silenceGapMs}", floored.silenceGapMs >= 45L)
    }

    @Test
    fun computePwmBassPulseTiming_snapsToMusicalBpmSubdivision() {
        val engine = SubwooferHapticEngine(testLraCapabilities()) { _, _ -> }
        val hw = testLraCapabilities()
        val config = TuningConfig(pwmPulseGapMs = 135f)

        val timing120 = engine.computePwmBassPulseTiming(48f, 120, config, hw)
        assertTrue(timing120.isBpmLocked)
        assertTrue(timing120.silenceGapMs >= SubwooferHapticEngine.MIN_PWM_SILENCE_MS)

        // At 120 BPM, beat = 500ms; valid subdivisions: 750, 500, 250, 167, 125, 83, 63
        val period = timing120.pulseDurationMs + timing120.silenceGapMs
        val validPeriods = listOf(750L, 500L, 250L, 167L, 125L, 83L, 63L)
        assertTrue("Expected period $period to match 120 BPM subdivision", period in validPeriods)
    }

    @Test
    fun pwmScheduler_enforcesRealSilenceLockoutAndRapidDriveDecay() {
        var currentSimTime = 1_000L
        val dispatches = mutableListOf<Pair<Long, Long>>() // timeMs to durationMs
        val engine = SubwooferHapticEngine(testLraCapabilities()) { kind, dur ->
            if (kind == "pwm_bass") dispatches += currentSimTime to dur
        }
        val config = TuningConfig(pwmEnabled = true, pwmPulseGapMs = 135f)

        val frame = bassFrame(freqHz = 48f, bpm = 0, bassDepth = 0.85f)
        var peakDriveDuringPulse = 0f
        var minDriveDuringSilence = 1f
        var sawSilenceState = false

        while (currentSimTime <= 1_450L) {
            val out = engine.processTelemetry(frame, config, currentSimTime)
            if (out.isPwmPulseActive) {
                if (out.driveLevel > peakDriveDuringPulse) peakDriveDuringPulse = out.driveLevel
            } else if (out.pwmSilenceGapMs > 0) {
                sawSilenceState = true
                if (out.driveLevel < minDriveDuringSilence) minDriveDuringSilence = out.driveLevel
            }
            currentSimTime += 10L
        }

        assertTrue("Expected at least 2 pulses in 450ms", dispatches.size >= 2)
        assertTrue("Expected silence gap state between pulses", sawSilenceState)
        assertTrue("Expected drive to decay rapidly in silence gap ($minDriveDuringSilence vs $peakDriveDuringPulse)", minDriveDuringSilence < peakDriveDuringPulse * 0.45f)

        for (i in 1 until dispatches.size) {
            val prevStart = dispatches[i - 1].first
            val prevDur = dispatches[i - 1].second
            val silenceGap = dispatches[i].first - (prevStart + prevDur)
            assertTrue("Real silence gap must be >= 45ms, got $silenceGap ms", silenceGap >= 45L)
        }
    }

    @Test
    fun kickAndBassChannelTurnOff_isolateChannelsAndClearTimers() {
        val dispatches = mutableListOf<String>()
        val engine = SubwooferHapticEngine(testLraCapabilities()) { kind, _ ->
            dispatches += kind
        }

        // Kick disabled, Bass enabled: kick triggers must NOT fire or trigger sidechain
        val kickOffConfig = TuningConfig(kickEnabled = false, bassEnabled = true, pwmEnabled = true)
        val kickAttempt = engine.processTelemetry(
            bassFrame(kickTriggered = true, kickImpactStrength = 0.9f, bassDepth = 0.75f),
            kickOffConfig,
            nowMs = 1_000L
        )
        assertFalse("Kick should not fire when kickEnabled=false", "kick" in dispatches)
        assertFalse("Sidechain ducking must stay false when kickEnabled=false", kickAttempt.isSidechainDucking)

        val bassWhileKickOff = engine.processTelemetry(
            bassFrame(kickTriggered = false, bassDepth = 0.75f),
            kickOffConfig,
            nowMs = 1_010L
        )
        assertTrue("Bass should still fire when only kick is turned off", "pwm_bass" in dispatches)
        assertTrue(bassWhileKickOff.isPwmPulseActive)

        // Bass disabled, Kick enabled: bass must reset timers and emit zero bass dispatches
        dispatches.clear()
        val bassOffConfig = TuningConfig(kickEnabled = true, bassEnabled = false, pwmEnabled = true)
        val bassDisabledOut = engine.processTelemetry(
            bassFrame(kickTriggered = false, bassDepth = 0.85f),
            bassOffConfig,
            nowMs = 1_200L
        )
        assertFalse("pwm_bass" in dispatches)
        assertFalse(bassDisabledOut.isPwmPulseActive)
        assertEquals(0, bassDisabledOut.pwmSilenceGapMs)

        val kickWhileBassOff = engine.processTelemetry(
            bassFrame(kickTriggered = true, kickImpactStrength = 0.92f, bassDepth = 0.85f),
            bassOffConfig,
            nowMs = 1_210L
        )
        assertTrue("Kick should still fire when only bass is turned off", "kick" in dispatches)
        assertTrue(kickWhileBassOff.isSidechainDucking)

        // ERM mode respects kickEnabled = false
        dispatches.clear()
        val ermKickOff = kickOffConfig.copy(forceErmMode = true)
        engine.processTelemetry(
            bassFrame(kickTriggered = true, kickImpactStrength = 0.9f),
            ermKickOff,
            nowMs = 1_500L
        )
        assertFalse("ERM kick must respect kickEnabled=false", "erm_kick" in dispatches)
    }

    @Test
    fun legacyMode_preservesEngineThrottleAndContinuousBassWhenPwmDisabled() {
        val dispatches = mutableListOf<String>()
        val engine = SubwooferHapticEngine(testLraCapabilities()) { kind, _ ->
            dispatches += kind
        }

        val throttleLegacy = TuningConfig(pwmEnabled = false, engineThrottleEnabled = true)
        val outThrottle = engine.processTelemetry(bassFrame(bassDepth = 0.80f), throttleLegacy, nowMs = 2_000L)
        assertTrue("Expected legacy_cadence dispatch", "legacy_cadence" in dispatches)
        assertEquals(0, outThrottle.pwmSilenceGapMs)
        assertFalse(outThrottle.isPwmPulseActive)
        assertFalse(outThrottle.isPwmBpmLocked)

        dispatches.clear()
        val continuousLegacy = TuningConfig(pwmEnabled = false, engineThrottleEnabled = false)
        val outContinuous = engine.processTelemetry(bassFrame(bassDepth = 0.80f), continuousLegacy, nowMs = 2_200L)
        assertTrue("Expected legacy_bass dispatch", "legacy_bass" in dispatches)
        assertEquals(0, outContinuous.pwmSilenceGapMs)
        assertFalse(outContinuous.isPwmPulseActive)
    }

    @Test
    fun forPreset_preservesMasterPwmAndChannelControls() {
        val preset = TuningConfig.forPreset(
            preset = SubwooferPreset.EDM_PUNCH,
            currentLatencyMs = 40,
            pwmEnabled = false,
            pwmPulseGapMs = 190f,
            kickEnabled = false,
            bassEnabled = true
        )
        assertFalse(preset.pwmEnabled)
        assertEquals(190f, preset.pwmPulseGapMs, 0.01f)
        assertFalse(preset.kickEnabled)
        assertTrue(preset.bassEnabled)
        assertEquals(40, preset.latencyOffsetMs)
    }

    @Test
    fun acousticDspPipeline_estimatesKicklessBassSwellBpmAndDecaysAfterSilence() {
        val sampleRate = 48_000
        val pipeline = AcousticDspPipeline(sampleRate = sampleRate)
        val config = TuningConfig()
        val block = FloatArray(AcousticDspPipeline.HOP_SIZE)

        var simTimeMs = 1_000L
        var sampleIdx = 0L
        var lastEstimatedBpm = 0

        // 5 seconds of smooth 52Hz bass swells pulsing at 100 BPM (600ms period, 320ms on / 280ms off)
        val totalHops = (5.0 * sampleRate / AcousticDspPipeline.HOP_SIZE).toInt()
        for (hop in 0 until totalHops) {
            for (i in block.indices) {
                val t = sampleIdx.toDouble() / sampleRate
                sampleIdx++
                val cyclePos = t % 0.600
                // Smooth raised-cosine envelope (zero percussive click)
                val env = if (cyclePos < 0.360) {
                    0.5 - 0.5 * kotlin.math.cos(2.0 * PI * cyclePos / 0.360)
                } else {
                    0.0
                }
                block[i] = (sin(2.0 * PI * 52.0 * t) * 0.72 * env).toFloat()
            }
            simTimeMs += 5L
            pipeline.processBuffer(block, block.size, config, simTimeMs) { frame ->
                lastEstimatedBpm = frame.estimatedBpm
            }
        }

        assertTrue("Expected kickless swell BPM near 100 (85..115), got $lastEstimatedBpm", lastEstimatedBpm in 85..115)

        // Feed > 5200ms of silence and verify stale BPM resets to 0
        block.fill(0f)
        for (hop in 0 until 1060) {
            simTimeMs += 5L
            pipeline.processBuffer(block, block.size, config, simTimeMs) { frame ->
                lastEstimatedBpm = frame.estimatedBpm
            }
        }
        assertEquals("Expected stale BPM to reset to 0 after >5s silence", 0, lastEstimatedBpm)
    }

    @Test
    fun acousticDspPipeline_boundsSubDominantFreqAgainstViolinBodyBleed() {
        val sampleRate = 48_000
        val pipeline = AcousticDspPipeline(sampleRate = sampleRate)
        val config = TuningConfig(bassCrossoverHz = 88f)
        val block = FloatArray(AcousticDspPipeline.HOP_SIZE)

        var sampleIdx = 0L
        var simTimeMs = 1_000L
        var settledDominantHz = 0f

        // 55 Hz cello/sub-bass paired with strong 185 Hz violin/viola body resonance
        for (hop in 0 until 180) {
            for (i in block.indices) {
                val t = sampleIdx.toDouble() / sampleRate
                sampleIdx++
                val env = (t / 0.08).coerceIn(0.0, 1.0)
                val celloSub = sin(2.0 * PI * 55.0 * t) * 0.55
                val violinBleed = sin(2.0 * PI * 185.0 * t) * 0.70
                block[i] = ((celloSub + violinBleed) * env).toFloat()
            }
            simTimeMs += 5L
            pipeline.processBuffer(block, block.size, config, simTimeMs) { frame ->
                if (hop > 40) {
                    settledDominantHz = frame.dominantLowFreqHz
                }
            }
        }

        assertTrue(
            "Expected dominantLowFreqHz to stay anchored to sub-bass (< 120 Hz) despite 185 Hz bleed, got $settledDominantHz",
            settledDominantHz in 40f..120f
        )
    }
}

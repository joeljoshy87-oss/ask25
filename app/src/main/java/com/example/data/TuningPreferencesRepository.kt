package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.CaptureSource
import com.example.model.SubwooferPreset
import com.example.model.TuningConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.subPulseDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "subpulse_tuning_prefs"
)

class TuningPreferencesRepository(private val context: Context) {

    private object Keys {
        val MASTER_DRIVE = floatPreferencesKey("master_drive")
        val MASTER_THRESHOLD = floatPreferencesKey("master_threshold")
        val FORCE_ERM_MODE = booleanPreferencesKey("force_erm_mode")
        val MASTER_LPF_ENABLED = booleanPreferencesKey("master_lpf_enabled")
        val MASTER_LPF_CUTOFF_HZ = floatPreferencesKey("master_lpf_cutoff_hz")
        val ENGINE_THROTTLE_ENABLED = booleanPreferencesKey("engine_throttle_enabled")
        val ENGINE_CADENCE_RPM = floatPreferencesKey("engine_cadence_rpm")
        val PWM_ENABLED = booleanPreferencesKey("pwm_enabled")
        val PWM_PULSE_GAP_MS = floatPreferencesKey("pwm_pulse_gap_ms")
        val KICK_ENABLED = booleanPreferencesKey("kick_enabled")
        val BASS_ENABLED = booleanPreferencesKey("bass_enabled")
        val KICK_GAIN = floatPreferencesKey("kick_gain")
        val BASS_GAIN = floatPreferencesKey("bass_gain")
        val KICK_TIGHTNESS = floatPreferencesKey("kick_tightness")
        val KICK_SENSITIVITY = floatPreferencesKey("kick_sensitivity")
        val BASS_CROSSOVER_HZ = floatPreferencesKey("bass_crossover_hz")
        val BASS_SMOOTHNESS = floatPreferencesKey("bass_smoothness")
        val SIDECHAIN_DUCKING = floatPreferencesKey("sidechain_ducking")
        val LATENCY_OFFSET_MS = intPreferencesKey("latency_offset_ms")
        val BEATER_BOOST = booleanPreferencesKey("beater_boost")
        val ADAPTIVE_AGC = booleanPreferencesKey("adaptive_agc")
        val ACTIVE_PRESET = stringPreferencesKey("active_preset")
        val CAPTURE_SOURCE = stringPreferencesKey("capture_source")
    }

    val tuningConfigFlow: Flow<TuningConfig> = context.subPulseDataStore.data.map { prefs ->
        val presetName = prefs[Keys.ACTIVE_PRESET]
        val preset = SubwooferPreset.entries.firstOrNull { it.name == presetName }
            ?: SubwooferPreset.CLUB_808
        TuningConfig(
            masterDrive = prefs[Keys.MASTER_DRIVE] ?: 0.88f,
            masterThreshold = prefs[Keys.MASTER_THRESHOLD] ?: 0.45f,
            forceErmMode = prefs[Keys.FORCE_ERM_MODE] ?: false,
            masterLpfEnabled = prefs[Keys.MASTER_LPF_ENABLED] ?: false,
            masterLpfCutoffHz = prefs[Keys.MASTER_LPF_CUTOFF_HZ] ?: 220f,
            engineThrottleEnabled = prefs[Keys.ENGINE_THROTTLE_ENABLED] ?: true,
            engineCadenceRpm = prefs[Keys.ENGINE_CADENCE_RPM] ?: 0.68f,
            pwmEnabled = prefs[Keys.PWM_ENABLED] ?: true,
            pwmPulseGapMs = (prefs[Keys.PWM_PULSE_GAP_MS] ?: 135f).coerceIn(45f, 320f),
            kickEnabled = prefs[Keys.KICK_ENABLED] ?: true,
            bassEnabled = prefs[Keys.BASS_ENABLED] ?: true,
            kickGain = prefs[Keys.KICK_GAIN] ?: 0.92f,
            bassGain = prefs[Keys.BASS_GAIN] ?: 0.80f,
            kickTightness = prefs[Keys.KICK_TIGHTNESS] ?: 0.72f,
            kickSensitivity = prefs[Keys.KICK_SENSITIVITY] ?: 0.62f,
            bassCrossoverHz = prefs[Keys.BASS_CROSSOVER_HZ] ?: 88f,
            bassSmoothness = prefs[Keys.BASS_SMOOTHNESS] ?: 0.68f,
            sidechainDucking = prefs[Keys.SIDECHAIN_DUCKING] ?: 0.75f,
            latencyOffsetMs = prefs[Keys.LATENCY_OFFSET_MS] ?: 0,
            beaterBoostEnabled = prefs[Keys.BEATER_BOOST] ?: true,
            adaptiveAgcEnabled = prefs[Keys.ADAPTIVE_AGC] ?: true,
            hapticsMuted = false,
            activePreset = preset
        )
    }

    val captureSourceFlow: Flow<CaptureSource> = context.subPulseDataStore.data.map { prefs ->
        val raw = prefs[Keys.CAPTURE_SOURCE]
        CaptureSource.entries.firstOrNull { it.name == raw } ?: CaptureSource.INTERNAL_APP_AUDIO
    }

    suspend fun saveTuningConfig(config: TuningConfig) {
        context.subPulseDataStore.edit { prefs ->
            prefs[Keys.MASTER_DRIVE] = config.masterDrive
            prefs[Keys.MASTER_THRESHOLD] = config.masterThreshold
            prefs[Keys.FORCE_ERM_MODE] = config.forceErmMode
            prefs[Keys.MASTER_LPF_ENABLED] = config.masterLpfEnabled
            prefs[Keys.MASTER_LPF_CUTOFF_HZ] = config.masterLpfCutoffHz
            prefs[Keys.ENGINE_THROTTLE_ENABLED] = config.engineThrottleEnabled
            prefs[Keys.ENGINE_CADENCE_RPM] = config.engineCadenceRpm
            prefs[Keys.PWM_ENABLED] = config.pwmEnabled
            prefs[Keys.PWM_PULSE_GAP_MS] = config.pwmPulseGapMs
            prefs[Keys.KICK_ENABLED] = config.kickEnabled
            prefs[Keys.BASS_ENABLED] = config.bassEnabled
            prefs[Keys.KICK_GAIN] = config.kickGain
            prefs[Keys.BASS_GAIN] = config.bassGain
            prefs[Keys.KICK_TIGHTNESS] = config.kickTightness
            prefs[Keys.KICK_SENSITIVITY] = config.kickSensitivity
            prefs[Keys.BASS_CROSSOVER_HZ] = config.bassCrossoverHz
            prefs[Keys.BASS_SMOOTHNESS] = config.bassSmoothness
            prefs[Keys.SIDECHAIN_DUCKING] = config.sidechainDucking
            prefs[Keys.LATENCY_OFFSET_MS] = config.latencyOffsetMs
            prefs[Keys.BEATER_BOOST] = config.beaterBoostEnabled
            prefs[Keys.ADAPTIVE_AGC] = config.adaptiveAgcEnabled
            prefs[Keys.ACTIVE_PRESET] = config.activePreset.name
        }
    }

    suspend fun saveCaptureSource(source: CaptureSource) {
        context.subPulseDataStore.edit { prefs ->
            prefs[Keys.CAPTURE_SOURCE] = source.name
        }
    }
}

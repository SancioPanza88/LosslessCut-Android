package com.sanciopanza.losslesscut_android.ui

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "llc_settings")

/** Impostazioni: formato output, qualità snapshot, snap keyframe, ecc. */
class SettingsStore(private val context: Context) {

    data class Settings(
        val outFormat: String = "mp4",
        val snapQuality: Int = 90,
        val snapFormat: String = "jpg",
        val snapToKeyframe: Boolean = true,
        val keepMode: Boolean = true, // true=Keep (esporta), false=Remove (ritaglia via)
        val lastDir: String = ""
    )

    private val OUT = stringPreferencesKey("out_format")
    private val QUAL = intPreferencesKey("snap_quality")
    private val SNAPFMT = stringPreferencesKey("snap_format")
    private val SNAPKF = booleanPreferencesKey("snap_kf")
    private val KEEP = booleanPreferencesKey("keep_mode")

    suspend fun load(): Settings {
        val d = context.dataStore.data.first()
        return Settings(
            outFormat = d[OUT] ?: "mp4",
            snapQuality = d[QUAL] ?: 90,
            snapFormat = d[SNAPFMT] ?: "jpg",
            snapToKeyframe = d[SNAPKF] ?: true,
            keepMode = d[KEEP] ?: true
        )
    }

    suspend fun save(s: Settings) {
        context.dataStore.edit {
            it[OUT] = s.outFormat
            it[QUAL] = s.snapQuality
            it[SNAPFMT] = s.snapFormat
            it[SNAPKF] = s.snapToKeyframe
            it[KEEP] = s.keepMode
        }
    }
}

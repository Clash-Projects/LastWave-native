package com.lastwave.app.data.local

import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * Reads by name and verifies the runtime type before returning a value.
 *
 * Preferences.Key equality intentionally uses only the key name. If a legacy
 * or edited backup restores that name with a different value type, the normal
 * typed operator can throw ClassCastException at the call site. This helper
 * turns such values into an ordinary missing preference so defaults apply.
 */
internal inline fun <reified T> Preferences.readSafely(key: Preferences.Key<T>): T? =
    asMap().entries.firstOrNull { it.key.name == key.name }?.value as? T

/**
 * A transient filesystem/read failure must not escape a long-lived collector.
 * DataStore's corruption handler repairs malformed protobuf files; this is the
 * final boundary for other read failures and emits defaults for this session.
 */
internal fun Flow<Preferences>.recoverPreferences(owner: String): Flow<Preferences> =
    catch { error ->
        Log.e(owner, "Preferences unavailable; using safe defaults", error)
        emit(emptyPreferences())
    }

package io.github.hgenmon.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Persists paired generators and user preferences in SharedPreferences.
 * Exposes [paired] and [autoConnect] as reactive [StateFlow]s for Compose.
 */
class GenStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _paired = MutableStateFlow(loadPaired())
    val paired: StateFlow<List<PairedGen>> = _paired.asStateFlow()

    private val _autoConnect = MutableStateFlow(prefs.getBoolean(KEY_AUTO_CONNECT, true))
    val autoConnect: StateFlow<Boolean> = _autoConnect.asStateFlow()

    fun save(gen: PairedGen) {
        val updated = _paired.value.filter { it.mac != gen.mac } + gen
        persist(updated)
        _paired.value = updated
    }

    fun forget(mac: String) {
        val updated = _paired.value.filter { it.mac != mac }
        persist(updated)
        _paired.value = updated
    }

    fun setAutoConnect(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CONNECT, enabled).apply()
        _autoConnect.value = enabled
    }

    private fun loadPaired(): List<PairedGen> {
        val json = prefs.getString(KEY_PAIRED, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { PairedGen.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun persist(gens: List<PairedGen>) {
        val arr = JSONArray()
        for (g in gens) arr.put(g.toJson())
        prefs.edit().putString(KEY_PAIRED, arr.toString()).apply()
    }

    private companion object {
        const val PREFS_NAME = "hgenmon"
        const val KEY_PAIRED = "paired"
        const val KEY_AUTO_CONNECT = "auto_connect"
    }
}

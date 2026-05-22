package io.github.hgenmon.data

import org.json.JSONObject

data class PairedGen(
    val mac: String,
    val serial: String,
    val label: String? = null,
    val pairedAt: Long = System.currentTimeMillis(),
) {
    fun displayName(): String = label?.takeIf { it.isNotBlank() } ?: serial

    fun toJson(): JSONObject = JSONObject().apply {
        put("mac", mac)
        put("serial", serial)
        if (!label.isNullOrBlank()) put("label", label)
        put("pairedAt", pairedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): PairedGen = PairedGen(
            mac = o.getString("mac"),
            serial = o.getString("serial"),
            label = o.optString("label").takeIf { it.isNotEmpty() },
            pairedAt = o.optLong("pairedAt", System.currentTimeMillis()),
        )
    }
}

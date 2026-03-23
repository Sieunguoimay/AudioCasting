package com.audiocast.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "ProfileManager"
private const val PREFS_NAME = "audiocast_profiles"
private const val KEY_DEVICE_NAME = "device_name"
private const val KEY_SAVED_SERVERS = "saved_servers"
private const val KEY_LAST_SERVER = "last_server"
private const val KEY_DEFAULT_VOLUME = "default_volume"
private const val KEY_DEFAULT_BUFFER_MS = "default_buffer_ms"
private const val KEY_AUTO_RECONNECT = "auto_reconnect"
private const val KEY_ADAPTIVE_BUFFER = "adaptive_buffer"
private const val KEY_VOLUME_GROUP = "volume_group"
private const val KEY_SAVED_PIN = "saved_pin_%s_%d" // host_port

/**
 * Manages saved connection profiles and user preferences using SharedPreferences.
 */
class ProfileManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * A saved server connection profile.
     */
    data class ServerProfile(
        val name: String,
        val host: String,
        val port: Int,
        val codec: String = "",
        val sampleRate: Int = 48000,
        val channels: Int = 2,
        val lastConnected: Long = 0
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("name", name)
            put("host", host)
            put("port", port)
            put("codec", codec)
            put("sample_rate", sampleRate)
            put("channels", channels)
            put("last_connected", lastConnected)
        }

        companion object {
            fun fromJson(json: JSONObject): ServerProfile = ServerProfile(
                name = json.optString("name", ""),
                host = json.optString("host", ""),
                port = json.optInt("port", 4953),
                codec = json.optString("codec", ""),
                sampleRate = json.optInt("sample_rate", 48000),
                channels = json.optInt("channels", 2),
                lastConnected = json.optLong("last_connected", 0)
            )
        }
    }

    // ──── Device Name ────

    fun getDeviceName(): String {
        return prefs.getString(KEY_DEVICE_NAME, "Android Device") ?: "Android Device"
    }

    fun setDeviceName(name: String) {
        prefs.edit().putString(KEY_DEVICE_NAME, name).apply()
    }

    // ──── Saved Servers ────

    fun getSavedServers(): List<ServerProfile> {
        val json = prefs.getString(KEY_SAVED_SERVERS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { ServerProfile.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse saved servers: ${e.message}")
            emptyList()
        }
    }

    fun saveServer(profile: ServerProfile) {
        val servers = getSavedServers().toMutableList()
        // Remove existing with same host:port
        servers.removeAll { it.host == profile.host && it.port == profile.port }
        // Add updated profile
        servers.add(profile.copy(lastConnected = System.currentTimeMillis()))
        // Keep max 20 saved servers
        val sorted = servers.sortedByDescending { it.lastConnected }.take(20)
        val arr = JSONArray()
        sorted.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_SAVED_SERVERS, arr.toString()).apply()
    }

    fun removeServer(host: String, port: Int) {
        val servers = getSavedServers().toMutableList()
        servers.removeAll { it.host == host && it.port == port }
        val arr = JSONArray()
        servers.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_SAVED_SERVERS, arr.toString()).apply()
    }

    // ──── Last Connected Server ────

    fun getLastServer(): ServerProfile? {
        val json = prefs.getString(KEY_LAST_SERVER, null) ?: return null
        return try {
            ServerProfile.fromJson(JSONObject(json))
        } catch (e: Exception) {
            null
        }
    }

    fun setLastServer(profile: ServerProfile) {
        prefs.edit().putString(KEY_LAST_SERVER, profile.toJson().toString()).apply()
    }

    // ──── Preferences ────

    fun getDefaultVolume(): Float = prefs.getFloat(KEY_DEFAULT_VOLUME, 1.0f)
    fun setDefaultVolume(volume: Float) = prefs.edit().putFloat(KEY_DEFAULT_VOLUME, volume).apply()

    fun getDefaultBufferMs(): Int = prefs.getInt(KEY_DEFAULT_BUFFER_MS, 50)
    fun setDefaultBufferMs(ms: Int) = prefs.edit().putInt(KEY_DEFAULT_BUFFER_MS, ms).apply()

    fun getAutoReconnect(): Boolean = prefs.getBoolean(KEY_AUTO_RECONNECT, true)
    fun setAutoReconnect(enabled: Boolean) = prefs.edit().putBoolean(KEY_AUTO_RECONNECT, enabled).apply()

    fun getAdaptiveBuffer(): Boolean = prefs.getBoolean(KEY_ADAPTIVE_BUFFER, true)
    fun setAdaptiveBuffer(enabled: Boolean) = prefs.edit().putBoolean(KEY_ADAPTIVE_BUFFER, enabled).apply()

    fun getVolumeGroup(): String = prefs.getString(KEY_VOLUME_GROUP, "") ?: ""
    fun setVolumeGroup(group: String) = prefs.edit().putString(KEY_VOLUME_GROUP, group).apply()

    // ──── Saved PINs (per server) ────

    fun getSavedPin(host: String, port: Int): String? {
        return prefs.getString(String.format(KEY_SAVED_PIN, host, port), null)
    }

    fun savePin(host: String, port: Int, pin: String) {
        prefs.edit().putString(String.format(KEY_SAVED_PIN, host, port), pin).apply()
    }

    fun clearPin(host: String, port: Int) {
        prefs.edit().remove(String.format(KEY_SAVED_PIN, host, port)).apply()
    }
}

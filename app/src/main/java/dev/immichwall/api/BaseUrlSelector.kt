package dev.immichwall.api

import dev.immichwall.settings.SettingsRepository
import dev.immichwall.util.Logg
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Picks which base URL to talk to: the primary LAN URL or the optional away
 * (e.g. Tailscale) URL. Remembers the last URL that actually answered so
 * steady-state requests don't pay a probe round-trip.
 */
class BaseUrlSelector(private val settings: SettingsRepository) {

    companion object {
        private const val TAG = "BaseUrlSelector"
        private const val PROBE_TIMEOUT_SECONDS = 3L
    }

    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** Last-known-good URL if we have one, else the configured primary. Never probes. */
    fun currentBaseUrl(): String {
        val lastGood = settings.lastGoodBaseUrl.trim().trimEnd('/')
        if (lastGood.isNotBlank()) return lastGood
        return settings.serverUrl.trim().trimEnd('/')
    }

    /**
     * Probes primary then away URL (3s timeout each) against the unauthenticated
     * `/api/server/version` endpoint. Persists the first URL that answers as
     * [SettingsRepository.lastGoodBaseUrl] and returns it; returns null if both
     * are dead (the previously persisted last-good value is left untouched so the
     * app keeps a plausible URL for later retries).
     */
    fun probeAndSelect(): String? {
        val primary = settings.serverUrl.trim().trimEnd('/')
        val away = settings.awayUrl.trim().trimEnd('/')
        for (candidate in listOf(primary, away)) {
            if (candidate.isBlank()) continue
            if (isReachable(candidate)) {
                if (settings.lastGoodBaseUrl != candidate) {
                    settings.lastGoodBaseUrl = candidate
                }
                Logg.d(TAG, "Selected base URL: $candidate")
                return candidate
            }
        }
        Logg.w(TAG, "No reachable base URL (primary='$primary', away='${away.ifBlank { "<unset>" }}')")
        return null
    }

    private fun isReachable(base: String): Boolean {
        val url = "$base/api/server/version".toHttpUrlOrNull() ?: return false
        return try {
            probeClient.newCall(Request.Builder().url(url).get().build())
                .execute()
                .use { it.isSuccessful }
        } catch (e: Exception) {
            Logg.d(TAG, "Probe failed for $base: ${e.message}")
            false
        }
    }
}

package dev.immichwall

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Configuration
import dev.immichwall.settings.SettingsRepository
import dev.immichwall.sync.SyncScheduler
import dev.immichwall.util.Logg

class App : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Keep process start light: this also runs in the wallpaper process, where
        // system_server binds the engine immediately and any main-thread work here delays
        // the first frame. SettingsRepository.get stays a blocking singleton (safe from
        // any thread) and ensurePeriodic is idempotent (KEEP), so both can run slightly
        // later off the main thread.
        Thread({
            try {
                val settings = SettingsRepository.get(this)
                // Pre-open the encrypted store off-main (Keystore + disk, 100-300ms) so
                // the first UI read of the API key hits the cached instance instead of
                // blocking the main thread.
                settings.warmUp()
                if (settings.isConfigured) {
                    Logg.d(TAG, "Configured; ensuring periodic cache refresh is scheduled")
                    SyncScheduler.ensurePeriodic(this)
                } else {
                    Logg.d(TAG, "Not configured yet; skipping periodic scheduling")
                }
            } catch (t: Throwable) {
                // Startup housekeeping must never take down the (wallpaper) process;
                // scheduling is re-attempted on the next process start.
                Logg.e(TAG, "Startup scheduling failed", t)
            }
        }, "app-startup").start()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_HEALTH,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Actionable wallpaper health issues (wallpaper replaced, cache empty)"
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "App"
        const val CHANNEL_HEALTH = "health"
    }
}

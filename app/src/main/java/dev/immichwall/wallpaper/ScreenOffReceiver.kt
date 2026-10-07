package dev.immichwall.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.immichwall.util.Logg

/**
 * PRIMARY advance trigger ([FIX] trigger inversion, DESIGN.md): `onVisibilityChanged(false)`
 * does not fire at screen-off when an app occludes the launcher, so this runtime-registered
 * receiver — registered by [PhotoWallpaperService] while at least one engine exists — funnels
 * SCREEN_OFF/SCREEN_ON into [RotationController]'s debounced state machine.
 *
 * Living in the wallpaper process is legal here: the process is bound at visible priority
 * while the wallpaper is set and is never cache-frozen.
 */
class ScreenOffReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        when (intent?.action) {
            Intent.ACTION_SCREEN_OFF -> {
                Logg.d(TAG, "ACTION_SCREEN_OFF")
                RotationController.onScreenOff(ctx)
            }
            Intent.ACTION_SCREEN_ON -> {
                Logg.d(TAG, "ACTION_SCREEN_ON")
                RotationController.onScreenOn(ctx)
            }
        }
    }

    private companion object {
        const val TAG = "ScreenOffReceiver"
    }
}

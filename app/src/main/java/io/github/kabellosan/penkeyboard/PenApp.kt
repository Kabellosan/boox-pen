package io.github.kabellosan.penkeyboard

import android.app.Application
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * The Onyx SDK calls hidden Android APIs (EPD refresh, pen routing) by reflection,
 * which Android 9+ blocks. Exempting them must happen before any TouchHelper is made.
 * Same approach as Miroir-IME's MiroirApp.
 */
class PenApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/onyx/ViewUpdateHelper;",
                "Landroid/onyx/epd/EpdModeManager;",
                "Landroid/view/View;",
                "Lcom/onyx/android/sdk/pen/TouchHelper;"
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Hidden API bypass failed: ${e.message}")
        }
    }

    companion object { const val TAG = "PenKeyboard" }
}

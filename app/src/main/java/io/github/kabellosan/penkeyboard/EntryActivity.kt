package io.github.kabellosan.penkeyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler

/**
 * The home icon. Boox's button settings can open an app on a long press, and
 * "Pen Keyboard" there launches this the same way the home icon does. The home
 * icon can only be tapped with the home screen showing, so: opened over the home
 * screen = setup; opened over another app = Commands over that app. Tells them
 * apart by the helper's last seen app; without the helper, always setup.
 */
class EntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        if (overAnApp()) {
            finish()
            overridePendingTransition(0, 0)
            Handler(mainLooper).postDelayed({
                if (!PenIME.showSelf(app, commands = true)) {
                    startActivity(Intent(app, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }, 300)
            return
        }
        startActivity(Intent(this, SetupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        finish()
        overridePendingTransition(0, 0)
    }

    private fun overAnApp(): Boolean {
        val before = PenButtonProbe.instance?.lastApp
        val home = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        val over = before != null && before != home && before != packageName &&
            before != "com.android.systemui" && !before.contains("packageinstaller")
        PenButtonProbe.record(this, "Opened over ${before ?: "unknown (helper off?)"} (home: $home)" +
            if (over) " → Commands" else "")
        return over
    }
}

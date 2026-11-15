package io.github.silleruss.drivy

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

internal fun restoreLegacyLauncherEntries(context: Context) {
    val manager = context.packageManager
    for (companion in Companion.entries) {
        val name = ComponentName(context.packageName, "io.github.silleruss.drivy.Launcher${companion.name}")
        try {
            val state = manager.getComponentEnabledSetting(name)
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
                manager.setComponentEnabledSetting(name, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        } catch (error: RuntimeException) {
            Log.w("Drivy", "Could not restore an older launcher entry", error)
        }
    }
}

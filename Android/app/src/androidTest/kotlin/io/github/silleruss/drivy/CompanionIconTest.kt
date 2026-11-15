package io.github.silleruss.drivy

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class CompanionIconTest {
    @Test fun disabledLegacyEntriesAreRestoredWithoutCreatingLauncherIcons() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".uitest"))
        val manager = context.packageManager
        fun component(companion: Companion) = ComponentName(context.packageName, "io.github.silleruss.drivy.Launcher${companion.name}")
        try {
            for (companion in Companion.entries) manager.setComponentEnabledSetting(component(companion),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            manager.setComponentEnabledSetting(component(Companion.RAPI),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, PackageManager.DONT_KILL_APP)
            val userDisabled = manager.getComponentEnabledSetting(component(Companion.RAPI)) ==
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            restoreLegacyLauncherEntries(context)
            if (userDisabled) assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                manager.getComponentEnabledSetting(component(Companion.RAPI)))
            manager.setComponentEnabledSetting(component(Companion.RAPI),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            restoreLegacyLauncherEntries(context)
            for (companion in Companion.entries) {
                assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, manager.getComponentEnabledSetting(component(companion)))
                assertEquals(component(companion).className, manager.getActivityInfo(component(companion), 0).name)
            }
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
            assertTrue("The isolated test app must not appear in the launcher", manager.queryIntentActivities(launcher, 0).isEmpty())
        } finally {
            for (companion in Companion.entries) manager.setComponentEnabledSetting(component(companion),
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        }
    }
}

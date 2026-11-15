package io.github.silleruss.drivy

import android.content.Context

internal enum class SmallWindowMode(val label: String) { OVERLAY("투명 캐릭터 창"), PIP("시스템 PiP") }
internal enum class PetCorner(val label: String) {
    TOP_LEFT("왼쪽 위"), TOP_RIGHT("오른쪽 위"), BOTTOM_LEFT("왼쪽 아래"), BOTTOM_RIGHT("오른쪽 아래")
}

internal class CompanionDisplay(context: Context) {
    private val preferences = context.getSharedPreferences("companion-display", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = preferences.getBoolean("enabled", false)
        set(value) { preferences.edit().putBoolean("enabled", value).apply() }
    var mode: SmallWindowMode
        get() = SmallWindowMode.entries.firstOrNull { it.name == preferences.getString("mode", null) } ?: SmallWindowMode.OVERLAY
        set(value) { preferences.edit().putString("mode", value.name).apply() }
    var corner: PetCorner
        get() = PetCorner.entries.firstOrNull { it.name == preferences.getString("corner", null) } ?: PetCorner.BOTTOM_RIGHT
        set(value) { preferences.edit().putString("corner", value.name).remove("x").remove("y").apply() }

    var notice: String?
        get() = preferences.getString("notice", null)
        set(value) { preferences.edit().putString("notice", value).apply() }

    fun position(): Pair<Float, Float> {
        val defaultX = if (corner in listOf(PetCorner.TOP_RIGHT, PetCorner.BOTTOM_RIGHT)) 1f else 0f
        val defaultY = if (corner in listOf(PetCorner.BOTTOM_LEFT, PetCorner.BOTTOM_RIGHT)) 1f else 0f
        return preferences.getFloat("x", defaultX).coerceIn(0f, 1f) to preferences.getFloat("y", defaultY).coerceIn(0f, 1f)
    }

    fun savePosition(x: Float, y: Float) {
        if (x.isFinite() && y.isFinite()) preferences.edit().putFloat("x", x.coerceIn(0f, 1f))
            .putFloat("y", y.coerceIn(0f, 1f)).apply()
    }
}

internal fun canShowSmallCompanion(enabled: Boolean, chosen: Boolean, uxKnown: Boolean, restricted: Boolean) =
    enabled && chosen && uxKnown && !restricted

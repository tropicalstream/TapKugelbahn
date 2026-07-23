package com.tapkugelbahn

import android.content.Context
import android.os.Build

/** Remembers the furthest level reached and whether to render SBS. */
class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("tapkugelbahn", Context.MODE_PRIVATE)

    private val deviceText = listOf(
        Build.MODEL, Build.MANUFACTURER, Build.BRAND, Build.DEVICE, Build.PRODUCT
    ).joinToString(" ").lowercase()

    // Guide gotcha #24: the X3 Pro reports Build.MODEL=ARGF20; detect by brand.
    val isRayNeoX3 =
        "rayneo" in deviceText || "leiniao" in deviceText || "ffalcon" in deviceText ||
            ("x3" in deviceText && ("tcl" in deviceText || "falcon" in deviceText))

    val sbs get() = isRayNeoX3

    var reached: Int
        get() = p.getInt("reached", 1)
        set(v) { if (v > reached) p.edit().putInt("reached", v).apply() }
}

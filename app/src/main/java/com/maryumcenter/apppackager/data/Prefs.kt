package com.maryumcenter.apppackager.data

import android.content.Context
import androidx.core.content.edit

/** Small, synchronous settings store — there are only four of them. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("app_packager", Context.MODE_PRIVATE)

    var destinationUri: String?
        get() = prefs.getString(KEY_DEST, null)
        set(value) = prefs.edit { putString(KEY_DEST, value) }

    var includeObb: Boolean
        get() = prefs.getBoolean(KEY_OBB, true)
        set(value) = prefs.edit { putBoolean(KEY_OBB, value) }

    var forceXapk: Boolean
        get() = prefs.getBoolean(KEY_FORCE, false)
        set(value) = prefs.edit { putBoolean(KEY_FORCE, value) }

    var showSystemApps: Boolean
        get() = prefs.getBoolean(KEY_SYSTEM, false)
        set(value) = prefs.edit { putBoolean(KEY_SYSTEM, value) }

    private companion object {
        const val KEY_DEST = "destination_uri"
        const val KEY_OBB = "include_obb"
        const val KEY_FORCE = "force_xapk"
        const val KEY_SYSTEM = "show_system"
    }
}

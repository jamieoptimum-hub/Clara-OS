package com.jarvis.smarthomelauncher.core

import android.content.Context
import android.content.SharedPreferences

object DashboardLayoutStore {
    private const val PREFS_NAME = "dashboard_layout_store_v1"
    private const val KEY_MIGRATED_LEGACY = "migrated_legacy_layout_prefs_v1"

    @Synchronized
    fun getPrefs(context: Context): SharedPreferences {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_MIGRATED_LEGACY, false)) {
            val editor = prefs.edit()
            val legacyClock = appCtx.getSharedPreferences("clara_hero_clock_split_prefs", Context.MODE_PRIVATE)
            if (!prefs.contains("hero_clock_split_ratio") && legacyClock.contains("hero_clock_split_ratio")) {
                editor.putFloat("hero_clock_split_ratio", legacyClock.getFloat("hero_clock_split_ratio", 0.50f))
            }
            val legacyCards = appCtx.getSharedPreferences("clara_unified_grid_cards_v2", Context.MODE_PRIVATE)
            if (!prefs.contains("unified_cards_list_json")) {
                legacyCards.getString("unified_cards_list_json", null)?.takeIf { it.isNotBlank() }?.let {
                    editor.putString("unified_cards_list_json", it)
                }
            }
            val legacyPhotos = appCtx.getSharedPreferences("clara_room_photo_uris_v1", Context.MODE_PRIVATE)
            for ((k, v) in legacyPhotos.all) {
                if (v is String && v.isNotBlank() && !prefs.contains("room_photo_$k")) {
                    editor.putString("room_photo_$k", v)
                }
            }
            editor.putBoolean(KEY_MIGRATED_LEGACY, true).apply()
            runCatching { appCtx.deleteSharedPreferences("clara_hero_clock_split_prefs") }
            runCatching { appCtx.deleteSharedPreferences("clara_unified_grid_cards_v2") }
            runCatching { appCtx.deleteSharedPreferences("clara_room_photo_uris_v1") }
        }
        return prefs
    }

    fun getHeroClockSplitRatio(context: Context): Float =
        getPrefs(context).getFloat("hero_clock_split_ratio", 0.50f)

    fun setHeroClockSplitRatio(context: Context, split: Float) {
        getPrefs(context).edit().putFloat("hero_clock_split_ratio", split).apply()
    }

    fun getUnifiedCardsJson(context: Context): String =
        getPrefs(context).getString("unified_cards_list_json", "[]") ?: "[]"

    fun setUnifiedCardsJson(context: Context, json: String) {
        getPrefs(context).edit().putString("unified_cards_list_json", json).apply()
    }

    fun getRoomPhotoUri(context: Context, roomName: String): String? =
        getPrefs(context).getString("room_photo_$roomName", null)

    fun setRoomPhotoUri(context: Context, roomName: String, uri: String) {
        getPrefs(context).edit().putString("room_photo_$roomName", uri).apply()
    }

    fun getAllRoomPhotos(context: Context): Map<String, String> {
        val prefs = getPrefs(context)
        val map = mutableMapOf<String, String>()
        for ((k, v) in prefs.all) {
            if (k.startsWith("room_photo_") && v is String) {
                map[k.removePrefix("room_photo_")] = v
            }
        }
        return map
    }

    fun setAllRoomPhotos(context: Context, photos: Map<String, String>) {
        val editor = getPrefs(context).edit()
        for ((k, v) in photos) {
            editor.putString("room_photo_$k", v)
        }
        editor.apply()
    }
}

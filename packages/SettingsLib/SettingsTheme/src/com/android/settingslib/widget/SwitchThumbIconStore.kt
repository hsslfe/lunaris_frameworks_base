/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settingslib.widget

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/** Process-local, asynchronously loaded cache shared by expressive switch drawables. */
internal class SwitchThumbIconStore private constructor(context: Context) {
    private val resolver = context.contentResolver
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val listeners = mutableListOf<WeakReference<SwitchThumbIconDrawable>>()
    private var generation = 0

    var bitmap: Bitmap? = null
        private set

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = reload()
    }

    init {
        try {
            resolver.registerContentObserver(Settings.Secure.getUriFor(SETTING_ENABLED), false, observer)
            resolver.registerContentObserver(Settings.Secure.getUriFor(SETTING_PATH), false, observer)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    handler.post { reload() }
                }
            }
            val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to observe switch icon settings", e)
        }
        handler.post { reload() }
    }

    fun addListener(drawable: SwitchThumbIconDrawable) {
        handler.post {
            listeners.add(WeakReference(drawable))
            drawable.invalidateSelf()
        }
    }

    private fun reload() {
        val request = ++generation
        executor.execute {
            val loaded = loadBitmap()
            handler.post {
                if (request != generation) return@post
                bitmap = loaded
                val iterator = listeners.iterator()
                while (iterator.hasNext()) {
                    val drawable = iterator.next().get()
                    if (drawable == null) iterator.remove() else drawable.invalidateSelf()
                }
            }
        }
    }

    private fun loadBitmap(): Bitmap? {
        return try {
            if (Settings.Secure.getInt(resolver, SETTING_ENABLED, 0) != 1) return null
            val path = Settings.Secure.getString(resolver, SETTING_PATH) ?: return null
            val root = File(Environment.getExternalStorageDirectory(), ICON_DIR).canonicalFile
            val file = File(path).canonicalFile
            if (file.parentFile != root || !file.name.startsWith("active_") ||
                !file.name.endsWith(".png") || !file.isFile ||
                file.length() !in 1..MAX_FILE_BYTES) return null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth !in 1..MAX_ICON_SIZE || bounds.outHeight !in 1..MAX_ICON_SIZE) {
                return null
            }
            BitmapFactory.decodeFile(file.path)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to load switch icon", e)
            null
        }
    }

    companion object {
        private const val TAG = "SwitchThumbIconStore"
        private const val SETTING_ENABLED = "lunaris_switch_thumb_icon_enabled"
        private const val SETTING_PATH = "lunaris_switch_thumb_icon_path"
        private const val ICON_DIR = "Lunaris/SwitchThumbIcon"
        private const val MAX_FILE_BYTES = 256L * 1024
        private const val MAX_ICON_SIZE = 256
        private val stores = mutableMapOf<ContentResolver, SwitchThumbIconStore>()

        @Synchronized
        fun get(context: Context): SwitchThumbIconStore {
            val appContext = context.applicationContext ?: context
            return stores.getOrPut(appContext.contentResolver) { SwitchThumbIconStore(appContext) }
        }
    }
}

/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("DEPRECATION")

package com.android.settingslib.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Environment
import android.os.Looper
import android.provider.Settings
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SwitchThumbIconDrawableTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private val stores = mutableSetOf<Any>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        directory = File(Environment.getExternalStorageDirectory(), "Lunaris/SwitchThumbIcon")
        directory.deleteRecursively()
        directory.mkdirs()
        Settings.Secure.putInt(context.contentResolver, ENABLED, 0)
        Settings.Secure.putString(context.contentResolver, PATH, "")
    }

    @After
    fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
        stores.forEach { executor(it).shutdownNow() }
        cache().clear()
        directory.deleteRecursively()
    }

    @Test
    fun defaultKeepsStockIcons() {
        val drawable = drawable()
        drain(drawable)
        assertEquals(Color.GREEN, pixel(drawable, checked = true))
        assertEquals(Color.GREEN, pixel(drawable, checked = false))
    }

    @Test
    fun customReplacesOnlyOnIcon() {
        activate(writeIcon(Color.RED))
        val drawable = drawable()
        drain(drawable)
        assertEquals(Color.RED, pixel(drawable, checked = true))
        assertEquals(Color.GREEN, pixel(drawable, checked = false))
    }

    @Test
    fun settingChangeUpdatesExistingDrawable() {
        val drawable = drawable()
        drain(drawable)
        activate(writeIcon(Color.BLUE))
        notifyChange(drawable)
        assertEquals(Color.BLUE, pixel(drawable, checked = true))
        Settings.Secure.putInt(context.contentResolver, ENABLED, 0)
        notifyChange(drawable)
        assertEquals(Color.GREEN, pixel(drawable, checked = true))
    }

    @Test
    fun newCacheRestoresPersistedChoice() {
        activate(writeIcon(Color.RED))
        val first = drawable()
        drain(first)
        cache().clear()
        val second = drawable()
        drain(second)
        assertNotSame(store(first), store(second))
        assertEquals(Color.RED, pixel(second, checked = true))
    }

    @Test
    fun missingFileUsesStockIcon() {
        activate(File(directory, "active_missing.png"))
        val drawable = drawable()
        drain(drawable)
        assertEquals(Color.GREEN, pixel(drawable, checked = true))
    }

    @Test
    fun corruptFileUsesStockIcon() {
        val file = File(directory, "active_corrupt.png").apply { writeText("invalid PNG") }
        activate(file)
        val drawable = drawable()
        drain(drawable)
        assertEquals(Color.GREEN, pixel(drawable, checked = true))
    }

    @Test
    fun outsideDirectoryIsRejected() {
        val file = File(context.cacheDir, "active_outside.png")
        writeBitmap(file, Color.RED)
        activate(file)
        val drawable = drawable()
        drain(drawable)
        assertEquals(Color.GREEN, pixel(drawable, checked = true))
    }

    @Test
    fun oversizedImageIsRejected() {
        val file = File(directory, "active_oversized.png")
        writeBitmap(file, Color.RED, size = 512)
        activate(file)
        val drawable = drawable()
        drain(drawable)
        assertEquals(Color.GREEN, pixel(drawable, checked = true))
    }

    private fun activate(file: File) {
        Settings.Secure.putString(context.contentResolver, PATH, file.path)
        Settings.Secure.putInt(context.contentResolver, ENABLED, 1)
    }

    private fun writeIcon(color: Int): File =
        File.createTempFile("active_", ".png", directory).also { writeBitmap(it, color) }

    private fun writeBitmap(file: File, color: Int, size: Int = 128) {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawable(): SwitchThumbIconDrawable =
        SwitchThumbIconDrawable.create(context, ColorDrawable(Color.GREEN)).also {
            stores.add(store(it))
        }

    private fun notifyChange(drawable: SwitchThumbIconDrawable) {
        context.contentResolver.notifyChange(Settings.Secure.getUriFor(PATH), null)
        context.contentResolver.notifyChange(Settings.Secure.getUriFor(ENABLED), null)
        drain(drawable)
    }

    private fun pixel(drawable: SwitchThumbIconDrawable, checked: Boolean): Int {
        drawable.state = if (checked) {
            intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked)
        } else {
            intArrayOf(android.R.attr.state_enabled)
        }
        drawable.setBounds(0, 0, 24, 24)
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        return try {
            drawable.draw(Canvas(bitmap))
            bitmap.getPixel(12, 12)
        } finally {
            bitmap.recycle()
        }
    }

    // Drain the background decoder as well as the main-thread observer callbacks deterministically.
    private fun drain(drawable: SwitchThumbIconDrawable) {
        val executor = executor(store(drawable))
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            executor.submit {}.get(10, TimeUnit.SECONDS)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun store(drawable: SwitchThumbIconDrawable): Any =
        drawable.javaClass.getDeclaredField("store").apply { isAccessible = true }.get(drawable)!!

    private fun executor(store: Any): ExecutorService =
        store.javaClass.getDeclaredField("executor").apply { isAccessible = true }.get(store)
            as ExecutorService

    private fun cache(): MutableMap<*, *> =
        Class.forName("com.android.settingslib.widget.SwitchThumbIconStore")
            .getDeclaredField("stores").apply { isAccessible = true }.get(null) as MutableMap<*, *>

    private companion object {
        const val ENABLED = "lunaris_switch_thumb_icon_enabled"
        const val PATH = "lunaris_switch_thumb_icon_path"
    }
}

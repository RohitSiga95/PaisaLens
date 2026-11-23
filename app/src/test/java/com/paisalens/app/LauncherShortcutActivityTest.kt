package com.paisalens.app

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.AdaptiveIconDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LauncherShortcutActivityTest {
    @Test
    @Suppress("DEPRECATION")
    fun manifestPublishesFourShortcutsWithIconsAndASeparatePrivateEntryPoint() {
        val app = RuntimeEnvironment.getApplication()
        val launcher = app.packageManager.getActivityInfo(
            ComponentName(app, MainActivity::class.java),
            PackageManager.GET_META_DATA,
        )
        val trampoline = app.packageManager.getActivityInfo(
            ComponentName(app, LauncherShortcutActivity::class.java),
            0,
        )
        val shortcuts = launcher.metaData.getInt("android.app.shortcuts")
        assertEquals(R.xml.launcher_shortcuts, shortcuts)
        assertFalse(trampoline.exported)
        assertTrue(trampoline.taskAffinity.isNullOrEmpty())

        val namespace = "http://schemas.android.com/apk/res/android"
        val ids = mutableListOf<String>()
        val actions = mutableListOf<LauncherShortcut>()
        app.resources.getXml(shortcuts).use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType != XmlPullParser.START_TAG) continue
                when (xml.name) {
                    "shortcut" -> {
                        ids += checkNotNull(xml.getAttributeValue(namespace, "shortcutId"))
                        val icon = xml.getAttributeResourceValue(namespace, "icon", 0)
                        val label = xml.getAttributeResourceValue(namespace, "shortcutShortLabel", 0)
                        assertTrue(app.getDrawable(icon) is AdaptiveIconDrawable)
                        assertTrue(app.getString(label).isNotBlank())
                    }
                    "intent" -> {
                        assertEquals(app.packageName, xml.getAttributeValue(namespace, "targetPackage"))
                        assertEquals(trampoline.name, xml.getAttributeValue(namespace, "targetClass"))
                        actions += checkNotNull(LauncherShortcut.fromAction(xml.getAttributeValue(namespace, "action")))
                    }
                }
            }
        }
        assertEquals(listOf("add_expense", "sync_sms", "bills_and_dues", "reminders"), ids)
        assertEquals(LauncherShortcut.entries.toList(), actions)
    }

    @Test
    fun eachShortcutReusesMainWithoutClearingItsTaskAndHandsOffExactlyOnce() {
        LauncherShortcut.entries.forEach { shortcut ->
            val intent = forward(shortcut.action)
            assertEquals(MainActivity::class.java.name, intent.component?.className)
            assertEquals(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                intent.flags,
            )
            assertEquals(shortcut, LauncherShortcutActivity.takePendingRequest(intent)?.shortcut)
            assertNull(LauncherShortcutActivity.takePendingRequest(intent))
        }
    }

    @Test
    fun anIntentAloneCannotQueueAScanAndRepeatedLaunchesStayDistinct() {
        assertNull(LauncherShortcutActivity.takePendingRequest(Intent(LauncherShortcut.SYNC_SMS.action)))
        val first = forward(LauncherShortcut.SYNC_SMS.action)
        val firstRequest = checkNotNull(LauncherShortcutActivity.takePendingRequest(first))
        assertNull(LauncherShortcutActivity.takePendingRequest(Intent(first.action)))
        val nextRequest = checkNotNull(LauncherShortcutActivity.takePendingRequest(forward(LauncherShortcut.SYNC_SMS.action)))
        assertEquals(firstRequest.shortcut, nextRequest.shortcut)
        assertNotSame(firstRequest, nextRequest)
    }

    @Test
    fun unknownActionsDoNotOpenTheApp() {
        val controller = Robolectric.buildActivity(LauncherShortcutActivity::class.java, Intent(Intent.ACTION_VIEW)).create()
        try {
            assertTrue(controller.get().isFinishing)
            assertNull(shadowOf(controller.get()).nextStartedActivity)
        } finally {
            controller.destroy()
        }
    }

    private fun forward(action: String): Intent {
        val controller = Robolectric.buildActivity(LauncherShortcutActivity::class.java, Intent(action)).create()
        return try {
            assertTrue(controller.get().isFinishing)
            checkNotNull(shadowOf(controller.get()).nextStartedActivity)
        } finally {
            controller.destroy()
        }
    }
}

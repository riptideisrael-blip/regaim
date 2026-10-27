package com.regaim.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression (crash on adding photos from the gallery, Oct 2026): a foreground service must create its notification
 * channel before startForeground, or Android kills the app with "Bad notification for startForeground".
 * Reads each service's source and checks that a channel is created before every startForeground call.
 */
class ForegroundChannelTest {
    private val dir = listOf(File("src/main/java/com/regaim/app"), File("app/src/main/java/com/regaim/app")).first { it.isDirectory }
    private val makesChannel = Regex("""nm\((this|ctx|app|applicationContext)\)|createChannel\(\)|createNotificationChannel\(""")

    @Test fun everyServiceCreatesItsChannelBeforeStartForeground() {
        val services = dir.listFiles { f -> f.name.endsWith("Service.kt") }!!.filter { it.readText().contains("startForeground(") }
        assertTrue("no services found in $dir", services.isNotEmpty())
        for (f in services) {
            val src = f.readText()
            var at = src.indexOf("ServiceCompat.startForeground(")
            while (at >= 0) {
                // The function the call is in: from the nearest "fun " before it.
                val start = src.lastIndexOf(" fun ", at)
                val body = src.substring(start, at)
                assertTrue("${f.name}: startForeground without creating the notification channel first", makesChannel.containsMatchIn(body))
                at = src.indexOf("ServiceCompat.startForeground(", at + 1)
            }
        }
    }
}

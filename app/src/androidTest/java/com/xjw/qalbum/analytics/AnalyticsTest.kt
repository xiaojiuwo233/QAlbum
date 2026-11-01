package com.xjw.qalbum.analytics

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.ThemeMode
import com.xjw.qalbum.ui.onboarding.AGREEMENT_VERSION
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** 同意协议前什么都不发；开关决定发不发事件与错误；关掉使用分析时清掉用户属性 */
@RunWith(AndroidJUnit4::class)
class AnalyticsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storeFile = File(context.cacheDir, "analytics-test-${UUID.randomUUID()}.preferences_pb")
    private val settings = SettingsRepository(context, scope, PreferenceDataStoreFactory.create(scope = scope) { storeFile })
    private val backend = RecordingBackend()
    private val analytics = Analytics(backend, settings, scope)

    @After
    fun tearDown() {
        scope.cancel()
        storeFile.delete()
    }

    private class RecordingBackend : AnalyticsBackend {
        val states = CopyOnWriteArrayList<AnalyticsState>()
        val events = CopyOnWriteArrayList<Pair<String, Map<String, Any>>>()
        val properties = CopyOnWriteArrayList<Pair<String, String?>>()
        val errors = CopyOnWriteArrayList<Throwable>()

        override fun apply(state: AnalyticsState) {
            states += state
        }

        override fun log(name: String, params: Map<String, Any>) {
            events += name to params
        }

        override fun setUserProperty(name: String, value: String?) {
            properties += name to value
        }

        override fun setCrashKey(name: String, value: String) = Unit

        override fun recordError(error: Throwable, message: String?) {
            errors += error
        }

        override fun forCrashReport(error: Throwable): Throwable = error
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    private fun names() = backend.events.map { it.first }

    @Test
    fun beforeAgreementNothingIsSent() {
        analytics.event("early")
        analytics.start()
        waitFor("state") { backend.states.isNotEmpty() }
        assertEquals(AnalyticsState(consent = false, usage = false, crash = false), backend.states.last())
        analytics.event("later")
        analytics.recordError(IOException("x"), null)
        assertTrue(backend.events.isEmpty())
        assertTrue(backend.errors.isEmpty())
    }

    @Test
    fun afterAgreementEventsAndSettingChangesAreSent() = runBlocking {
        analytics.event("early", "count" to 3)
        analytics.start()
        waitFor("state") { backend.states.isNotEmpty() }
        settings.setAgreementVersion(AGREEMENT_VERSION)
        waitFor("consent") { backend.states.last().usage }
        waitFor("agreement_accept") { "agreement_accept" in names() }
        // 读出设置时还没同意协议：之前攒下的事件丢掉
        assertFalse("early" in names())
        analytics.event("scan", "count" to 5, "ok" to true, "skip" to null)
        val scan = backend.events.last { it.first == "scan" }.second
        assertEquals(mapOf("count" to 5L, "ok" to "yes"), scan)
        settings.setThemeMode(ThemeMode.DARK)
        waitFor("setting_change") { "setting_change" in names() }
        assertEquals(mapOf("setting" to "theme_mode", "setting_value" to "dark"), backend.events.last { it.first == "setting_change" }.second)
        analytics.recordError(IOException("/storage/emulated/0/a/b 123456"), null)
        assertEquals("<path> <n>", backend.errors.single().message)
    }

    @Test
    fun earlyEventsAreFlushedOnceAgreed() = runBlocking {
        settings.setAgreementVersion(AGREEMENT_VERSION)
        analytics.event("early")
        analytics.start()
        waitFor("early") { "early" in names() }
    }

    @Test
    fun turningUsageOffStopsEventsAndClearsProperties() = runBlocking {
        settings.setAgreementVersion(AGREEMENT_VERSION)
        analytics.start()
        waitFor("usage") { backend.states.lastOrNull()?.usage == true }
        waitFor("access_mode") { backend.properties.any { it.first == "access_mode" && it.second == "shizuku" } }
        settings.setUsageAnalytics(false)
        waitFor("usage off") { backend.states.last().let { it.consent && !it.usage && it.crash } }
        assertTrue(Analytics.USER_PROPERTIES.all { name -> backend.properties.any { it.first == name && it.second == null } })
        val before = backend.events.size
        analytics.event("scan")
        analytics.screen("gallery")
        assertEquals(before, backend.events.size)
        // 崩溃日志单独开关
        analytics.recordError(IOException("boom"), null)
        assertEquals(1, backend.errors.size)
        settings.setCrashReports(false)
        waitFor("crash off") { !backend.states.last().crash }
        analytics.recordError(IOException("boom"), null)
        assertEquals(1, backend.errors.size)
    }
}

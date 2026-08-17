package com.xjw.qalbum.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjw.qalbum.service.ai.AiPrivacyGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryPrivacyTest {

    @Test
    fun publicAcknowledgementIsBoundToExactEndpoint() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repository = SettingsRepository(
            ApplicationProvider.getApplicationContext<Context>(),
            scope,
        )

        repository.setAiEndpoint("https://privacy-test-a.example/v1")
        repository.setAiPublicEndpointAcknowledged(true)
        assertTrue(repository.aiPublicEndpointAcknowledged.first())

        repository.setAiEndpoint("https://privacy-test-b.example/v1")
        assertFalse(repository.aiPublicEndpointAcknowledged.first())
        assertFalse(repository.isAiPublicEndpointAcknowledged("https://privacy-test-a.example/v1"))

        repository.setAiPublicEndpointAcknowledged(true)
        assertTrue(repository.aiPublicEndpointAcknowledged.first())
        assertTrue(repository.isAiPublicEndpointAcknowledged("https://privacy-test-b.example/v1"))
        repository.setAiEndpoint("http://127.0.0.1:8080/v1")
        assertFalse(repository.aiPublicEndpointAcknowledged.first())

        // Keep the assertion explicit: the test endpoints above must exercise the public branch.
        assertEqualsPublic(AiPrivacyGuard.classify("https://privacy-test-a.example/v1"))
        scope.cancel()
    }

    private fun assertEqualsPublic(safety: AiPrivacyGuard.Safety) {
        assertTrue(safety == AiPrivacyGuard.Safety.PUBLIC_INTERNET)
    }
}

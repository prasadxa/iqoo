package com.aasra.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterTest {

    private val healthyRam = RamState(freeGb = 6f)

    @Test
    fun routesOfflineWhenNetworkUnavailable() {
        val decision = Router.decide(
            transcript = "What is the weather today?",
            confidence = 0.95f,
            network = NetworkState.UNAVAILABLE,
            ramState = healthyRam,
            language = "en",
        )
        assertEquals(Route.Local, decision.route)
    }

    @Test
    fun routesMedicalKeywordsToCloudLlm() {
        val decision = Router.decide(
            transcript = "Mujhe paracetamol leni chahiye kya?",
            confidence = 0.95f,
            network = NetworkState.AVAILABLE,
            ramState = healthyRam,
            language = "hi",
        )
        assertEquals(Route.CloudLlm(EscalationReason.MEDICAL), decision.route)
    }

    @Test
    fun routesLowConfidenceToRelisten() {
        val decision = Router.decide(
            transcript = "Something mumbled",
            confidence = 0.40f,
            network = NetworkState.AVAILABLE,
            ramState = healthyRam,
            language = "en",
        )
        assertTrue(decision.route is Route.CloudSttRelisten)
    }

    @Test
    fun routesCurrentInfoToWebSearch() {
        val decision = Router.decide(
            transcript = "Aaj mausam kaisa rahega?",
            confidence = 0.90f,
            network = NetworkState.AVAILABLE,
            ramState = healthyRam,
            language = "hi",
        )
        assertTrue(decision.route is Route.WebSearchThenCloud)
    }

    @Test
    fun routesNormalTurnToLocal() {
        val decision = Router.decide(
            transcript = "What time is it",
            confidence = 0.92f,
            network = NetworkState.AVAILABLE,
            ramState = healthyRam,
            language = "en",
        )
        assertEquals(Route.Local, decision.route)
    }
}

package com.aasra.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val searchJson = Json { ignoreUnknownKeys = true }

@Serializable
data class SearchHit(
    val title: String = "",
    val url: String = "",
    val snippet: String = "",
)

@Serializable
private data class SearchResponse(val results: List<SearchHit> = emptyList())

/**
 * POST /v1/search (PLAN 5.5): mode=shorter, gl=in, hl=hi|en, num_results=5.
 * 1 credit/call — gate behind [needsCurrentInfo] (the router), never call blindly.
 * Exposed to both LLMs as tool `web_search(query)`.
 */
class SearchApi(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = CallMissedConfig.BASE_URL,
    private val usage: UsageCounter = UsageCounter(),
) {
    suspend fun search(query: String, hl: String = "hi"): List<SearchHit> =
        withContext(Dispatchers.IO) {
            require(query.isNotBlank()) { "query must not be blank" }
            val bodyJson = buildJsonObject {
                put("query", query)
                put("mode", CallMissedConfig.SEARCH_MODE)
                put("gl", CallMissedConfig.SEARCH_GL)
                put("hl", hl)
                put("num_results", CallMissedConfig.SEARCH_NUM_RESULTS)
            }.toString()
            val req = Request.Builder()
                .url("$baseUrl/v1/search")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                usage.record(resp.headers)
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw CallMissedException(resp.code, CloudErrors.parseCode(raw), raw.take(500))
                }
                searchJson.decodeFromString(SearchResponse.serializer(), raw).results
            }
        }

    companion object {
        // Router gate (PLAN 5.6): only "news / weather / price / when is" style turns.
        private val GATE = listOf(
            "news", "weather", "mausam", "price", "kimat", "rate",
            "kab hai", "when is", "when will", "who won", "score",
            "petrol", "diesel", "gold", "daam", "mandi", "bhav",
        )

        /** True when the transcript needs current info → web_search then cloud LLM. */
        fun needsCurrentInfo(transcript: String): Boolean {
            val t = transcript.lowercase()
            return GATE.any { it in t }
        }
    }
}

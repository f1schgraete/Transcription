package com.siptranscribe.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Posts a finished call transcript to Azure AI Foundry / Azure OpenAI chat completions
 * and parses out the structured call summary.
 *
 * Endpoint forms accepted as the resource base:
 *   - https://<resource>.cognitiveservices.azure.com
 *   - https://<resource>.services.ai.azure.com
 *   - https://<resource>.openai.azure.com
 *
 * The deployment name is the chat model deployment (e.g. `gpt-4o-mini`).
 */
class ConversationAnalyzer(
    private val endpoint: String,
    private val apiKey: String,
    private val deployment: String
) {

    companion object {
        private const val TAG = "ConversationAnalyzer"
        private const val API_VERSION = "2024-08-01-preview"
        private const val TIMEOUT_MS = 30_000

        private const val SYSTEM_PROMPT =
            "Du analysierst das Transkript eines Telefongesprächs auf Deutsch. " +
            "Es gibt zwei Sprecher (Anrufer und Angerufener), aber das Transkript " +
            "ist nicht nach Sprechern getrennt. Antworte ausschließlich mit gültigem " +
            "JSON in genau diesem Schema:\n" +
            "{\n" +
            "  \"caller_name\": string|null,    // Name des Anrufers, falls erkennbar\n" +
            "  \"topic\": string|null,           // Hauptthema in 1-3 Wörtern\n" +
            "  \"important_points\": [string],   // 2-5 Stichpunkte zum Inhalt, kurz\n" +
            "  \"dates\": [string],              // Termine, Daten und Uhrzeiten\n" +
            "  \"todos\": [string]               // Aufgaben oder Vereinbarungen\n" +
            "}\n" +
            "Keine ganzen Sätze, keine Wiederholungen, keine Floskeln. " +
            "Verwende null oder leere Listen, wenn keine Information vorliegt. " +
            "Keine zusätzlichen Felder, keine Markdown-Codeblöcke."
    }

    data class Result(
        val callerName: String?,
        val topic: String?,
        val importantPoints: List<String>,
        val dates: List<String>,
        val todos: List<String>
    )

    fun analyze(transcript: String): Result {
        require(endpoint.isNotBlank()) { "Azure-Endpunkt nicht konfiguriert" }
        require(apiKey.isNotBlank()) { "Azure-API-Schlüssel nicht konfiguriert" }
        require(deployment.isNotBlank()) { "Chat-Deployment nicht konfiguriert" }
        require(transcript.isNotBlank()) { "Transkript ist leer" }

        val url = buildUrl(endpoint, deployment)
        val body = buildRequestBody(transcript)

        Log.i(TAG, "POST $url")
        val response = post(url, body)
        Log.d(TAG, "response: $response")

        return parseResponse(response)
    }

    private fun buildUrl(endpoint: String, deployment: String): String {
        val base = endpoint.trim().trimEnd('/')
        val httpsBase = base
            .replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
            .let { if (it.startsWith("https://", ignoreCase = true)) it else "https://$it" }
        return "$httpsBase/openai/deployments/$deployment/chat/completions?api-version=$API_VERSION"
    }

    private fun buildRequestBody(transcript: String): String {
        val payload = JSONObject().apply {
            put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                .put(JSONObject().put("role", "user").put("content", transcript))
            )
            put("temperature", 0.2)
            put("response_format", JSONObject().put("type", "json_object"))
        }
        return payload.toString()
    }

    private fun post(urlStr: String, body: String): String {
        val conn = (URL(urlStr).openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("api-key", apiKey)
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw RuntimeException("HTTP $code: $text")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun parseResponse(json: String): Result {
        val root = JSONObject(json)
        val choices = root.optJSONArray("choices")
            ?: throw RuntimeException("Antwort enthält keine choices")
        if (choices.length() == 0) throw RuntimeException("Antwort enthält keine choices")
        val content = choices.getJSONObject(0)
            .getJSONObject("message")
            .getString("content")

        val analysis = JSONObject(content)
        return Result(
            callerName = analysis.optStringOrNull("caller_name"),
            topic = analysis.optStringOrNull("topic"),
            importantPoints = analysis.optJSONArray("important_points").toStringList(),
            dates = analysis.optJSONArray("dates").toStringList(),
            todos = analysis.optJSONArray("todos").toStringList()
        )
    }

    private fun JSONObject.optStringOrNull(name: String): String? {
        if (!has(name) || isNull(name)) return null
        val s = optString(name).trim()
        return s.ifEmpty { null }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until length()) {
            val s = optString(i, "").trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }
}

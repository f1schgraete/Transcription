package com.siptranscribe.app

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Translates short conversational text via Mistral's chat-completions API
 * (EU-hosted), used by the Zuhören "Übersetzen" mode. Reuses the same Mistral
 * API key as [VoxtralSttEngine] (MainActivity.KEY_VOXTRAL_KEY).
 *
 * Each [translate] call is a single stateless REST request. The callback fires
 * on an OkHttp background thread, so the caller must marshal back to the main
 * thread itself.
 */
class MistralTranslator(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val baseHost: String = "api.mistral.ai"
) {
    companion object {
        private const val TAG = "MistralTranslator"
        const val DEFAULT_MODEL = "mistral-small-latest"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Translate [text] into [targetLanguageEnglishName] (e.g. "German",
     * "English"). Calls [onResult] with the translation, or null on any error.
     */
    fun translate(text: String, targetLanguageEnglishName: String, onResult: (String?) -> Unit) {
        if (apiKey.isBlank() || text.isBlank()) {
            onResult(null)
            return
        }
        val system = "You are a translation engine. Translate the user's message into " +
            "$targetLanguageEnglishName. Reply with ONLY the translation — no quotes, " +
            "no notes, no original text."
        val payload = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", text))
            )
            .toString()

        val request = Request.Builder()
            .url("https://$baseHost/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody(JSON))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "translate failed", e)
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = resp.body?.string()
                    if (!resp.isSuccessful || body == null) {
                        Log.w(TAG, "translate HTTP ${resp.code}: ${body?.take(200)}")
                        onResult(null)
                        return
                    }
                    val translation = try {
                        JSONObject(body)
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                            .trim()
                    } catch (e: Exception) {
                        Log.w(TAG, "translate parse error", e)
                        null
                    }
                    onResult(translation)
                }
            }
        })
    }
}

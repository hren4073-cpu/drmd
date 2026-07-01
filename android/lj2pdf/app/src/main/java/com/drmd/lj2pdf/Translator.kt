package com.drmd.lj2pdf

import org.json.JSONArray
import org.json.JSONObject

/**
 * Optional machine translation of foreign posts via an EXTERNAL API (the user
 * configures the endpoint in settings). Two dialects out of the box:
 *  - "libre"  → LibreTranslate: POST {q, source:"auto", target, api_key} → {translatedText}
 *  - "deepl"  → DeepL: POST {text, target_lang, auth_key}                 → {translations:[{text}]}
 * "custom" is treated as LibreTranslate-compatible. Translation is off unless
 * enabled; downloads of foreign sites are slower when it is on (as expected).
 */
object Translator {

    data class Config(
        val on: Boolean,
        val target: String,
        val endpoint: String,
        val key: String,
        val engine: String
    ) {
        val active: Boolean get() = on && endpoint.isNotBlank()
    }

    /** Translate [text] to [Config.target]; returns the original on any failure. */
    suspend fun translate(cfg: Config, text: String): String {
        if (!cfg.active || text.isBlank()) return text
        return try {
            when (cfg.engine) {
                "deepl" -> deepl(cfg, text)
                else -> libre(cfg, text)
            } ?: text
        } catch (_: Throwable) { text }
    }

    private suspend fun libre(cfg: Config, text: String): String? {
        val payload = JSONObject()
            .put("q", text).put("source", "auto").put("target", cfg.target)
            .put("format", "text")
        if (cfg.key.isNotBlank()) payload.put("api_key", cfg.key)
        val resp = Http.postJson(cfg.endpoint, payload.toString()) ?: return null
        return JSONObject(resp).optString("translatedText").ifBlank { null }
    }

    private suspend fun deepl(cfg: Config, text: String): String? {
        val payload = JSONObject()
            .put("text", JSONArray().put(text))
            .put("target_lang", cfg.target.uppercase())
        val headers = if (cfg.key.isNotBlank())
            mapOf("Authorization" to "DeepL-Auth-Key ${cfg.key}") else emptyMap()
        val resp = Http.postJson(cfg.endpoint, payload.toString(), headers) ?: return null
        val arr = JSONObject(resp).optJSONArray("translations") ?: return null
        return if (arr.length() > 0) arr.getJSONObject(0).optString("text").ifBlank { null } else null
    }
}

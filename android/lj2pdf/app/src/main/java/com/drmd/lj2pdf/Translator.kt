package com.drmd.lj2pdf

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Optional machine translation of foreign posts. Engines:
 *  - "mlkit"  → LOCAL, on-device (Google ML Kit) — offline, free, no endpoint;
 *               downloads a language model once, then works without internet.
 *  - "libre"  → LibreTranslate cloud: POST {q, source:"auto", target, api_key}.
 *  - "deepl"  → DeepL cloud: POST {text, target_lang, auth_key}.
 *  - "custom" → LibreTranslate-compatible endpoint.
 * Off unless enabled; foreign-site downloads are slower when on (as expected).
 */
object Translator {

    data class Config(
        val on: Boolean,
        val target: String,
        val endpoint: String,
        val key: String,
        val engine: String
    ) {
        val active: Boolean
            get() = on && (engine == "mlkit" || endpoint.isNotBlank())
    }

    // Cache one ML Kit translator per source→target pair (creation is costly).
    private val mlClients = ConcurrentHashMap<String, com.google.mlkit.nl.translate.Translator>()

    /** Translate [text] to [Config.target]; returns the original on any failure. */
    suspend fun translate(cfg: Config, text: String): String {
        if (!cfg.active || text.isBlank()) return text
        return try {
            when (cfg.engine) {
                "mlkit" -> mlkit(cfg, text)
                "deepl" -> deepl(cfg, text)
                else -> libre(cfg, text)
            } ?: text
        } catch (_: Throwable) { text }
    }

    private suspend fun mlkit(cfg: Config, text: String): String? {
        val srcTag = try {
            LanguageIdentification.getClient().identifyLanguage(text.take(600)).await()
        } catch (_: Throwable) { null } ?: return null
        if (srcTag == "und") return text                      // undetermined → keep
        val src = TranslateLanguage.fromLanguageTag(srcTag) ?: return null
        val tgt = TranslateLanguage.fromLanguageTag(cfg.target) ?: return null
        if (src == tgt) return text
        val client = mlClients.getOrPut("$src>$tgt") {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build()
            )
        }
        client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return client.translate(text).await()
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

package com.mariocjun.algoviz.ai

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.mariocjun.algoviz.BuildConfig
import java.io.IOException
import java.net.UnknownHostException

/**
 * Outcome of an AI explanation request.
 *
 * Modelled as a sealed type (instead of a bare String) so the UI can render
 * THREE visually distinct states — success, a friendly "no API key" onboarding
 * nudge, and a genuine error — without string-sniffing the message. This is the
 * contract that lets VizActivity satisfy Nielsen heuristic #9 (errors must look
 * like errors, never like success). See STD-1.
 */
sealed interface AiResult {
    /** The model answered. [text] is human-readable explanation copy. */
    data class Success(val text: String) : AiResult

    /**
     * A real failure the user should see as an *error* (own icon/colour/title).
     * [kind] lets the UI pick a human, situation-specific message.
     */
    data class Error(val kind: ErrorKind, val detail: String? = null) : AiResult

    /**
     * No API key configured. NOT a crude error — the UI frames this as friendly
     * onboarding ("configure GOOGLE_AI_API_KEY…"), an instruction, not a fault.
     */
    data object MissingApiKey : AiResult
}

/** Coarse failure buckets, each mapped to its own human message in the UI. */
enum class ErrorKind {
    /** No / lost internet — the model is a network service. */
    OFFLINE,
    /** Anything else: API rejected the request, timed out, parse error, etc. */
    GENERIC,
}

/**
 * GeminiAssistant provides an interface to interact with Google's Gemini AI.
 * It uses the GOOGLE_AI_API_KEY from BuildConfig, which is populated from secrets.properties.
 */
class GeminiAssistant {
    private val apiKey = BuildConfig.GOOGLE_AI_API_KEY

    private val model = GenerativeModel(
        modelName = "gemini-1.5-flash",
        apiKey = apiKey
    )

    /**
     * Checks if the API Key is valid (not empty).
     */
    fun hasValidApiKey(): Boolean = apiKey.isNotEmpty()

    /**
     * Generates an explanation for a given algorithm name.
     *
     * Returns a typed [AiResult] so the caller can render success and the
     * various failure modes as visually distinct states. Network failures are
     * classified to [ErrorKind.OFFLINE] so the UI can say "needs internet"
     * instead of leaking a raw stack message.
     */
    suspend fun explainAlgorithm(algorithmName: String): AiResult {
        if (!hasValidApiKey()) {
            return AiResult.MissingApiKey
        }

        return try {
            val response = model.generateContent(
                content {
                    text("Explique de forma concisa e técnica o algoritmo de ordenação: $algorithmName. " +
                            "Foque na complexidade, funcionamento básico e uma curiosidade interessante. " +
                            "Use tom profissional e polido (Apple-grade).")
                }
            )
            val text = response.text
            if (text.isNullOrBlank()) {
                AiResult.Error(ErrorKind.GENERIC)
            } else {
                AiResult.Success(text)
            }
        } catch (e: UnknownHostException) {
            // DNS lookup failed → almost always no connectivity.
            AiResult.Error(ErrorKind.OFFLINE, e.localizedMessage)
        } catch (e: IOException) {
            // Socket/timeout/connection-reset — treat as a connectivity problem.
            AiResult.Error(ErrorKind.OFFLINE, e.localizedMessage)
        } catch (e: Exception) {
            // The Gemini SDK wraps transport faults in its own exception types;
            // sniff the message for the usual offline signatures, else generic.
            val msg = (e.localizedMessage ?: e.message).orEmpty()
            val looksOffline = listOf(
                "unable to resolve host", "failed to connect", "network",
                "timeout", "timed out", "unreachable", "connection",
            ).any { msg.contains(it, ignoreCase = true) }
            AiResult.Error(if (looksOffline) ErrorKind.OFFLINE else ErrorKind.GENERIC, msg)
        }
    }
}

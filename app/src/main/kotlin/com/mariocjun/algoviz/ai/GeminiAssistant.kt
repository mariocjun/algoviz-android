package com.mariocjun.algoviz.ai

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.mariocjun.algoviz.BuildConfig

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
     */
    suspend fun explainAlgorithm(algorithmName: String): String {
        if (!hasValidApiKey()) {
            return "Erro: API Key não configurada no secrets.properties."
        }

        return try {
            val response = model.generateContent(
                content {
                    text("Explique de forma concisa e técnica o algoritmo de ordenação: $algorithmName. " +
                            "Foque na complexidade, funcionamento básico e uma curiosidade interessante. " +
                            "Use tom profissional e polido (Apple-grade).")
                }
            )
            response.text ?: "Não foi possível obter uma explicação no momento."
        } catch (e: Exception) {
            "Erro ao conectar com Gemini: ${e.localizedMessage}"
        }
    }
}

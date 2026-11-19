package com.example.voiceflow.ai

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

sealed interface RefinedTextResult {
    data class Success(val refinedText: String) : RefinedTextResult
    data class Fallback(val rawText: String, val reason: String) : RefinedTextResult
}

interface TextRefinerGateway {
    suspend fun refineText(rawText: String): RefinedTextResult
}

class GeminiAiTextRefiner : TextRefinerGateway {

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            systemInstruction = content {
                text(SYSTEM_INSTRUCTION)
            },
        )
    }

    override suspend fun refineText(rawText: String): RefinedTextResult {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) {
            return RefinedTextResult.Fallback(trimmed, "Boş metin")
        }

        return try {
            val response = withTimeout(TIMEOUT_MS) {
                model.generateContent(
                    content {
                        text("Lütfen aşağıdaki dikte edilmiş ham metni düzelt ve sadece düzeltilmiş metni yaz:\n\n$trimmed")
                    },
                )
            }
            val text = response.text?.trim()
            if (!text.isNullOrBlank()) {
                val cleaned = cleanModelOutput(text)
                RefinedTextResult.Success(cleaned)
            } else {
                RefinedTextResult.Fallback(trimmed, "Model boş yanıt döndürdü")
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Yapay zeka metin düzeltme zaman aşımına uğradı, ham metin kullanılacak.", e)
            RefinedTextResult.Fallback(trimmed, "Zaman aşımı")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Yapay zeka metin düzeltme hatası, ham metin kullanılacak.", e)
            RefinedTextResult.Fallback(trimmed, e.localizedMessage ?: "Bağlantı hatası")
        }
    }

    private fun cleanModelOutput(text: String): String {
        var result = text.trim()
        if (result.startsWith("```") && result.endsWith("```")) {
            result = result.removeSurrounding("```").trim()
            if (result.startsWith("text", ignoreCase = true)) {
                result = result.substring(4).trim()
            }
        }
        if (result.startsWith("\"") && result.endsWith("\"") && result.length >= 2) {
            result = result.removeSurrounding("\"").trim()
        }
        return result
    }

    companion object {
        private const val TAG = "GeminiAiTextRefiner"
        private const val TIMEOUT_MS = 25_000L

        private const val SYSTEM_INSTRUCTION =
            "Sen uzman bir Türkçe dikte editörüsün. " +
                "Kullanıcının mikrofona serbestçe, dağınık, devrik veya duraksayarak söylediği ham metni anlamını ve duygusunu hiç bozmadan akıcı, doğal, temiz ve dilbilgisi kurallarına uygun bir Türkçe metne dönüştür. " +
                "Konuşma tanıma hatalarını, yanlış algılanmış veya saçma duyulan kelimeleri cümlenin genel bağlamına göre akıllıca düzelt. " +
                "Kullanıcının asıl anlatmak istediği mesajı eksiksiz koru. " +
                "Noktalama işaretlerini (nokta, virgül, soru işareti vb.) ve büyük-küçük harf düzenini kusursuz uygula. " +
                "SADECE ve YALNIZCA düzeltilmiş nihai Türkçe metni döndür. Başına veya sonuna hiçbir açıklama, selamlama, tırnak işareti veya ek not koyma."
    }
}

package com.enftc.hrbuzz

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerationConfig
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelConfig
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

/**
 * The model on the phone: Gemma 4 E4B from the AICore developer preview ("nano-v4-full"),
 * through Google's ML Kit GenAI Prompt API. [OnDevice] finds this class by name; builds
 * without it have no on-device coach. Every call blocks, so callers use the worker thread.
 * Thinking stays off: it makes a plan three to four times slower and no better.
 */
class MlKitEngine : OnDevice.Engine {

    private val model: GenerativeModel by lazy {
        Generation.getClient(GenerationConfig.Builder().apply {
            modelConfig = ModelConfig.Builder().apply {
                releaseStage = ModelReleaseStage.PREVIEW
                preference = ModelPreference.FULL
            }.build()
        }.build())
    }

    @Volatile
    private var name = ""

    override fun status(): Int = blocking {
        val status = model.checkStatus()
        if (name.isEmpty()) {
            name = try { model.getBaseModelName() } catch (e: Exception) { "" }
        }
        status
    }

    override fun download() = blocking {
        model.download().collect {
            if (it is DownloadStatus.DownloadFailed) throw it.e
        }
    }

    override fun generate(system: String, user: String, maxTokens: Int): String = blocking {
        val request = GenerateContentRequest.Builder(SystemInstruction(system), TextPart(user))
            .apply {
                maxOutputTokens = maxTokens
                temperature = 0.2f
            }.build()
        model.generateContent(request).candidates.firstOrNull()?.text ?: ""
    }

    override fun modelName(): String = name

    private fun <T> blocking(block: suspend () -> T): T = try {
        runBlocking { block() }
    } catch (e: GenAiException) {
        throw OnDevice.Failure(e.errorCode, e.message ?: "AICore error ${e.errorCode}")
    } catch (e: OnDevice.Failure) {
        throw e
    } catch (e: Exception) {
        throw OnDevice.Failure(0, e.message ?: e.javaClass.simpleName)
    }
}

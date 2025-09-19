package com.kotlindocs.backend.rag

import ai.grazie.api.gateway.client.SuspendableAPIGatewayClient
import ai.grazie.emb.DoubleTextEmbedding
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import kotlinx.coroutines.runBlocking

@Service
class EmbeddingService(
    private val apiClient: SuspendableAPIGatewayClient,
    @Value("\${rag.embeddings.model:sentence-transformers/multi-qa-mpnet-base-dot-v1}")
    private val model: String,
) {
    private val logger = LoggerFactory.getLogger(EmbeddingService::class.java)

    fun embed(texts: List<String>): List<DoubleArray> {
        if (texts.isEmpty()) return emptyList()
        return texts.map { text ->
            try {
                embed(text)
            } catch (t: Throwable) {
                throw RuntimeException("Embedding failure: ${'$'}{t.message}", t)
            }
        }
    }

    fun embed(text: String): DoubleArray {
        return try {
            val result: DoubleTextEmbedding = runBlocking {
                apiClient.meta().emb().embed(text = text, model = model)
            }
            result.values
        } catch (t: Throwable) {
            logger.error("Embedding API error: {}", t.message)
            throw RuntimeException("Failed to get embedding: ${'$'}{t.message}", t)
        }
    }
}

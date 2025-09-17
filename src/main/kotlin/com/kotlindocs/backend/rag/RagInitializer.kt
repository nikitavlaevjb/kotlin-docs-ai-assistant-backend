package com.kotlindocs.backend.rag

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class RagInitializer(
    private val ragService: RagService,
) {
    private val logger = LoggerFactory.getLogger(RagInitializer::class.java)

    @PostConstruct
    fun init() {
        // Ensure all unindexed URLs (markdown pages) are indexed on startup
        try {
            ragService.getRelevantDocsContext("initialize index", null)
            logger.info("RAG initializer: ensured all unindexed URLs are indexed on startup")
        } catch (t: Throwable) {
            logger.warn("RAG initializer: failed to index all documents on startup: {}", t.message)
        }
    }
}

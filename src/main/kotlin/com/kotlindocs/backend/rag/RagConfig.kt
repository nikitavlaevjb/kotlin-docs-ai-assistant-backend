package com.kotlindocs.backend.rag

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RagConfig {
    @Bean
    fun luceneVectorStore(
        @Value("\${rag.lucene.index-path:./lucene-index}") indexPath: String,
    ): LuceneVectorStore = LuceneVectorStore(indexPath)
}

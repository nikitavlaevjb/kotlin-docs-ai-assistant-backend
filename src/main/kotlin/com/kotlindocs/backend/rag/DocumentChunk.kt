package com.kotlindocs.backend.rag

data class DocumentChunk(
    val sourceUrl: String,
    val localPath: String,
    val title: String,
    val section: String?,
    val chunkIndex: Int,
    val text: String,
    val embedding: DoubleArray
)

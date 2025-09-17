package com.kotlindocs.backend.rag

import com.kotlindocs.backend.services.PagesService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

@Service
class RagService(
    private val pagesService: PagesService,
    private val embeddingService: EmbeddingService,
    private val vectorStore: LuceneVectorStore,
    @Value("\${rag.chunk-size:1000}") private val chunkSize: Int,
    @Value("\${rag.chunk-overlap:200}") private val overlap: Int,
    @Value("\${rag.max-results:5}") private val maxResults: Int,
) {
    private val logger = LoggerFactory.getLogger(RagService::class.java)

    private val indexed: ConcurrentHashMap<String, Boolean> = ConcurrentHashMap()

    init {
        // Preload 'indexed' map with already indexed local paths from Lucene index
        try {
            val preIndexed = vectorStore.listDistinctMetaValues("localPath")
            if (preIndexed.isNotEmpty()) {
                preIndexed.forEach { path -> indexed[path] = true }
                logger.info("Preloaded {} indexed documents from Lucene index", preIndexed.size)
            } else {
                logger.info("No pre-indexed documents found in Lucene index on init")
            }
        } catch (t: Throwable) {
            logger.warn("Failed to preload indexed documents from Lucene: {}", t.message)
        }
    }

    fun getRelevantDocsContext(query: String, url: String?): String {
        if (url.isNullOrBlank()) {
            // No URL specified: ensure global index exists
            indexAllIfNeeded()
        } else {
            val docsPath = URI.create(url).path
            val localPath = docsPath.removeSuffix(".html").removePrefix("/") + ".md"
            indexIfNeeded(url, localPath)
        }

        val queryEmbedding = try { embeddingService.embed(query) } catch (t: Throwable) {
            throw RuntimeException("Failed to embed query: ${'$'}{t.message}", t)
        }

        val whereFilter: Map<String, Any?>? = if (!url.isNullOrBlank()) {
            val docsPath = URI.create(url).path
            val localPath = docsPath.removeSuffix(".html").removePrefix("/") + ".md"
            mapOf("localPath" to localPath)
        } else null

        val result = vectorStore.queryByEmbedding(queryEmbedding, maxResults, whereFilter)

        val chunks = result.documents.indices.map { i ->
            val meta = result.metadatas.getOrNull(i) ?: emptyMap()
            DocumentChunk(
                sourceUrl = (meta["sourceUrl"] as? String) ?: (meta["source_url"] as? String) ?: "",
                localPath = (meta["localPath"] as? String) ?: (meta["local_path"] as? String) ?:"",
                title = (meta["title"] as? String) ?: "Kotlin Docs",
                section = (meta["section"] as? String),
                chunkIndex = (meta["chunkIndex"] as? Number)?.toInt() ?: i,
                text = result.documents[i],
                embedding = DoubleArray(0) // not needed for formatting
            )
        }

        if (chunks.isEmpty()) return ""
        return formatContext(chunks)
    }

    private fun indexIfNeeded(sourceUrl: String?, localPath: String) {
        if (indexed.containsKey(localPath)) return
        synchronized(indexed) {
            if (indexed.containsKey(localPath)) return
            try {
                val md = pagesService.readTextFile(localPath)
                    ?: throw IllegalStateException("Failed to fetch page content for $localPath")
                val title = extractTitle(md)
                val sections = extractSections(md)
                val chunks = splitIntoChunks(md, chunkSize, overlap, sections)
                val vectors = embeddingService.embed(chunks.map { it.second })
                val defaultSourceUrl = "https://kotlinlang.org/docs/" + localPath.removeSuffix(".md") + ".html"
                val finalSourceUrl = sourceUrl ?: defaultSourceUrl

                val items = chunks.mapIndexed { i, (section, text) ->
                    val id = "$localPath#$i"
                    LuceneVectorStore.UpsertItem(
                        id = id,
                        embedding = vectors[i],
                        document = text,
                        metadata = mapOf(
                            "sourceUrl" to finalSourceUrl,
                            "localPath" to localPath,
                            "title" to title,
                            "section" to section,
                            "chunkIndex" to i
                        )
                    )
                }
                vectorStore.upsert(items)
                indexed[localPath] = true
            } catch (t: Throwable) {
                logger.error("Indexing failed for {}: {}", localPath, t.message)
                throw RuntimeException("Failed to index document: ${t.message}", t)
            }
        }
    }

    private fun indexAllIfNeeded() {
        val allFiles = try { pagesService.listMarkdownFiles() } catch (_: Throwable) { emptyList() }
        if (allFiles.isEmpty()) return
        for (path in allFiles) {
            if (!indexed.containsKey(path)) {
                try {
                    indexIfNeeded(null, path)
                    logger.info("Indexed {}", path)
                } catch (t: Throwable) {
                    logger.warn("Skipping {} due to indexing error: {}", path, t.message)
                }
            }
        }
    }

    private fun formatContext(chunks: List<DocumentChunk>): String {
        val sb = StringBuilder()
        sb.appendLine("Retrieved documentation context (top ${chunks.size}):")
        chunks.forEach { c ->
            sb.appendLine("\n---\n")
            val sectionInfo = c.section?.let { " - Section: $it" } ?: ""
            sb.appendLine("Source: ${c.sourceUrl}$sectionInfo")
            sb.appendLine("Title: ${c.title}")
            c.section?.let { sb.appendLine("## $it") }
            sb.appendLine(c.text.trim())
        }
        return sb.toString()
    }

    private fun extractTitle(md: String): String {
        // First level-1 header as title
        val regex = Regex("^#\\s+(.+)$", RegexOption.MULTILINE)
        return regex.find(md)?.groupValues?.get(1)?.trim() ?: "Kotlin Docs"
    }

    private fun extractSections(md: String): List<Pair<Int, String>> {
        // Returns list of (startIndex, sectionName) for '## ' headers
        val regex = Regex("^##\\s+(.+)$", RegexOption.MULTILINE)
        return regex.findAll(md).map { it.range.first to it.groupValues[1].trim() }.toList()
    }

    private fun splitIntoChunks(
        md: String,
        chunkSize: Int,
        overlap: Int,
        sections: List<Pair<Int, String>>
    ): List<Pair<String?, String>> {
        val chunks = mutableListOf<Pair<String?, String>>()
        var start = 0
        val step = (chunkSize - overlap).coerceAtLeast(1)
        while (start < md.length) {
            val end = kotlin.math.min(start + chunkSize, md.length)
            val text = md.substring(start, end)
            val section = currentSectionFor(start, sections)
            chunks.add(section to text)
            if (end == md.length) break
            start += step
        }
        return chunks
    }

    private fun currentSectionFor(pos: Int, sections: List<Pair<Int, String>>): String? {
        var current: String? = null
        for ((idx, name) in sections) {
            if (idx <= pos) current = name else break
        }
        return current
    }
}

package com.kotlindocs.backend.rag

import org.apache.lucene.analysis.core.WhitespaceAnalyzer
import org.apache.lucene.document.*
import org.apache.lucene.index.*
import org.apache.lucene.search.*
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.document.KnnFloatVectorField
import org.apache.lucene.index.VectorSimilarityFunction
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.Closeable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Lucene-based vector store for the RAG pipeline.
 * - Stores vectors using KnnFloatVectorField (DOT_PRODUCT). We normalize vectors to achieve cosine similarity.
 * - Stores document text and metadata as stored fields.
 * - Supports kNN vector search with optional metadata filters (exact match on stored keyword fields).
 * - Thread-safe; maintains a single IndexWriter and SearcherManager.
 */
@Component
class LuceneVectorStore(indexDirPath: String = "./lucene-index") : Closeable {
    private val logger = LoggerFactory.getLogger(LuceneVectorStore::class.java)

    data class UpsertItem(
        val id: String,
        val embedding: DoubleArray,
        val document: String,
        val metadata: Map<String, Any?>,
    )

    data class QueryResult(
        val ids: List<String>,
        val documents: List<String>,
        val metadatas: List<Map<String, Any?>>, 
        val distances: List<Double>?, // scores (cosine similarity proxy)
    )

    private val directory = run {
        val p = Path.of(indexDirPath)
        if (!Files.exists(p)) Files.createDirectories(p)
        FSDirectory.open(p)
    }

    private val analyzer = WhitespaceAnalyzer()
    private val writer: IndexWriter
    private val searcherManager: SearcherManager
    private val rwLock = ReentrantReadWriteLock()

    init {
        val iwc = IndexWriterConfig(analyzer).apply {
            openMode = IndexWriterConfig.OpenMode.CREATE_OR_APPEND
            setUseCompoundFile(true)
        }
        writer = IndexWriter(directory, iwc)
        searcherManager = SearcherManager(writer, null)
    }

    fun upsert(items: List<UpsertItem>) {
        if (items.isEmpty()) return
        rwLock.write {
            try {
                for (item in items) {
                    val doc = toDocument(item)
                    writer.updateDocument(Term(F_ID, item.id), doc)
                }
                writer.commit()
                searcherManager.maybeRefresh()
            } catch (t: Throwable) {
                logger.error("Lucene upsert failed: {}", t.message)
                throw RuntimeException("Lucene upsert failed: ${t.message}", t)
            }
        }
    }

    fun queryByEmbedding(queryEmbedding: DoubleArray, topK: Int, where: Map<String, Any?>? = null): QueryResult {
        val qVec = normalizeToFloat(queryEmbedding)
        rwLock.read {
            var searcher: IndexSearcher? = null
            try {
                searcherManager.maybeRefresh()
                searcher = searcherManager.acquire()
                val filterQuery = buildFilterQuery(where)

                val knnQuery = if (filterQuery != null) {
                    KnnFloatVectorQuery(F_VECTOR, qVec, topK, filterQuery)
                } else {
                    KnnFloatVectorQuery(F_VECTOR, qVec, topK)
                }
                val topDocs = searcher!!.search(knnQuery, topK)
                val ids = mutableListOf<String>()
                val docs = mutableListOf<String>()
                val metas = mutableListOf<Map<String, Any?>>()
                val scores = mutableListOf<Double>()
                for (sd in topDocs.scoreDocs) {
                    val d = searcher.doc(sd.doc)
                    ids.add(d.get(F_ID))
                    docs.add(d.get(F_DOCUMENT) ?: "")
                    metas.add(extractMetadata(d))
                    scores.add(sd.score.toDouble())
                }
                return QueryResult(ids, docs, metas, scores)
            } catch (t: Throwable) {
                logger.error("Lucene query failed: {}", t.message)
                throw RuntimeException("Lucene query failed: ${t.message}", t)
            } finally {
                if (searcher != null) searcherManager.release(searcher)
            }
        }
    }

    private fun toDocument(item: UpsertItem): Document {
        val doc = Document()
        doc.add(StringField(F_ID, item.id, Field.Store.YES))
        // Store text
        doc.add(StoredField(F_DOCUMENT, item.document))
        // Vector field: use DOT_PRODUCT on normalized vectors (cosine similarity)
        val vec = normalizeToFloat(item.embedding)
        doc.add(KnnFloatVectorField(F_VECTOR, vec, VectorSimilarityFunction.DOT_PRODUCT))
        // Metadata: store both for retrieval and as keyword fields for filtering
        item.metadata.forEach { (k, v) ->
            val key = metaField(k)
            val str = (v?.toString() ?: "")
            doc.add(StringField(key, str, Field.Store.YES))
        }
        return doc
    }

    private fun buildFilterQuery(where: Map<String, Any?>?): Query? {
        if (where == null || where.isEmpty()) return null
        val bq = BooleanQuery.Builder()
        for ((k, v) in where) {
            val key = metaField(k)
            val value = v?.toString() ?: ""
            bq.add(TermQuery(Term(key, value)), BooleanClause.Occur.FILTER)
        }
        return bq.build()
    }

    private fun extractMetadata(doc: Document): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        for (f in doc.fields) {
            val name = f.name()
            if (name.startsWith(F_META_PREFIX)) {
                val key = name.removePrefix(F_META_PREFIX)
                val value = doc.get(name)
                map[key] = value
            }
        }
        return map
    }

    private fun normalizeToFloat(vector: DoubleArray): FloatArray {
        var norm = 0.0
        for (v in vector) norm += v * v
        val denom = if (norm > 0.0) kotlin.math.sqrt(norm) else 1.0
        return FloatArray(vector.size) { i -> (vector[i] / denom).toFloat() }
    }

    override fun close() {
        rwLock.write {
            try {
                searcherManager.close()
            } catch (_: IOException) {}
            try {
                writer.close()
            } catch (_: IOException) {}
            try {
                directory.close()
            } catch (_: IOException) {}
        }
    }

    fun listDistinctMetaValues(metaKey: String): Set<String> {
        val results = mutableSetOf<String>()
        val fieldName = metaField(metaKey)
        rwLock.read {
            var searcher: IndexSearcher? = null
            try {
                searcherManager.maybeRefresh()
                searcher = searcherManager.acquire()
                val reader = searcher!!.indexReader
                for (leafCtx in reader.leaves()) {
                    val leaf = leafCtx.reader()
                    val live = leaf.liveDocs
                    val max = leaf.maxDoc()
                    var i = 0
                    while (i < max) {
                        if (live == null || live.get(i)) {
                            val doc = leaf.document(i)
                            val v = doc.get(fieldName)
                            if (v != null) results.add(v)
                        }
                        i++
                    }
                }
            } catch (t: Throwable) {
                logger.warn("Failed to list distinct meta values for {}: {}", metaKey, t.message)
            } finally {
                if (searcher != null) searcherManager.release(searcher)
            }
        }
        return results
    }

    companion object {
        private const val F_ID = "id"
        private const val F_DOCUMENT = "document"
        private const val F_VECTOR = "vector"
        private const val F_META_PREFIX = "meta_"
        private fun metaField(name: String) = F_META_PREFIX + name
    }
}

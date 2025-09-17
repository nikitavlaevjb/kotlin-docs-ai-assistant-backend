package com.kotlindocs.backend.controller

import ai.grazie.api.gateway.client.SuspendableAPIGatewayClient
import ai.grazie.gen.tasks.text.webSearch.WebSearchTaskDescriptor
import ai.grazie.gen.tasks.text.webSearch.WebSearchTaskParams
import ai.grazie.model.llm.data.stream.LLMStreamData
import ai.grazie.model.llm.parameters.LLMConfig
import ai.grazie.model.llm.profile.OpenAIProfileIDs
import ai.grazie.model.llm.prompt.LLMPromptID
import com.kotlindocs.backend.rag.RagService
import com.kotlindocs.backend.services.PagesService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.coyote.BadRequestException
import org.springframework.cache.annotation.Cacheable
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
class SearchController(
    private val apiClient: SuspendableAPIGatewayClient,
    private val ragService: RagService,
    private val pagesService: PagesService,
) {
    companion object Companion {
        val DOMAINS = listOf(
            "https://kotlinlang.org/docs"
        )
    }

    private suspend fun createChatStream(request: ChatRequest): Flow<LLMStreamData> = apiClient.llm().v9().chat(
        prompt = LLMPromptID("kotlin-docs-ai"),
        profile = OpenAIProfileIDs.Chat.GPT5_Mini,
        messages = {
            system(request.systemPrompt)
            user(request.userPrompt)
        },
        parameters = LLMConfig()
    )

    @GetMapping("/websearch")
    fun websearch(
        @RequestParam("query", required = false) queryParam: String?,
        @RequestParam("maxResults", required = false) maxResultsParam: Int?
    ): String {
        val builder = StringBuilder()

        val query = queryParam?.takeIf { it.isNotBlank() } ?: "What is Koog by JetBrains?"
        val maxResults = when {
            maxResultsParam == null -> 5
            maxResultsParam < 1 -> 1
            maxResultsParam > 20 -> 20
            else -> maxResultsParam
        }

        runBlocking {
            val taskParams = WebSearchTaskParams(
                query = query,
                maxResults = maxResults,
                includeAnswer = WebSearchTaskParams.IncludeAnswer.advanced,
                includeDomains = DOMAINS
            )

            val taskCall = WebSearchTaskDescriptor.createCallData(taskParams)
            try {
                val stream = apiClient.tasksWithStreamData().execute(taskCall)
                stream.collect { event ->
                    val content = event.content
                    if (content.isNotEmpty()) {
                        builder.append(content)
                    }
                }
            } catch (t: Throwable) {
                println("Error: ${t.message}")
                // Don't rethrow during tests/no client; return whatever collected (possibly empty)
            }
        }

        // Return the raw JSON string (or empty string if nothing was received)
        return builder.toString()
    }

    data class ChatHTTPRequest(
        val systemPrompt: String? = null,
        val userPrompt: String? = null,
    )

    data class ChatRequest(
        val systemPrompt: String,
        val userPrompt: String,
    ) {
        init {
            require(systemPrompt.isNotBlank()) { "systemPrompt must not be blank" }
            require(userPrompt.isNotBlank()) { "userPrompt must not be blank" }
        }
    }

    private suspend fun collectChatStream(request: suspend () -> ChatRequest): String {
        val builder = StringBuilder()

        try {
            createChatStream(request()).collect {
                val content = it.content
                if (content.isNotEmpty()) builder.append(content)
            }
        } catch (t: Throwable) {
            println("Error: ${t.message}")
        }

        return builder.toString()
    }

    private fun processChatSSE(request: suspend () -> ChatRequest): SseEmitter {
        val emitter = SseEmitter(0L)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                createChatStream(request()).collect { event: LLMStreamData ->
                    try {
                        val content = event.content.ifEmpty { return@collect }
                        val escapedContent = "\\\"$content\\\""
                        val sseEvent = SseEmitter.event().name("message").data(escapedContent)
                        emitter.send(sseEvent)
                    } catch (sendEx: Exception) {
                        emitter.completeWithError(sendEx)
                    }
                }

                try {
                    emitter.send(SseEmitter.event().name("end").data("[DONE]"))
                } catch (_: Exception) {
                }

                emitter.complete()
            } catch (t: Throwable) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(t.message ?: "Unknown error"))
                } catch (_: Exception) {
                }
                emitter.completeWithError(t)
            }
        }

        return emitter
    }

    private suspend fun getRelevantDocsContext(query: String, url: String? = null): String = try {
        ragService.getRelevantDocsContext(query, url)
    } catch (_: Exception) {
        throw BadRequestException("Failed to retrieve relevant documentation context.")
    }

    @PostMapping("/chat")
    fun chat(@RequestBody(required = false) body: ChatHTTPRequest?): String = runBlocking {
        collectChatStream {
            ChatRequest(
                systemPrompt = body?.systemPrompt ?: "",
                userPrompt = body?.userPrompt ?: throw BadRequestException("Request body must contain userPrompt")
            )
        }
    }

    @PostMapping("/chat/stream/sse", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun chatStreamSse(@RequestBody(required = true) body: ChatHTTPRequest?): SseEmitter = processChatSSE {
        ChatRequest(
            systemPrompt = body?.systemPrompt ?: "",
            userPrompt = body?.userPrompt ?: throw BadRequestException("Request body must contain userPrompt")
        )
    }

    data class SummarizeRequest(
        val url: String? = null,
        val systemPrompt: String? = null,
    )

    private suspend fun resolveContextFromUrlOrRag(url: String?, fallbackQuery: String): String {
        return try {
            if (!url.isNullOrBlank()) {
                val path = java.net.URI.create(url).path
                val localPath = path.removeSuffix(".html").removePrefix("/") + ".md"
                pagesService.readTextFile(localPath)
                    ?: throw IllegalStateException("Failed to fetch page content for $localPath")
            } else {
                getRelevantDocsContext(fallbackQuery, null)
            }
        } catch (t: Throwable) {
            throw BadRequestException("Failed to retrieve page content: ${t.message}")
        }
    }

    private suspend fun makeSummarizeRequest(request: SummarizeRequest): ChatRequest {
        val context = resolveContextFromUrlOrRag(request.url, "summarize documentation")

        return ChatRequest(
            // language=markdown
            systemPrompt = request.systemPrompt?.replace("\$content", context) ?: ("""
                You need to summarize the documentation article.
                I will send a link to it. There are some rules you need to follow:
                  * The text shouldn't contain more than two paragraphs.
                  * It shouldn't be a short overview about all the entities mentioned in the article, but a real summary: what the article is about
                  * If there is an important part of the article, you can provide anchor links for the particular section.
                  * If it is a tutorial, you should explain what the tutorial is about and what is the result in the final, not summarizing the steps.
                  * If the article is about the feature: provide a link (or a one-liner code block) how to activate this. 
                  * Use simplified English. Apply Kotlin documentation guidelines.
                  * All links or anchors in the answers should be in a pure Markdown markup.
            """.trimIndent() + "\n\n```markdown\n$context\n```"),
            userPrompt = "Provide a summary of the page."
        )
    }

    @PostMapping("/summarize")
    @Cacheable("summarize")
    fun summary(@RequestBody(required = false) request: SummarizeRequest): String = runBlocking {
        collectChatStream { makeSummarizeRequest(request) }
    }

    @PostMapping("/summarize/stream/sse", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Cacheable("summarize-sse")
    fun summarizeStreamSSE(@RequestBody(required = true) request: SummarizeRequest): SseEmitter =
        processChatSSE { makeSummarizeRequest(request) }

    data class UserMessageRequest(
        val url: String? = null,
        val message: String? = null,
    )

    private suspend fun makeRealWorldExample(request: UserMessageRequest): ChatRequest {
        val message =
            request.message?.takeIf { it.isNotBlank() } ?: throw BadRequestException("Message must not be blank.")

        val context = getRelevantDocsContext(message)

        return ChatRequest(
            // language=markdown
            systemPrompt = """
                You are a Kotlin expert and tutor.
                You need to provide a real-life code example or several (a piece of it) for the piece of text from the official documentation that user selected. 
                Follow the rules:
                 * The answer shouldn't contain up to three codeblocks.
                 * The answer should be concise.
                 * Use official Kotin, JetBrains, or Google repositories for examples or repositories with a number of stars more than 1000.
                 * If there is not enough information, find the sentence in the ${request.url} and try to provide an example. If there is not enough information, the answer should explain the lack of context. Kindly ask to extend the selection or make another choice.
                 * For the text, use simplified English. Apply Kotlin documentation guidelines.
                 * Examples should be small, concise, and straight to the point.
                 * If you want to provide a link or anchor, do it in a pure Markdown markup.
                
                Selected page content:
            """.trimIndent() + "\n\n```markdown\n$context\n```",
            userPrompt = message,
        )
    }

    @PostMapping("/realWorldExample")
    fun realWorldExample(@RequestBody(required = false) request: UserMessageRequest): String = runBlocking {
        collectChatStream { makeRealWorldExample(request) }
    }

    @PostMapping("/realWorldExample/stream/sse", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun realWorldExampleSSE(@RequestBody(required = true) request: UserMessageRequest): SseEmitter =
        processChatSSE { makeRealWorldExample(request) }

    private suspend fun makeExplainInSimpleWords(request: UserMessageRequest): ChatRequest {
        val message =
            request.message?.takeIf { it.isNotBlank() } ?: throw BadRequestException("Message must not be blank.")

        val context = resolveContextFromUrlOrRag(request.url, message)

        return ChatRequest(
            // language=markdown
            systemPrompt = """
                You are a Kotlin expert and tutor.
                You need to explain the piece of text from the official documentation that user selected. You should do this in simple terms. 
                Follow the rules:
                 * The text shouldn't contain more than two paragraphs.
                 * If there is not enough information, find the sentence in the ${request.url} and try to explain in simpler words.
                 *  If a user selected an article or a word that is not a term, the answer should explain lack of context. Kindly ask to extend the selection or make another choice.
                 * Use simplified English. Apply Kotlin documentation guidelines.
                 * If it is possible to provide a small example, provide it. It should be concise and straight to the point.
                 * Use only simple terms, like the user has no strong knowledge of programming.
                 * Do not provide any links in the answer.
                
                Selected page content:
            """.trimIndent() + "\n\n```markdown\n$context\n```",
            userPrompt = message,
        )
    }

    @PostMapping("/explainInSimpleWords")
    fun explainInSimpleWords(@RequestBody(required = false) request: UserMessageRequest): String = runBlocking {
        collectChatStream { makeExplainInSimpleWords(request) }
    }

    @PostMapping("/explainInSimpleWords/stream/sse")
    fun explainInSimpleWordsSSE(@RequestBody(required = false) request: UserMessageRequest): SseEmitter =
        processChatSSE { makeExplainInSimpleWords(request) }

    data class BackgroundTransformRequest(
        val url: String,
        val language: String,
        val background: String,
    )

    @PostMapping("/backgroundTransform")
    fun backgroundTransform(@RequestBody(required = true) request: BackgroundTransformRequest): String = runBlocking {
        collectChatStream {
            ChatRequest(
                systemPrompt = """
                I will provide three paramteres:
                * page URL: ${'$'}url
                * programming language: ${'$'}language
                * development background description (such as mobile developer, backend, desktop, etc.): ${'$'}background
                
                
                Based on these parameters, explain me that ${'$'}url page if I was:
                * a developer with ${'$'}language knowledge
                * I mostly develop a ${'$'}background
                
                Article should follow the rules:
                
                * Do not add an intro about this page
                * Use Simple English, apply Kotlin documentation styleguides to the text, keep the style of the original article
                * The article should have the same structure
                * In each section (or after explaining a concepts) add some practical notes for the ${'$'}background if there is any
                * The Kotlin codeblocks should remain the same
                * If there is a adjacent topic to the concept explaining (for example, the ${'$'}language has a common/standard library or related language concept), mentioned them so I will be acknowledged the difference in Kotlin and ${'$'}language
                * Don’t hesitate to expand the each section with some important details and comparisons, but they should be concise
                * At the end of the article, but before the Next step section, provide an additional section that emphasised the key aspects and differences I need to know that explained on the page. Keep it concise, it could be a table with comparison
                * Do not change the Next step section at all
                * Output should be in Markdown format
                """,
                userPrompt = """
                    Here are parameters:
                    1. ${request.url}
                    2. ${request.language}
                    3. ${request.background}
                """.trimIndent()
            )
        }
    }
}

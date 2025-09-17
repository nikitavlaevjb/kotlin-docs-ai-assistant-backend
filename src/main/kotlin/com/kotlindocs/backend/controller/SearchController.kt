package com.kotlindocs.backend.controller

import ai.grazie.api.gateway.client.SuspendableAPIGatewayClient
import ai.grazie.gen.tasks.text.summarize.TextSummarizeTaskDescriptor
import ai.grazie.gen.tasks.text.summarize.TextSummarizeTaskParams
import ai.grazie.gen.tasks.text.webSearch.WebSearchTaskDescriptor
import ai.grazie.gen.tasks.text.webSearch.WebSearchTaskParams
import ai.grazie.model.llm.data.stream.LLMStreamData
import ai.grazie.model.llm.parameters.LLMConfig
import ai.grazie.model.llm.profile.OpenAIProfileIDs
import ai.grazie.model.llm.prompt.LLMPromptID
import com.kotlindocs.backend.services.PagesService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.coyote.BadRequestException
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.net.URI

@RestController
class SearchController(
    private val apiClient: SuspendableAPIGatewayClient,
    private val pagesService: PagesService

) {
    companion object Companion {
        val DOMAINS = listOf<String>(
            "https://kotlinlang.org/docs"
        )
    }

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

    data class ChatRequest(
        val systemPrompt: String? = null,
        val userPrompt: String? = null,
//        val temperature: Double? = null,
    )

    private suspend fun processChatRequest(body: ChatRequest?): String {
        val builder = StringBuilder()

        val systemText = body?.systemPrompt?.takeIf { it.isNotBlank() } ?: "You are a helpful assistant"
        val userText = body?.userPrompt?.takeIf { it.isNotBlank() } ?: "Say hello in one short sentence."
//        val temperature = when (val t = body?.temperature) {
//            null -> 0.6
//            else -> when {
//                t < 0.0 -> 0.0
//                t > 2.0 -> 2.0
//                else -> t
//            }
//        }

        try {
            val chatResponseStream = apiClient.llm().v9().chat(
                prompt = LLMPromptID("kotlin-docs-ai"),
                profile = OpenAIProfileIDs.Chat.GPT5_Mini,
                messages = {
                    system(systemText)
                    user(userText)
                },
                parameters = LLMConfig(
//                        temperature = temperature
                )
            )
            chatResponseStream.collect {
                val content = it.content
                if (content.isNotEmpty()) builder.append(content)
            }
        } catch (t: Throwable) {
            println("Error: ${t.message}")
            // Don't rethrow during tests/no client; return whatever collected
        }
        return builder.toString()
    }

    private suspend fun getDocsPageContent(url: String?): String = try {
        val docsUrl = url?.takeIf { it.isNotBlank() }?.let { URI.create(it)?.path }
            ?: throw BadRequestException("URL must be a valid Kotlin docs page")

        val localPath = "${docsUrl.removeSuffix(".html").removePrefix("/")}.md"

        pagesService.readTextFile(localPath) ?: throw Exception("Failed to fetch page content.")
    } catch (_: Exception) {
        throw BadRequestException("Failed to fetch page content.")
    }

    @PostMapping("/chat")
    fun chat(@RequestBody(required = false) body: ChatRequest?): String = runBlocking {
        processChatRequest(body)
    }

    @PostMapping("/chat/stream/sse", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun chatStreamSse(@RequestBody(required = true) body: ChatRequest): SseEmitter {
        val emitter = SseEmitter(0L)
        val systemText = body.systemPrompt?.takeIf { it.isNotBlank() } ?: "You are a helpful assistant"
        val userText = body.userPrompt?.takeIf { it.isNotBlank() } ?: "Say hello in one short sentence."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val chatResponseStream = apiClient.llm().v9().chat(
                    prompt = LLMPromptID("kotlin-docs-ai"),
                    profile = OpenAIProfileIDs.Chat.GPT5_Mini,
                    messages = {
                        system(systemText)
                        user(userText)
                    },
                    parameters = LLMConfig(
                    )
                )

                chatResponseStream.collect { event: LLMStreamData ->
                    val content = event.content
                    if (content.isNotEmpty()) {
                        val escapedContent = "\\\"$content\\\""
                        try {
                            emitter.send(SseEmitter.event().name("message").data(escapedContent))
                        } catch (sendEx: Exception) {
                            // Client might have disconnected
                            emitter.completeWithError(sendEx)
                            return@collect
                        }
                    }
                }
                // Signal completion
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

    data class SummarizeByWordsRequest(
        val url: String,
        val size: String = "20%",
    )

    @PostMapping("/summarizeByWords")
    fun summary(@RequestBody(required = false) request: SummarizeByWordsRequest): String {
        val builder = StringBuilder()

        runBlocking {
            val content = getDocsPageContent(request.url)

            val size = when {
                request.size.endsWith("%") -> request.size.removeSuffix("%").toIntOrNull()?.let { percent ->
                    calculateTextSize(content) * percent / 100
                }

                else -> request.size.toIntOrNull()
            } ?: throw BadRequestException("Size must be a percentage (e.g. 20%) or integer")

            apiClient.tasksWithStreamData().execute(
                TextSummarizeTaskDescriptor.createCallData(
                    TextSummarizeTaskParams(
                        text = content, words = size, lang = "English"
                    )
                )
            ).collect {
                val content = it.content
                if (content.isNotEmpty()) builder.append(content)
            }
        }

        return builder.toString()
    }

    data class SummarizeRequest(
        val url: String? = null,
        val systemPrompt: String? = null,
    )

    @PostMapping("/summarize")
    fun summary(@RequestBody(required = false) request: SummarizeRequest): String = runBlocking {
        val content = getDocsPageContent(request.url)

        val chatRequest = ChatRequest(
            // language=markdown
            systemPrompt = request.systemPrompt?.replace("\$content", content) ?: ("""
                You need to summarize the documentation article.
                I will send a link to it. There are some rules you need to follow:
                  * The text shouldn't contain more than two paragraphs.
                  * It shouldn't be a short overview about all the entities mentioned in the article, but a real summary: what the article is about
                  * If there is an important part of the article, you can provide anchor links for the particular section.
                  * If it is a tutorial, you should explain what the tutorial is about and what is the result in the final, not summarizing the steps.
                  * If the article is about the feature: provide a link (or a one-liner code block) how to activate this. 
                  * Use simplified English. Apply Kotlin documentation guidelines.
                  * All links or anchors in the answers should be in a pure Markdown markup.
            """.trimIndent() + "\n\n```markdown\n$content\n```"),
            userPrompt = "Provide a summary of the page."
        )

        processChatRequest(chatRequest)
    }

    data class UserMessageRequest(
        val url: String? = null,
        val message: String? = null,
    )

    @PostMapping("/realWorldExample")
    fun realWorldExample(@RequestBody(required = false) request: UserMessageRequest): String = runBlocking {
        val content = getDocsPageContent(request.url)

        val message =
            request.message?.takeIf { it.isNotBlank() } ?: throw BadRequestException("Message must not be blank.")

        val chatRequest = ChatRequest(
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
            """.trimIndent() + "\n\n```markdown\n$content\n```",
            userPrompt = message,
        )

        processChatRequest(chatRequest)
    }

    @PostMapping("/explainInSimpleWords")
    fun explainInSimpleWords(@RequestBody(required = false) request: UserMessageRequest): String = runBlocking {
        val content = getDocsPageContent(request.url)

        val message =
            request.message?.takeIf { it.isNotBlank() } ?: throw BadRequestException("Message must not be blank.")

        val chatRequest = ChatRequest(
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
            """.trimIndent() + "\n\n```markdown\n$content\n```",
            userPrompt = message,
        )

        processChatRequest(chatRequest)
    }
}

private fun calculateTextSize(text: String): Int = text
    .split(Regex("(?U)\\s+"))
    .filter { it.isNotBlank() && it.contains(Regex("\\p{L}")) }
    .size

package com.kotlindocs.backend.controller

import ai.grazie.api.gateway.client.SuspendableAPIGatewayClient
import ai.grazie.gen.tasks.text.summarize.TextSummarizeTaskDescriptor
import ai.grazie.gen.tasks.text.summarize.TextSummarizeTaskParams
import ai.grazie.gen.tasks.text.webSearch.WebSearchTaskDescriptor
import ai.grazie.gen.tasks.text.webSearch.WebSearchTaskParams
import ai.grazie.model.llm.parameters.LLMConfig
import ai.grazie.model.llm.profile.OpenAIProfileIDs
import ai.grazie.model.llm.prompt.LLMPromptID
import com.kotlindocs.backend.services.PagesService
import kotlinx.coroutines.runBlocking
import org.apache.coyote.BadRequestException
import org.springframework.web.bind.annotation.*
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

    private fun processChatRequest(body: ChatRequest?): String {
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

        runBlocking {
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
        }
        return builder.toString()
    }

    @PostMapping("/chat")
    fun chat(@RequestBody(required = false) body: ChatRequest?): String = processChatRequest(body)

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
        val systemPrompt: String? = null,
        val url: String? = null,
    )

    @PostMapping("/summarize")
    fun summary(@RequestBody(required = false) request: SummarizeRequest): String {
        val content = runBlocking {
            getDocsPageContent(request.url)
        }

        val summaryRequest = ChatRequest(
            // @language=markdown
            systemPrompt = request.systemPrompt?.replace("\$content", content) ?: ("""
                You need to summarize the documentation article.
                I will send a link to it. There are some rules you need to follow:
                  * The text shouldn't contain more than two paragraphs.
                  * It shouldn't be a short overview about all the entities mentioned in the article, but a real summary: what the article is about
                  * If there is an important part of the article, you can provide anchor links for the particular section.
                  * If it is a tutorial, you should explain what is the tutorial about and what is the result in the final, not summarizing the steps.
                  * If the article about the feature: provide a link (or a one-liner code block) how to activate this.
                  * Use simplified English. Apply Kotlin documentation guidelines.
            """.trimIndent() + "\n\n```markdown\n$content\n```"),
            userPrompt = "Provide a summary of the page."
        )

        return processChatRequest(summaryRequest);
    }

    private fun getDocsPageContent(url: String?): String = try {
        val docsUrl = url?.takeIf { it.isNotBlank() }?.let { URI.create(it)?.path }
            ?: throw BadRequestException("URL must be a valid Kotlin docs page")

        val localPath = "${docsUrl.removeSuffix(".html").removePrefix("/")}.md"

        pagesService.readTextFile(localPath) ?: throw Exception("Failed to fetch page content.")
    } catch (_: Exception) {
        throw BadRequestException("Failed to fetch page content.")
    }

}

private fun calculateTextSize(text: String): Int = text
    .split(Regex("(?U)\\s+"))
    .filter { it.isNotBlank() && it.contains(Regex("\\p{L}")) }
    .size

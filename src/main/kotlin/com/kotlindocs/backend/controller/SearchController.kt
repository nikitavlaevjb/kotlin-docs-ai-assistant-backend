package com.kotlindocs.backend.controller

import ai.grazie.api.gateway.client.SuspendableAPIGatewayClient
import ai.grazie.gen.tasks.text.webSearch.WebSearchTaskDescriptor
import ai.grazie.gen.tasks.text.webSearch.WebSearchTaskParams
import ai.grazie.model.llm.parameters.LLMConfig
import ai.grazie.model.llm.profile.OpenAIProfileIDs
import ai.grazie.model.llm.prompt.LLMPromptID
import kotlinx.coroutines.runBlocking
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class SearchController(
    private val apiClient: SuspendableAPIGatewayClient
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

    @PostMapping("/summarize")
    fun summary(@RequestBody(required = false) content: String?): String {
        if (content.isNullOrBlank()) {
            throw BadRequestException("Page content cannot be empty or null")
        }

        val summaryRequest = ChatRequest(
            // @language=markdown
            systemPrompt = """
                You are a helpful assistant that provides concise summaries.
                - Keep your responses brief and to the point.
                - Dont use any comments or extra text, only the summary.
                - Add common points
                
                For the page content, provide the following markdown:
                ```markdown
                $content
                ```
            """.trimIndent(),
            userPrompt = "Provide a summary of the page."
        )

        return processChatRequest(summaryRequest);
    }
}
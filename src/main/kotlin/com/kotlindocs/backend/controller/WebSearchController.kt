package com.kotlindocs.backend.controller

import ai.grazie.api.gateway.client.SuspendableAPIGatewayClient
import ai.grazie.gen.tasks.text.webSearch.WebSearchTaskDescriptor
import ai.grazie.gen.tasks.text.webSearch.WebSearchTaskParams
import kotlinx.coroutines.runBlocking
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class WebSearchController(
    private val apiClient: SuspendableAPIGatewayClient
) {
    companion object {
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
                stream?.collect { event ->
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
}
package com.kotlindocs.backend.manual

import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URI

/**
 * Manual test that connects to the real /chat/stream/sse endpoint and prints incoming events.
 *
 * How to use:
 * 1) Run the Spring Boot application locally (./gradlew bootRun) or point BASE_URL to remote URL.
 * 2) Provide the JWT if your server requires authentication (see tmp/jwt.txt) or leave as null for no auth.
 * 3) Run this test from your IDE. It's marked @Disabled to prevent it from running on CI by default.
 * 4) Observe console output with SSE events.
 */
class ChatStreamSseManualTest {

    private val BASE_URL: String = System.getProperty("chat.baseUrl")
        ?: System.getenv("CHAT_BASE_URL")
        ?: "http://localhost:8080"

    @Test
    fun connectAndPrintEvents() {
        val client = HttpClient.newHttpClient()

        val bodyJson = """
            {"systemPrompt":"You are a helpful assistant","userPrompt":"Say hello in one sentence"}
        """.trimIndent()

        val builder = HttpRequest.newBuilder()
            .uri(URI.create("$BASE_URL/chat/stream/sse"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(bodyJson))

        val request = builder.build()

        // We use ofInputStream to read the stream progressively and parse SSE frames manually.
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val input = response.body()

        println("Connected with status: ${response.statusCode()}")
        require(response.statusCode() in 200..299) { "Non-success status code" }

        input.bufferedReader().use { reader ->
            var line: String?
            var eventName: String? = null
            val dataBuilder = StringBuilder()

            fun flushEvent() {
                if (dataBuilder.isNotEmpty()) {
                    val data = dataBuilder.toString().trimEnd('\n')
                    println("[SSE EVENT] name=" + (eventName ?: "message") + ", data=" + data)
                    if (data == "[DONE]") return
                    dataBuilder.setLength(0)
                    eventName = null
                }
            }

            while (true) {
                line = reader.readLine() ?: break // end of stream

                if (line.isEmpty()) {
                    // blank line indicates event dispatch
                    flushEvent()
                    continue
                }

                when {
                    line.startsWith("event:") -> {
                        eventName = line.substringAfter(":").trim()
                    }
                    line.startsWith("data:") -> {
                        dataBuilder.append(line.substringAfter(":").trim()).append('\n')
                    }
                    // ignore other SSE fields (id:, retry:)
                }
            }

            // flush any trailing buffer
            flushEvent()
        }
    }
}

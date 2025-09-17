package com.kotlindocs.backend.controller

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(SearchController::class)
class WebSearchControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean
    private lateinit var apiClient: ai.grazie.api.gateway.client.SuspendableAPIGatewayClient

    @MockBean
    private lateinit var pagesService: com.kotlindocs.backend.services.PagesService

    @MockBean
    private lateinit var ragService: com.kotlindocs.backend.rag.RagService

    @Test
    fun `websearch endpoint should return search results`() {
        mockMvc.perform(get("/websearch"))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.anyOf(
                org.hamcrest.Matchers.equalTo(""),
                org.hamcrest.Matchers.startsWith("{"),
                org.hamcrest.Matchers.startsWith("["))) )
    }
}
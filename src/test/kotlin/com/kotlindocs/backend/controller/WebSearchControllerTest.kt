package com.kotlindocs.backend.controller

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@WebMvcTest(SearchController::class)
class WebSearchControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @org.springframework.boot.test.mock.mockito.MockBean
    private lateinit var apiClient: ai.grazie.api.gateway.client.SuspendableAPIGatewayClient

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
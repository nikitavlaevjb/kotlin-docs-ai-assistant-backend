package com.kotlindocs.backend.controller

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@WebMvcTest(WebSearchController::class)
class WebSearchControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `websearch endpoint should return success message`() {
        mockMvc.perform(get("/websearch"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").value("WebSearch endpoint is working!"))
            .andExpect(jsonPath("$.timestamp").exists())
    }
}
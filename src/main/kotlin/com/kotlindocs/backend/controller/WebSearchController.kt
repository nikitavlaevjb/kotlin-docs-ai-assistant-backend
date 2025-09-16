package com.kotlindocs.backend.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class WebSearchController {

    @GetMapping("/websearch")
    fun websearch(): Map<String, Any> {
        return mapOf(
            "message" to "WebSearch endpoint is working!",
            "timestamp" to System.currentTimeMillis()
        )
    }
}
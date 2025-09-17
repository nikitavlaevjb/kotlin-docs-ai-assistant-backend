package com.kotlindocs.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cache.annotation.EnableCaching

@SpringBootApplication
@EnableCaching
class KotlinDocsAiAssistantBackendApplication

fun main(args: Array<String>) {
    runApplication<KotlinDocsAiAssistantBackendApplication>(*args)
}
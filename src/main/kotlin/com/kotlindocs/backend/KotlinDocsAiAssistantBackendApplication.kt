package com.kotlindocs.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class KotlinDocsAiAssistantBackendApplication

fun main(args: Array<String>) {
    runApplication<KotlinDocsAiAssistantBackendApplication>(*args)
}
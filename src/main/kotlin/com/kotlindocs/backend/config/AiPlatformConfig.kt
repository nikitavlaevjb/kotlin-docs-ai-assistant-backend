package com.kotlindocs.backend.config

import ai.grazie.api.gateway.client.DefaultUrlResolver
import ai.grazie.api.gateway.client.PlatformConfigurationUrl
import ai.grazie.api.gateway.client.ResolutionResult
import ai.grazie.api.gateway.client.SuspendableAPIGatewayClient
import ai.grazie.client.common.SuspendableHTTPClient
import ai.grazie.client.ktor.GrazieKtorHTTPClient
import ai.grazie.model.auth.GrazieAgent
import ai.grazie.model.auth.v5.AuthData
import ai.grazie.model.cloud.AuthType
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AiPlatformConfig(
    @Value("\${ai-platform-token:}")
    private val userToken: String,
) {

    @Bean
    fun apiGatewayClient(): SuspendableAPIGatewayClient {
        require(userToken.isNotBlank()) { "ai-platform-token is not configured. Provide AI_PLATFORM_TOKEN env var or set ai-platform-token in application.yml" }

        val httpClient = SuspendableHTTPClient.WithV5(
            GrazieKtorHTTPClient.Client.Default,
            authData = AuthData(userToken, grazieAgent = GrazieAgent("kotlin-docs-ai-assistant-backend", "dev"))
        )

        val serverUrl = when (val resolutionResult = runBlockingResolve(httpClient)) {
            is ResolutionResult.Failure -> throw resolutionResult.problems.first()
            is ResolutionResult.FallbackUrl -> {
                // fallback URL
                println(resolutionResult.problems)
                resolutionResult.url
            }
            is ResolutionResult.Success -> resolutionResult.url
        }

        return SuspendableAPIGatewayClient(
            serverUrl = serverUrl,
            authType = AuthType.User,
            httpClient = httpClient,
        )
    }

    private fun runBlockingResolve(httpClient: SuspendableHTTPClient.WithV5): ResolutionResult =
        kotlinx.coroutines.runBlocking {
            DefaultUrlResolver(PlatformConfigurationUrl.Production.GLOBAL, httpClient).resolve()
        }
}

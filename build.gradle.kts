import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
	id("org.springframework.boot") version "3.3.3"
	id("io.spring.dependency-management") version "1.1.6"
	kotlin("jvm") version "2.1.0"
	kotlin("plugin.spring") version "2.1.0"
    id("com.google.cloud.tools.jib") version "3.4.3"
}

group = "com.kotlindocs"
version = "0.0.1-SNAPSHOT"

java {
	sourceCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/grazi/grazie-platform-public/")
    maven("https://repo.spring.io/milestone")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("ai.grazie.api:api-gateway-client-jvm:0.8.54")
    implementation("ai.grazie.client:client-ktor-jvm:0.8.54")

    // Spring AI (embeddings/vector store)
    implementation("org.springframework.ai:spring-ai-core:1.0.0-M2")

    // Vector math
    implementation("org.apache.commons:commons-math3:3.6.1")

    // Lucene for vector search
    implementation("org.apache.lucene:lucene-core:9.8.0")
    implementation("org.apache.lucene:lucene-queryparser:9.8.0")
    implementation("org.apache.lucene:lucene-analysis-common:9.8.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<KotlinCompile> {
	compilerOptions {
		freeCompilerArgs.add("-Xjsr305=strict")
        jvmTarget.set(JvmTarget.JVM_17)
	}
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Enable Byte Buddy experimental support for Java 24 to allow Mockito inline mocks
    systemProperty("net.bytebuddy.experimental", "true")
}
package com.kotlindocs.backend.services

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipInputStream

@Component
class PagesService(
    private val resourceLoader: ResourceLoader
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(PagesService::class.java)

    // Keep the temp dir reference so we can delete it on shutdown
    private var tempDir: Path? = null

    fun readTextFile(localPath: String): String? {
        val resourcePath = tempDir?.resolve(localPath)?.toFile()

        val stream = resourcePath?.inputStream()
            ?: error("Resource not found: $resourcePath")

        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    override fun run(args: ApplicationArguments) {
        val resource = resourceLoader.getResource("classpath:pages.zip")
        if (!resource.exists()) {
            // handle missing resource as appropriate
            logger.error("ZIP resource not found {}", resource.file.path)
            return
        }

        // Create a temp directory to unpack into
        tempDir = Files.createTempDirectory("pages-unpack-")
        logger.info("Unpacking {} to {}", resource.filename, tempDir)

        // Unpack using ZipInputStream
        resource.inputStream.use { fis ->
            BufferedInputStream(fis).use { bis ->
                ZipInputStream(bis).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val entryName = entry.name

                        // Resolve and normalize to protect against Zip Slip
                        val resolved = tempDir!!.resolve(entryName).normalize()
                        if (!resolved.startsWith(tempDir!!)) {
                            throw SecurityException("Bad zip entry: $entryName")
                        }

                        if (entry.isDirectory) {
                            Files.createDirectories(resolved)
                        } else {
                            // Ensure parent directories exist
                            Files.createDirectories(resolved.parent)
                            // Write file
                            Files.newOutputStream(
                                resolved,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING
                            ).use { os ->
                                zis.copyTo(os)
                            }
                        }

                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        }

        logger.info("Unpack complete")
    }

    @PreDestroy
    fun cleanUp() {
        tempDir?.let { deleteRecursively(it) }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }
}

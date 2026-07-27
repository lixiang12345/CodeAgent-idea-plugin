package com.codeagent.plugin.actions

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object SupportBundleExporter {
    fun export(
        destination: Path,
        logRoot: Path,
        statusReport: String,
        pluginVersion: String,
        ideName: String,
        ideBuild: String,
        generatedAt: Instant = Instant.now(),
        userHome: String = System.getProperty("user.home").orEmpty(),
    ): Path {
        val timestamp = FILE_TIMESTAMP.format(generatedAt)
        val output = destination.resolve("codeagent-logs-$timestamp.zip")
        val normalizedLogRoot = logRoot.toAbsolutePath().normalize()
        var totalInputBytes = 0L
        var filesWritten = 0
        var skippedByPolicy = 0
        var skippedBySize = 0
        var skippedSymlinks = 0

        val outputStream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        runCatching { Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rw-------")) }
        try {
            ZipOutputStream(outputStream).use { zip ->
                val metadata = buildString {
                    appendLine("CodeAgent diagnostic archive")
                    appendLine("Generated: $generatedAt")
                    appendLine("Plugin: $pluginVersion")
                    appendLine("IDE: $ideName")
                    appendLine("Build: $ideBuild")
                    appendLine("Source logs: JetBrains IDE log directory (absolute path redacted)")
                    appendLine("Policy: allowlisted UTF-8 text only; credentials and user home paths redacted")
                }
                zip.writeTextEntry("codeagent-environment.txt", SupportBundleRedactor.redact(metadata, userHome))
                zip.writeTextEntry("extension-status.txt", SupportBundleRedactor.redact(statusReport, userHome))

                if (Files.isDirectory(normalizedLogRoot, LinkOption.NOFOLLOW_LINKS)) {
                    Files.walk(normalizedLogRoot).use { stream ->
                        stream.sorted().forEach { file ->
                            if (file == normalizedLogRoot) return@forEach
                            if (Files.isSymbolicLink(file)) {
                                skippedSymlinks += 1
                                return@forEach
                            }
                            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return@forEach

                            val relative = normalizedLogRoot.relativize(file)
                            if (!SupportBundleLogPolicy.isAllowed(relative)) {
                                skippedByPolicy += 1
                                return@forEach
                            }
                            val size = runCatching { Files.size(file) }.getOrDefault(MAX_FILE_BYTES + 1)
                            if (
                                size > MAX_FILE_BYTES ||
                                totalInputBytes + size > MAX_ARCHIVE_INPUT_BYTES ||
                                filesWritten >= MAX_FILES
                            ) {
                                skippedBySize += 1
                                return@forEach
                            }

                            val source = readUtf8ReplacingMalformed(file)
                            if (source == null || totalInputBytes + source.bytesRead > MAX_ARCHIVE_INPUT_BYTES) {
                                skippedBySize += 1
                                return@forEach
                            }
                            val redacted = SupportBundleRedactor.redact(source.text, userHome)
                            val entry = "logs/${relative.toString().replace('\\', '/')}"
                            zip.writeTextEntry(entry, redacted)
                            totalInputBytes += source.bytesRead
                            filesWritten += 1
                        }
                    }
                }

                val summary = buildString {
                    appendLine("Files included: $filesWritten")
                    appendLine("Input bytes inspected: $totalInputBytes")
                    appendLine("Skipped by allowlist: $skippedByPolicy")
                    appendLine("Skipped by size/count budget: $skippedBySize")
                    appendLine("Skipped symbolic links: $skippedSymlinks")
                }
                zip.writeTextEntry("export-summary.txt", SupportBundleRedactor.redact(summary, userHome))
            }
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(output) }
            throw error
        }
        return output
    }

    private fun readUtf8ReplacingMalformed(file: Path): TextLog? {
        val bytes = Files.newInputStream(file).use { input ->
            input.readNBytes(MAX_FILE_BYTES.toInt() + 1)
        }
        if (bytes.size > MAX_FILE_BYTES) return null
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return TextLog(bytes.size.toLong(), decoder.decode(ByteBuffer.wrap(bytes)).toString())
    }

    private fun ZipOutputStream.writeTextEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }

    private const val MAX_FILES = 40
    private const val MAX_FILE_BYTES = 25L * 1024 * 1024
    private const val MAX_ARCHIVE_INPUT_BYTES = 200L * 1024 * 1024
    private val FILE_TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

    private data class TextLog(val bytesRead: Long, val text: String)
}

internal object SupportBundleLogPolicy {
    private val rotatedIdeaLog = Regex("idea(?:\\.log\\.\\d+|\\.\\d+\\.log)")
    private val jcefLog = Regex("jcef_chromium(?:_\\d+)?\\.log")
    private val freezeDirectory = Regex("threadDumps-freeze-[A-Za-z0-9._-]+")
    private val threadDump = Regex("threadDump-[A-Za-z0-9._-]+\\.txt")

    fun isAllowed(relative: Path): Boolean {
        if (relative.isAbsolute || relative.any { it.toString() == "." || it.toString() == ".." }) return false
        val name = relative.fileName?.toString() ?: return false
        if (relative.nameCount == 1) {
            return name == "idea.log" ||
                rotatedIdeaLog.matches(name) ||
                jcefLog.matches(name)
        }
        return relative.nameCount == 2 &&
            freezeDirectory.matches(relative.getName(0).toString()) &&
            (name == "report.txt" || threadDump.matches(name))
    }
}

internal object SupportBundleRedactor {
    private const val REDACTED = "[REDACTED]"
    private val sensitiveHeader = Regex(
        "(?im)^(\\s*(?:authorization|proxy-authorization|cookie|set-cookie|x-api-key|x-codeagent-byok-[a-z0-9_-]+)\\s*:\\s*).*$",
    )
    private val sensitiveKeyValue = Regex(
        "(?i)([\"']?\\b[A-Za-z0-9_.-]*(?:authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|id[_-]?token|client[_-]?secret|secret[_-]?access[_-]?key|session[_-]?token|password|passwd|cookie|token|secret)[A-Za-z0-9_.-]*\\b[\"']?)(\\s*[:=]\\s*)(?!\\[REDACTED\\])(?:\"(?:\\\\.|[^\"])*\"|'[^']*'|[^\\s,;}\\]]+)",
    )
    private val authorizationCredential = Regex(
        "(?i)([\"']?\\b[A-Za-z0-9_.-]*(?:authorization|auth_header)[A-Za-z0-9_.-]*\\b[\"']?\\s*[:=]\\s*[\"']?)(?:Bearer|Basic)\\s+[A-Za-z0-9+/_=.-]{6,}[\"']?",
    )
    private val uppercaseAssignment = Regex(
        "(?m)\\b([A-Z][A-Z0-9_]{2,}\\s*=\\s*)(?!\\[REDACTED\\])(?:\"(?:\\\\.|[^\"])*\"|'[^']*'|[^\\s,;]+)",
    )
    private val bearerCredential = Regex("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9+/_=.-]{6,}")
    private val urlUserInfo = Regex("(?i)(https?://)[^/\\s:@]+(?::[^/\\s@]*)?@")
    private val sensitiveQuery = Regex(
        "(?i)([?&#](?:access[_-]?token|refresh[_-]?token|id[_-]?token|api[_-]?key|token|secret|password|client_secret|code|code_verifier|sig|signature|x-amz-signature)=)[^&#\\s]+",
    )
    private val sensitivePathSegment = Regex(
        "(?i)(/(?:share|token|oauth/callback)/)[A-Za-z0-9._~+/=-]{8,}",
    )
    private val jwt = Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b")
    private val knownApiKey = Regex(
        "\\b(?:sk-(?:proj-|ant-)?[A-Za-z0-9_-]{12,}|github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|(?:secret|ntn|lin_api)_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{16,}|(?:AKIA|ASIA)[A-Z0-9]{16})\\b",
    )

    fun redact(value: String, userHome: String): String {
        var redacted = value
        if (userHome.isNotBlank()) {
            redacted = redacted.replace(userHome, "~")
            redacted = redacted.replace(userHome.replace('/', '\\'), "~")
        }
        redacted = sensitiveHeader.replace(redacted) { match -> "${match.groupValues[1]}$REDACTED" }
        redacted = urlUserInfo.replace(redacted) { match -> "${match.groupValues[1]}$REDACTED@" }
        redacted = sensitiveQuery.replace(redacted) { match -> "${match.groupValues[1]}$REDACTED" }
        redacted = sensitivePathSegment.replace(redacted) { match -> "${match.groupValues[1]}$REDACTED" }
        redacted = authorizationCredential.replace(redacted) { match -> "${match.groupValues[1]}$REDACTED" }
        redacted = sensitiveKeyValue.replace(redacted) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}$REDACTED"
        }
        redacted = uppercaseAssignment.replace(redacted) { match -> "${match.groupValues[1]}$REDACTED" }
        redacted = bearerCredential.replace(redacted) { match -> "${match.groupValues[1]} $REDACTED" }
        redacted = jwt.replace(redacted, REDACTED)
        return knownApiKey.replace(redacted, REDACTED)
    }
}

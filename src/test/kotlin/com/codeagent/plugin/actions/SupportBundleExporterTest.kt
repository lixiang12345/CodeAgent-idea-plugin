package com.codeagent.plugin.actions

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.zip.ZipFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupportBundleExporterTest {
    @Test
    fun `redacts credential shapes urls and user home`() {
        val home = "/Users/pilot"
        val secrets = listOf(
            "bearer-sentinel-123456",
            "cookie-sentinel-123456",
            "query-sentinel-123456",
            "json-sentinel-123456",
            "password-sentinel-123456",
            "sk-proj-DIRECTSENTINEL1234567890",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzZW50aW5lbCJ9.c2lnbmF0dXJlc2VudGluZWw",
            "user:pass",
            "env-token-sentinel-123456",
            "equals-auth-sentinel-123456",
            "opaque-gateway-value-123456",
            "share-path-sentinel-123456",
        )
        val input = """
            Authorization: Bearer ${secrets[0]}
            Cookie: session=${secrets[1]}; theme=dark
            GET https://${secrets[7]}@backend.example/v1?token=${secrets[2]}&page=1
            {"api_key":"${secrets[3]}","password":"${secrets[4]}"}
            Direct key ${secrets[5]}
            JWT ${secrets[6]}
            NOTION_TOKEN=${secrets[8]}
            authorization=Bearer ${secrets[9]}
            RANDOM_GATEWAY=${secrets[10]}
            Share https://backend.example/v1/share/${secrets[11]}
            File $home/workspace/idea.log
        """.trimIndent()

        val redacted = SupportBundleRedactor.redact(input, home)

        secrets.forEach { assertFalse(redacted.contains(it), "Secret remained in redacted output: $it") }
        assertContains(redacted, "Authorization: [REDACTED]")
        assertContains(redacted, "https://[REDACTED]@backend.example/v1?token=[REDACTED]&page=1")
        assertContains(redacted, "File ~/workspace/idea.log")
    }

    @Test
    fun `allows only bounded diagnostic text paths`() {
        assertTrue(SupportBundleLogPolicy.isAllowed(Path.of("idea.log")))
        assertTrue(SupportBundleLogPolicy.isAllowed(Path.of("idea.log.2")))
        assertTrue(SupportBundleLogPolicy.isAllowed(Path.of("idea.2.log")))
        assertTrue(SupportBundleLogPolicy.isAllowed(Path.of("jcef_chromium_123.log")))
        assertFalse(SupportBundleLogPolicy.isAllowed(Path.of("hs_err_pid123.log")))
        assertTrue(
            SupportBundleLogPolicy.isAllowed(
                Path.of("threadDumps-freeze-20260727-154658/report.txt"),
            ),
        )
        assertTrue(
            SupportBundleLogPolicy.isAllowed(
                Path.of("threadDumps-freeze-20260727-154658/threadDump-20260727-154707.txt"),
            ),
        )
        assertFalse(SupportBundleLogPolicy.isAllowed(Path.of("open-telemetry-meters.json")))
        assertFalse(SupportBundleLogPolicy.isAllowed(Path.of("indexing-diagnostic/storage.json")))
        assertFalse(
            SupportBundleLogPolicy.isAllowed(
                Path.of("threadDumps-freeze-20260727/nested/threadDump-20260727.txt"),
            ),
        )
        assertFalse(
            SupportBundleLogPolicy.isAllowed(
                Path.of("threadDumps-freeze-20260727/../threadDump-20260727.txt"),
            ),
        )
        assertFalse(SupportBundleLogPolicy.isAllowed(Path.of("unrelated.bin")))
    }

    @Test
    fun `exports a redacted allowlisted archive and skips symlinks`() {
        val root = Files.createTempDirectory("codeagent-support-bundle")
        val logRoot = root.resolve("logs")
        val destination = root.resolve("exports")
        val fakeHome = root.resolve("home").toString()
        try {
            Files.createDirectories(logRoot)
            Files.createDirectories(destination)
            logRoot.resolve("idea.log").writeText(
                "CodeAgent ready\nAuthorization: Bearer archive-bearer-sentinel\n" +
                    "project=$fakeHome/workspace\n",
            )
            logRoot.resolve("jcef_chromium_123.log").writeText(
                "JCEF request https://user:pass@localhost/v1?api_key=query-archive-sentinel\n",
            )
            val freeze = logRoot.resolve("threadDumps-freeze-20260727-154658")
            Files.createDirectories(freeze)
            freeze.resolve("report.txt").writeText("password=thread-dump-sentinel\nDiff open stack\n")
            logRoot.resolve("open-telemetry-meters.json").writeText("token=telemetry-sentinel\n")
            logRoot.resolve("unrelated.bin").writeText("secret=binary-sentinel\n")

            val external = root.resolve("external-secret.txt")
            external.writeText("Authorization: Bearer symlink-sentinel\n")
            val symlinkCreated = runCatching {
                Files.createSymbolicLink(logRoot.resolve("idea.log.1"), external)
            }.isSuccess

            val output = SupportBundleExporter.export(
                destination = destination,
                logRoot = logRoot,
                statusReport = "Backend online\nclient_secret=status-sentinel\n",
                pluginVersion = "0.7.30",
                ideName = "PyCharm",
                ideBuild = "PY-261.24374.152",
                generatedAt = Instant.parse("2026-07-27T08:00:00Z"),
                userHome = fakeHome,
            )

            if (Files.getFileStore(output).supportsFileAttributeView("posix")) {
                assertEquals(
                    PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(output),
                )
            }

            ZipFile(output.toFile()).use { zip ->
                val contents = zip.entries().asSequence().associate { entry ->
                    entry.name to zip.getInputStream(entry).bufferedReader().use { it.readText() }
                }
                assertTrue("logs/idea.log" in contents)
                assertTrue("logs/jcef_chromium_123.log" in contents)
                assertTrue("logs/threadDumps-freeze-20260727-154658/report.txt" in contents)
                assertFalse("logs/open-telemetry-meters.json" in contents)
                assertFalse("logs/unrelated.bin" in contents)
                if (symlinkCreated) assertNull(contents["logs/idea.log.1"])

                val archiveText = contents.values.joinToString("\n")
                listOf(
                    "archive-bearer-sentinel",
                    "user:pass",
                    "query-archive-sentinel",
                    "thread-dump-sentinel",
                    "telemetry-sentinel",
                    "binary-sentinel",
                    "symlink-sentinel",
                    "status-sentinel",
                    fakeHome,
                ).forEach { sentinel ->
                    assertFalse(archiveText.contains(sentinel), "Archive leaked sentinel: $sentinel")
                }
                assertContains(archiveText, "CodeAgent ready")
                assertContains(archiveText, "Diff open stack")
                assertContains(contents.getValue("codeagent-environment.txt"), "absolute path redacted")
                assertContains(contents.getValue("export-summary.txt"), "Files included: 3")
                if (symlinkCreated) {
                    assertContains(contents.getValue("export-summary.txt"), "Skipped symbolic links: 1")
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

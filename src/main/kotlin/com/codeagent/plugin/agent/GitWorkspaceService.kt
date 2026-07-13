package com.codeagent.plugin.agent

import com.codeagent.plugin.bridge.GitFileDto
import com.codeagent.plugin.bridge.GitSnapshotDto
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

@Service(Service.Level.PROJECT)
class GitWorkspaceService(private val project: Project) {
    private val root = Path.of(requireNotNull(project.basePath)).toAbsolutePath().normalize()
    private val guard = ProjectPathGuard(root)
    private val executor = AppExecutorUtil.getAppExecutorService()

    fun snapshot(): CompletableFuture<GitSnapshotDto> = CompletableFuture.supplyAsync({ snapshotBlocking() }, executor)

    fun stage(paths: List<String>): CompletableFuture<GitSnapshotDto> = mutate(paths, "add", "--")

    fun unstage(paths: List<String>): CompletableFuture<GitSnapshotDto> {
        val normalized = normalizePaths(paths)
        return CompletableFuture.supplyAsync({
            val restored = runGit(listOf("restore", "--staged", "--") + normalized, allowFailure = true)
            if (restored.exitCode != 0) {
                val hasHead = runGit(listOf("rev-parse", "--verify", "HEAD"), allowFailure = true).exitCode == 0
                check(!hasHead) { restored.stderr.trim().ifEmpty { "Unable to unstage selected files" } }
                runGit(listOf("rm", "--cached", "--") + normalized)
            }
            snapshotBlocking()
        }, executor)
    }

    fun commit(message: String): CompletableFuture<GitSnapshotDto> {
        val cleanMessage = message.trim()
        require(cleanMessage.isNotEmpty()) { "Commit message is required" }
        require(cleanMessage.length <= 4_000) { "Commit message is too long" }
        val future = CompletableFuture<GitSnapshotDto>()
        ApplicationManager.getApplication().invokeLater {
            val answer = Messages.showYesNoDialog(
                project,
                "Commit the currently staged changes?",
                "CodeAgent Git Commit",
                "Commit",
                "Cancel",
                Messages.getQuestionIcon(),
            )
            if (answer != Messages.YES) {
                snapshot().whenComplete { snapshot, error ->
                    if (error != null) future.completeExceptionally(error) else future.complete(snapshot)
                }
                return@invokeLater
            }
            CompletableFuture.supplyAsync({
                runGit(listOf("commit", "-m", cleanMessage), timeoutMillis = 60_000)
                snapshotBlocking()
            }, executor).whenComplete { snapshot, error ->
                if (error != null) future.completeExceptionally(error) else future.complete(snapshot)
            }
        }
        return future
    }

    fun suggestCommitMessage(staged: List<GitFileDto>): String {
        require(staged.isNotEmpty()) { "Stage at least one file first" }
        val paths = staged.map { it.path }
        val prefix = when {
            paths.all { it.endsWith(".md", ignoreCase = true) } -> "docs"
            paths.all { it.contains("test", ignoreCase = true) || it.contains("spec", ignoreCase = true) } -> "test"
            paths.all { it.endsWith(".gradle") || it.endsWith(".gradle.kts") || it.endsWith("package.json") } -> "build"
            else -> "chore"
        }
        val scope = paths.mapNotNull { it.substringBefore('/').takeIf(String::isNotBlank) }
            .distinct()
            .singleOrNull()
            ?.takeIf { it.length <= 24 }
        val subject = if (paths.size == 1) "update ${Path.of(paths.single()).fileName}" else "update ${paths.size} files"
        return buildString {
            append(prefix)
            if (scope != null) append('(').append(scope).append(')')
            append(": ").append(subject)
        }
    }

    fun openDiff(path: String, staged: Boolean): CompletableFuture<Unit> = CompletableFuture.supplyAsync({
        val relative = normalizePath(path)
        val before = if (staged) readRevision("HEAD", relative) else readRevision(":", relative)
        val after = if (staged) readRevision(":", relative) else readWorkingTree(relative)
        ApplicationManager.getApplication().invokeLater {
            val request = SimpleDiffRequest(
                "Git ${if (staged) "staged" else "working tree"} change: $relative",
                DiffContentFactory.getInstance().create(before),
                DiffContentFactory.getInstance().create(after),
                if (staged) "HEAD" else "Index",
                if (staged) "Index" else "Working Tree",
            )
            DiffManager.getInstance().showDiff(project, request)
        }
    }, executor)

    private fun mutate(paths: List<String>, vararg arguments: String): CompletableFuture<GitSnapshotDto> {
        val normalized = normalizePaths(paths)
        return CompletableFuture.supplyAsync({
            runGit(arguments.toList() + normalized)
            snapshotBlocking()
        }, executor)
    }

    private fun normalizePaths(paths: List<String>): List<String> = paths.distinct().map(::normalizePath).also {
        require(it.isNotEmpty()) { "Select at least one Git path" }
    }

    private fun snapshotBlocking(): GitSnapshotDto = runCatching {
        val result = runGit(listOf("status", "--porcelain=v1", "--branch", "-z"))
        GitStatusParser.parse(result.stdout, root.fileName?.toString().orEmpty())
    }.getOrElse { error ->
        GitSnapshotDto(repository = root.fileName?.toString().orEmpty(), error = error.message ?: "Git unavailable")
    }

    private fun normalizePath(path: String): String {
        val resolved = guard.pathForWrite(path)
        return root.relativize(resolved).toString().replace('\\', '/')
    }

    private fun readRevision(revision: String, path: String): String {
        val spec = if (revision == ":") ":$path" else "$revision:$path"
        val result = runGit(listOf("show", spec), allowFailure = true)
        return if (result.exitCode == 0) result.stdout else ""
    }

    private fun readWorkingTree(path: String): String {
        val candidate = guard.pathForWrite(path)
        if (!Files.exists(candidate)) return ""
        val file = guard.existingFile(path)
        require(Files.size(file) <= MAX_DIFF_BYTES) { "$path is too large for text Diff" }
        return Files.readString(file, StandardCharsets.UTF_8)
    }

    private fun runGit(
        arguments: List<String>,
        timeoutMillis: Int = 20_000,
        allowFailure: Boolean = false,
    ) = CapturingProcessHandler(
        GeneralCommandLine(listOf("git", "-C", root.toString()) + arguments)
            .withWorkDirectory(root.toFile())
            .withCharset(StandardCharsets.UTF_8),
    ).runProcess(timeoutMillis).also { result ->
        check(!result.isTimeout) { "Git command timed out" }
        if (!allowFailure) check(result.exitCode == 0) {
            result.stderr.trim().ifEmpty { "Git command failed with exit ${result.exitCode}" }
        }
    }

    companion object {
        private const val MAX_DIFF_BYTES = 2_000_000L
    }
}

internal object GitStatusParser {
    fun parse(output: String, repository: String): GitSnapshotDto {
        val records = output.split('\u0000')
        var branch = ""
        val staged = mutableListOf<GitFileDto>()
        val unstaged = mutableListOf<GitFileDto>()
        var index = 0
        while (index < records.size) {
            val record = records[index]
            if (record.isEmpty()) {
                index++
                continue
            }
            if (record.startsWith("## ")) {
                val header = record.removePrefix("## ")
                branch = when {
                    header.startsWith("No commits yet on ") -> header.removePrefix("No commits yet on ")
                    header.startsWith("Initial commit on ") -> header.removePrefix("Initial commit on ")
                    header.startsWith("HEAD (no branch)") -> "HEAD"
                    else -> header.substringBefore("...").substringBefore(' ')
                }
                index++
                continue
            }
            if (record.length < 4) {
                index++
                continue
            }
            val x = record[0]
            val y = record[1]
            val path = record.substring(3)
            val renamed = x in "RC" || y in "RC"
            if (x != ' ' && x != '?') staged += GitFileDto(path, statusLabel(x))
            if (y != ' ' || x == '?') unstaged += GitFileDto(path, statusLabel(if (x == '?') '?' else y))
            index += if (renamed) 2 else 1
        }
        return GitSnapshotDto(
            available = true,
            branch = branch.ifBlank { "HEAD" },
            repository = repository,
            unstaged = unstaged.sortedBy { it.path },
            staged = staged.sortedBy { it.path },
        )
    }

    private fun statusLabel(code: Char): String = when (code) {
        'M' -> "modified"
        'A' -> "added"
        'D' -> "deleted"
        'R' -> "renamed"
        'C' -> "copied"
        'U' -> "unmerged"
        '?' -> "untracked"
        else -> code.toString()
    }
}

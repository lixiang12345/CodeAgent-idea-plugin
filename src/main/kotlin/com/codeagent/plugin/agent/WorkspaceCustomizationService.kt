package com.codeagent.plugin.agent

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

internal data class WorkspaceCustomization(
    val rules: List<WorkspaceRule>,
    val skills: List<WorkspaceSkill>,
) {
    companion object {
        val EMPTY = WorkspaceCustomization(emptyList(), emptyList())
    }
}

internal data class WorkspaceRule(
    val id: String,
    val name: String,
    val path: String,
    val content: String,
)

internal data class WorkspaceSkill(
    val id: String,
    val name: String,
    val description: String,
    val path: String,
    val content: String,
)

@Service(Service.Level.PROJECT)
internal class WorkspaceCustomizationService(project: Project) {
    private val loader = WorkspaceCustomizationLoader(project.basePath?.let(Path::of))
    private var cached: WorkspaceCustomization? = null

    @Synchronized
    fun snapshot(): WorkspaceCustomization = cached ?: loader.load().also { cached = it }

    @Synchronized
    fun refresh(): WorkspaceCustomization = loader.load().also { cached = it }
}

internal class WorkspaceCustomizationLoader(private val projectRoot: Path?) {
    fun load(): WorkspaceCustomization = runCatching {
        val root = projectRoot?.toRealPath() ?: return WorkspaceCustomization.EMPTY
        WorkspaceCustomization(
            rules = loadRules(root),
            skills = loadSkills(root),
        )
    }.getOrDefault(WorkspaceCustomization.EMPTY)

    private fun loadRules(root: Path): List<WorkspaceRule> {
        val directory = root.resolve(".codeagent/rules")
        if (!Files.isDirectory(directory)) return emptyList()
        return listChildren(directory)
            .filter { it.fileName.toString().endsWith(".md", ignoreCase = true) }
            .mapNotNull { file ->
                val content = readProjectFile(root, file) ?: return@mapNotNull null
                val relative = relativePath(root, file)
                WorkspaceRule(
                    id = relative,
                    name = markdownTitle(content) ?: file.fileName.toString().substringBeforeLast('.').humanize(),
                    path = relative,
                    content = content,
                )
            }
            .take(MAX_RULES)
    }

    private fun loadSkills(root: Path): List<WorkspaceSkill> = buildList {
        SKILL_ROOTS.forEach { relativeRoot ->
            val directory = root.resolve(relativeRoot)
            if (!Files.isDirectory(directory)) return@forEach
            listChildren(directory).filter(Files::isDirectory).forEach { skillDirectory ->
                if (size >= MAX_SKILLS) return@buildList
                val file = skillDirectory.resolve("SKILL.md")
                val content = readProjectFile(root, file) ?: return@forEach
                val relative = relativePath(root, file)
                val body = withoutFrontMatter(content)
                add(
                    WorkspaceSkill(
                        id = relative,
                        name = markdownTitle(body) ?: skillDirectory.fileName.toString().humanize(),
                        description = markdownDescription(body),
                        path = relative,
                        content = content,
                    ),
                )
            }
        }
    }.sortedBy { it.path }

    private fun listChildren(directory: Path): List<Path> = Files.list(directory).use { stream ->
        stream.sorted().toList()
    }

    private fun readProjectFile(root: Path, file: Path): String? = runCatching {
        if (!Files.isRegularFile(file)) return null
        val resolved = file.toRealPath()
        if (!resolved.startsWith(root)) return null
        Files.newBufferedReader(resolved).use { reader ->
            val content = StringBuilder()
            val buffer = CharArray(4096)
            while (content.length < MAX_FILE_CHARS) {
                val count = reader.read(buffer, 0, minOf(buffer.size, MAX_FILE_CHARS - content.length))
                if (count < 0) break
                content.append(buffer, 0, count)
            }
            content.toString().trim().takeIf(String::isNotEmpty)
        }
    }.getOrNull()

    private fun relativePath(root: Path, file: Path): String =
        root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/')

    private fun markdownTitle(content: String): String? = content.lineSequence()
        .firstOrNull { it.startsWith("# ") }
        ?.removePrefix("# ")
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    private fun markdownDescription(content: String): String = content.lineSequence()
        .map(String::trim)
        .firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
        ?.take(MAX_DESCRIPTION_CHARS)
        ?: "Repository skill"

    private fun withoutFrontMatter(content: String): String {
        val lines = content.lines()
        if (lines.firstOrNull()?.trim() != "---") return content
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        return if (end < 0) content else lines.drop(end + 2).joinToString("\n")
    }

    private fun String.humanize(): String = replace('-', ' ').replace('_', ' ')
        .split(' ')
        .filter(String::isNotBlank)
        .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercaseChar) }

    companion object {
        const val MAX_SELECTED_SKILLS = 8
        private const val MAX_RULES = 32
        private const val MAX_SKILLS = 64
        private const val MAX_FILE_CHARS = 16_000
        private const val MAX_DESCRIPTION_CHARS = 180
        private val SKILL_ROOTS = listOf(".codeagent/skills", ".agents/skills")
    }
}

internal class WorkspaceGuidanceLoader(private val projectRoot: Path?) {
    fun load(): String? = runCatching {
        val root = projectRoot?.toRealPath() ?: return null
        val guidanceFile = root.resolve("AGENTS.md")
        if (!Files.isRegularFile(guidanceFile)) return null
        val resolvedFile = guidanceFile.toRealPath()
        if (!resolvedFile.startsWith(root)) return null

        Files.newBufferedReader(resolvedFile).use { reader ->
            val content = StringBuilder()
            val buffer = CharArray(4096)
            while (content.length < MAX_GUIDANCE_CHARS) {
                val count = reader.read(buffer, 0, minOf(buffer.size, MAX_GUIDANCE_CHARS - content.length))
                if (count < 0) break
                content.append(buffer, 0, count)
            }
            content.toString().trim().takeIf(String::isNotEmpty)
        }
    }.getOrNull()

    companion object {
        private const val MAX_GUIDANCE_CHARS = 16_000
    }
}

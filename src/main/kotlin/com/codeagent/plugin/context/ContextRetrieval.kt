package com.codeagent.plugin.context

import kotlinx.serialization.Serializable
import java.util.Locale

internal data class ContextQuery(
    val text: String,
    val purpose: String,
    val pathPrefix: String? = null,
)

internal data class ContextQueryPlan(
    val request: String,
    val strategy: String,
    val queries: List<ContextQuery>,
    val identifiers: List<String>,
    val pathHints: List<String>,
)

@Serializable
internal data class ContextSearchChunk(
    val id: String,
    val path: String,
    val language: String,
    val startLine: Int,
    val endLine: Int,
    val content: String,
    val symbol: String? = null,
    val hash: String = "",
)

@Serializable
internal data class ContextSearchHit(
    val chunk: ContextSearchChunk,
    val score: Double,
    val source: String,
    val preview: String = "",
    val channels: Map<String, Double> = emptyMap(),
    val intent: String? = null,
)

internal data class ContextQueryResult(
    val query: ContextQuery,
    val hits: List<ContextSearchHit>,
)

internal data class PlannedContextPack(
    val task: String,
    val strategy: String,
    val queries: List<ContextQuery>,
    val packedText: String,
    val estimatedTokens: Int,
    val hitCount: Int,
    val availableHitCount: Int,
    val fileCount: Int,
    val truncated: Boolean,
)

internal object ContextQueryPlanner {
    private val pathPattern = Regex("""(?:[\w.-]+/)+[\w.-]+|\b[\w-]+\.(?:kt|kts|java|ts|tsx|js|jsx|py|go|rs|cpp|c|h|md|json|yaml|yml|toml|xml)\b""")
    private val identifierPattern = Regex("""\b[A-Za-z_][A-Za-z0-9_]*(?:(?:::|\.)[A-Za-z_][A-Za-z0-9_]*)*\b""")
    private val wordPattern = Regex("""[\p{L}\p{N}_-]{2,}""")
    private val stopWords = setOf(
        "about", "after", "also", "and", "are", "before", "build", "change", "code", "complete",
        "current", "does", "file", "files", "from", "have", "how", "implement", "into", "need",
        "project", "should", "that", "the", "this", "through", "use", "using", "what", "when",
        "where", "which", "with", "would",
    )

    fun plan(request: String, strategy: String = "balanced", focusPaths: List<String> = emptyList()): ContextQueryPlan {
        val normalized = request.replace(Regex("""\s+"""), " ").trim().take(4_000)
        require(normalized.isNotEmpty()) { "information request must not be empty" }
        val normalizedStrategy = strategy.lowercase().also {
            require(it in setOf("fast", "balanced", "deep")) { "strategy must be fast, balanced, or deep" }
        }
        val pathHints = linkedSetOf<String>()
        focusPaths.mapNotNullTo(pathHints, ::normalizePathHint)
        pathPattern.findAll(normalized).mapNotNullTo(pathHints) { normalizePathHint(it.value) }

        val identifiers = identifierPattern.findAll(normalized)
            .map { it.value }
            .filter { value ->
                val lower = value.lowercase()
                lower !in stopWords && (
                    value.length >= 6 ||
                        value.any(Char::isUpperCase) ||
                        '_' in value ||
                        '.' in value ||
                        "::" in value
                    )
            }
            .distinct()
            .take(10)
            .toList()
        val identifierTerms = identifiers.map { it.lowercase() }.toSet()
        val domainTerms = wordPattern.findAll(normalized)
            .map { it.value.lowercase() }
            .filter { it.length >= 3 && it !in stopWords && it !in identifierTerms }
            .distinct()
            .take(10)
            .toList()

        val queries = mutableListOf<ContextQuery>()
        fun add(text: String, purpose: String, pathPrefix: String? = null) {
            val queryText = text.trim()
            if (queryText.isEmpty()) return
            if (queries.none { it.text == queryText && it.pathPrefix == pathPrefix }) {
                queries += ContextQuery(queryText, purpose, pathPrefix)
            }
        }

        add(
            normalized,
            purpose = "task",
            pathPrefix = if (normalizedStrategy == "fast") pathHints.firstOrNull() else null,
        )
        if (identifiers.isNotEmpty()) {
            add((identifiers + domainTerms.take(4)).joinToString(" "), "symbols")
        }
        if (domainTerms.size >= 2) {
            add(domainTerms.joinToString(" "), "concepts")
        }
        for (path in pathHints) add(normalized, "path", path)

        val maxQueries = when (normalizedStrategy) {
            "fast" -> 1
            "deep" -> 6
            else -> 4
        }
        return ContextQueryPlan(
            request = normalized,
            strategy = normalizedStrategy,
            queries = queries.take(maxQueries),
            identifiers = identifiers,
            pathHints = pathHints.take(8),
        )
    }

    private fun normalizePathHint(value: String): String? = value
        .trim()
        .replace('\\', '/')
        .removePrefix("./")
        .trim('/')
        .takeIf(String::isNotEmpty)
        ?.take(1_000)
}

internal object ContextPackBuilder {
    fun build(plan: ContextQueryPlan, results: List<ContextQueryResult>, maxTokens: Int?): PlannedContextPack {
        val budget = maxTokens?.coerceAtLeast(1)
        val aggregates = linkedMapOf<String, AggregatedHit>()
        results.forEachIndexed { queryIndex, result ->
            for (hit in result.hits) {
                val key = "${hit.chunk.path}:${hit.chunk.startLine}-${hit.chunk.endLine}"
                val aggregate = aggregates.getOrPut(key) { AggregatedHit(hit) }
                if (hit.score > aggregate.hit.score) aggregate.hit = hit
                aggregate.queryIndexes += queryIndex
            }
        }

        val ranked = aggregates.values.sortedByDescending(::rankScore)
        val firstByPath = ranked.distinctBy { it.hit.chunk.path }
        val firstKeys = firstByPath.mapTo(hashSetOf()) { keyOf(it.hit) }
        val ordered = firstByPath + ranked.filter { keyOf(it.hit) !in firstKeys }
        val parts = mutableListOf<String>()
        parts += "# Context pack"
        parts += ""
        parts += "Request: ${plan.request.take(800)}"
        parts += "Strategy: ${plan.strategy}"
        parts += "Queries: ${plan.queries.size}"
        plan.queries.forEachIndexed { index, query ->
            parts += "${index + 1}. [${query.purpose}] ${query.text.take(300)}${query.pathPrefix?.let { " (path: $it)" }.orEmpty()}"
        }
        parts += ""
        parts += "Evidence candidates: ${ordered.size} chunks across ${ordered.map { it.hit.chunk.path }.distinct().size} files."
        parts += ""

        var tokens = estimateTokens(parts.joinToString("\n"))
        var truncated = false
        val used = mutableListOf<AggregatedHit>()
        for (aggregate in ordered) {
            val block = formatHit(aggregate, aggregate.hit.chunk.content)
            val blockTokens = estimateTokens(block)
            if (budget == null || tokens + blockTokens <= budget) {
                parts += block
                tokens += blockTokens
                used += aggregate
                continue
            }

            truncated = true
            val explicitBudget = requireNotNull(budget)
            if (used.isNotEmpty()) break
            val remainingTokens = explicitBudget - tokens
            val overheadTokens = estimateTokens(formatHit(aggregate, ""))
            val contentTokens = remainingTokens - overheadTokens - 20
            if (contentTokens > 0) {
                val content = aggregate.hit.chunk.content.take(contentTokens * 4) + "\n...[truncated to context pack budget]..."
                val shortened = formatHit(aggregate, content)
                if (tokens + estimateTokens(shortened) <= explicitBudget) {
                    parts += shortened
                    tokens += estimateTokens(shortened)
                    used += aggregate
                }
            }
            break
        }

        if (used.isEmpty()) {
            parts += if (budget == null)
                "No indexed evidence matched the planned queries."
            else
                "No indexed evidence matched the planned queries within the explicit context cap."
        }
        truncated = truncated || used.size < ordered.size
        val packedText = parts.joinToString("\n")
        return PlannedContextPack(
            task = plan.request,
            strategy = plan.strategy,
            queries = plan.queries,
            packedText = packedText,
            estimatedTokens = estimateTokens(packedText),
            hitCount = used.size,
            availableHitCount = ordered.size,
            fileCount = used.map { it.hit.chunk.path }.distinct().size,
            truncated = truncated,
        )
    }

    private fun rankScore(value: AggregatedHit): Double {
        val primaryBoost = if (0 in value.queryIndexes) 0.04 else 0.0
        val corroborationBoost = (value.queryIndexes.size - 1).coerceAtLeast(0) * 0.06
        return value.hit.score + primaryBoost + corroborationBoost
    }

    private fun formatHit(value: AggregatedHit, content: String): String {
        val hit = value.hit
        val channels = hit.channels.entries
            .sortedByDescending { it.value }
            .take(4)
            .joinToString(" ") { (name, score) -> "$name=${formatScore(score, 3)}" }
        return buildString {
            append("## ${hit.chunk.path}:${hit.chunk.startLine}-${hit.chunk.endLine}\n")
            hit.chunk.symbol?.takeIf(String::isNotBlank)?.let { append("symbol: $it\n") }
            append("matched_queries: ${value.queryIndexes.sorted().joinToString(", ") { (it + 1).toString() }}\n")
            append("score: ${formatScore(rankScore(value), 4)} · via: ${hit.source}")
            if (channels.isNotEmpty()) append(" · $channels")
            append("\n```" + hit.chunk.language + "\n")
            append(content)
            append("\n```\n")
        }
    }

    private fun keyOf(hit: ContextSearchHit): String = "${hit.chunk.path}:${hit.chunk.startLine}-${hit.chunk.endLine}"

    private fun estimateTokens(value: String): Int = (value.length + 3) / 4

    private fun formatScore(value: Double, precision: Int): String =
        String.format(Locale.ROOT, "%.${precision}f", value)

    private data class AggregatedHit(
        var hit: ContextSearchHit,
        val queryIndexes: LinkedHashSet<Int> = linkedSetOf(),
    )
}

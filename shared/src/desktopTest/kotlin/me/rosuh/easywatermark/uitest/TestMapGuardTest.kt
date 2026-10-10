package me.rosuh.easywatermark.uitest

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ADR-0032 P1: structural map↔code guard. No UI, no YAML library.
 *
 * Repo-root resolution follows [me.rosuh.easywatermark.ui.ProductShellHostOverlayTest]
 * (cwd / cwd.parent / explicit relative), then walks up for `settings.gradle.kts`.
 *
 * `cases[].ref` token rules (documented for map authors):
 * - Take the substring before the first `.` or space. That token is the class or
 *   flow name (`PickerFlowUITests.testX` → `PickerFlowUITests`;
 *   `DesktopWatermarkFlow --headless` → `DesktopWatermarkFlow`).
 * - The token must appear as a literal in repo source under `app/`, `shared/`,
 *   `iosApp/`, `macrobenchmark/`, **or** `desktopApp/`. The last root is included
 *   because P0 already binds `DesktopWatermarkFlow`, which lives only in
 *   `desktopApp/` (not in the four dirs named in the ADR prose).
 * - `trigger.kind: tag` values must appear as literals in `shared/src` Kotlin.
 *   `kind: system` and `kind: prop` are exempt (no product testTag).
 */
class TestMapGuardTest {

    @Test
    fun map_nodes_edges_sources_tags_and_case_refs_are_consistent() {
        val root = repoRoot()
        val mapFile = File(root, "docs/testmap/map.yaml")
        assertTrue(mapFile.isFile, "missing ${mapFile.absolutePath}")
        val parsed = MapYamlSubset.parse(mapFile.readText())
        val nodes = parsed.list("nodes")
        val edges = parsed.list("edges")
        assertTrue(nodes.isNotEmpty(), "nodes must be non-empty")
        assertTrue(edges.isNotEmpty(), "edges must be non-empty")

        val nodeIds = nodes.map { it.string("id") }
        val dupNodes = duplicates(nodeIds)
        assertTrue(dupNodes.isEmpty(), "duplicate node ids: $dupNodes")
        val nodeIdSet = nodeIds.toSet()

        for (node in nodes) {
            val source = node.string("source")
            val file = File(root, source)
            assertTrue(file.isFile, "node ${node.string("id")}: source missing on disk: $source")
        }

        val edgeIds = edges.map { it.string("id") }
        val dupEdges = duplicates(edgeIds)
        assertTrue(dupEdges.isEmpty(), "duplicate edge ids: $dupEdges")

        val sharedKotlin = collectKotlinSources(File(root, "shared/src"))
        val caseSearchRoots = listOf("app", "shared", "iosApp", "macrobenchmark", "desktopApp")
            .map { File(root, it) }
            .filter { it.isDirectory }
        val caseCorpus = collectSourceTexts(caseSearchRoots)

        for (edge in edges) {
            val id = edge.string("id")
            val from = edge.string("from")
            val to = edge.string("to")
            assertTrue(from in nodeIdSet, "edge $id: from '$from' is not a node")
            assertTrue(to in nodeIdSet, "edge $id: to '$to' is not a node")
            val trigger = edge.child("trigger")
            when (val kind = trigger.string("kind")) {
                "tag" -> {
                    val tag = trigger.string("value")
                    assertTrue(
                        sharedKotlin.any { it.contains(tag) },
                        "edge $id: trigger tag '$tag' not found in shared/src Kotlin",
                    )
                }
                "system", "prop" -> Unit
                else -> fail("edge $id: unknown trigger.kind '$kind'")
            }
            val cases = edge.optionalList("cases")
            for (case in cases) {
                val ref = case.string("ref")
                val token = refToken(ref)
                assertTrue(
                    caseCorpus.any { it.contains(token) },
                    "edge $id: cases.ref '$ref' token '$token' not found in app/shared/iosApp/macrobenchmark/desktopApp",
                )
            }
        }

        val copyFile = File(root, "docs/testmap/copy.yaml")
        assertTrue(copyFile.isFile, "missing ${copyFile.absolutePath}")
        val copy = MapYamlSubset.parse(copyFile.readText())
        val copyNodes = copy.child("nodes")
        val copyEdges = copy.child("edges")
        val copyCases = copy.child("cases")
        for (nid in nodeIds) {
            val row = copyNodes.optionalChild(nid)
            assertTrue(row != null, "copy.yaml missing nodes.$nid")
            requireNotNull(row)
            assertTrue(row.string("en").isNotBlank() && row.string("zh").isNotBlank(), "copy.yaml nodes.$nid needs en/zh")
        }
        for (eid in edgeIds) {
            val row = copyEdges.optionalChild(eid)
            assertTrue(row != null, "copy.yaml missing edges.$eid")
            requireNotNull(row)
            assertTrue(row.string("en").isNotBlank() && row.string("zh").isNotBlank(), "copy.yaml edges.$eid needs en/zh")
        }
        val refs = edges.flatMap { it.optionalList("cases") }.map { it.string("ref") }.toSet()
        for (ref in refs) {
            val row = copyCases.optionalChild(ref)
            assertTrue(row != null, "copy.yaml missing cases.$ref")
            requireNotNull(row)
            assertTrue(row.string("en").isNotBlank() && row.string("zh").isNotBlank(), "copy.yaml cases.$ref needs en/zh")
        }

        val artemis = File(root, "docs/testing/artemis-cases.json")
        assertTrue(artemis.isFile, "missing ${artemis.absolutePath}")
        val artemisText = artemis.readText()
        assertTrue(
            artemisText.contains("\"source_path\": \"docs/testmap/map.yaml\""),
            "artemis-cases.json must point at docs/testmap/map.yaml; it is not a second topology",
        )
        for (eid in edgeIds) {
            assertTrue(
                artemisText.contains("\"id\": \"$eid\""),
                "artemis-cases.json missing map edge $eid",
            )
        }

        val agentDevice = File(root, "docs/testing/agent-device-cases.json")
        assertTrue(agentDevice.isFile, "missing ${agentDevice.absolutePath}")
        val agentText = agentDevice.readText()
        assertTrue(
            agentText.contains("\"source_path\": \"docs/testmap/map.yaml\""),
            "agent-device-cases.json must point at docs/testmap/map.yaml; it is not a second topology",
        )
        for (eid in edgeIds) {
            assertTrue(
                agentText.contains("\"id\": \"$eid\""),
                "agent-device-cases.json missing map edge $eid",
            )
        }
    }

    private fun refToken(ref: String): String {
        val cut = ref.indexOfAny(charArrayOf('.', ' '))
        return if (cut < 0) ref else ref.substring(0, cut)
    }

    private fun duplicates(ids: List<String>): List<String> =
        ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.sorted()

    private fun collectKotlinSources(dir: File): List<String> =
        dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.readText() }
            .toList()

    private fun collectSourceTexts(roots: List<File>): List<String> {
        val exts = setOf("kt", "kts", "java", "swift")
        return roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension in exts }
                .filter { path ->
                    val rel = itSafeRel(root, path)
                    !rel.contains("/build/") && !rel.contains("/.gradle/")
                }
                .map { it.readText() }
        }
    }

    private fun itSafeRel(root: File, file: File): String =
        file.relativeTo(root).path.replace('\\', '/')

    private fun repoRoot(): File {
        val cwd = File("").absoluteFile
        val candidates = listOf(
            File("docs/testmap/map.yaml"),
            File(cwd, "docs/testmap/map.yaml"),
            File(cwd.parentFile, "docs/testmap/map.yaml"),
        )
        candidates.firstOrNull { it.isFile }?.let { return it.parentFile.parentFile.parentFile }
        var walk: File? = cwd
        while (walk != null) {
            if (File(walk, "settings.gradle.kts").isFile && File(walk, "docs/testmap/map.yaml").isFile) {
                return walk
            }
            walk = walk.parentFile
        }
        fail("could not resolve repo root from ${cwd.absolutePath}")
    }
}

/**
 * Hand-rolled YAML subset matching [scripts/generate_testmap.py]: mappings, lists,
 * scalars, comments, quoted strings, `[]` / `[a, b]`. Not a general YAML loader.
 */
internal object MapYamlSubset {
    fun parse(text: String): YamlMap {
        val lines = mutableListOf<Pair<Int, String>>()
        text.lineSequence().forEachIndexed { index, raw ->
            val stripped = stripComment(raw)
            if (stripped.isBlank()) return@forEachIndexed
            require('\t' !in stripped) { "line ${index + 1}: tabs are not allowed; use spaces" }
            lines += (index + 1) to stripped
        }
        var pos = 0
        fun peek(): Pair<Int, String>? = lines.getOrNull(pos)
        fun indentOf(line: String): Int = line.length - line.trimStart().length

        fun parseScalar(raw: String): Any? {
            val value = raw.trim()
            if (value == "[]") return emptyList<Any?>()
            if (value.startsWith("[") && value.endsWith("]")) {
                val inner = value.substring(1, value.length - 1).trim()
                if (inner.isEmpty()) return emptyList<Any?>()
                return splitTopCommas(inner).map { parseScalar(it) }
            }
            if ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))
            ) {
                return value.substring(1, value.lastIndex)
            }
            when (value) {
                "true" -> return true
                "false" -> return false
                "null" -> return null
            }
            if (value.all { it.isDigit() } ||
                (value.startsWith("-") && value.drop(1).all { it.isDigit() })
            ) {
                return value.toInt()
            }
            return value
        }

        lateinit var parseMapping: (Int) -> MutableMap<String, Any?>
        lateinit var parseList: (Int) -> MutableList<Any?>
        lateinit var parseValue: (Int) -> Any?

        parseValue = fun(minIndent: Int): Any? {
            val item = peek() ?: return null
            val indent = indentOf(item.second)
            if (indent < minIndent) return null
            val body = item.second.trim()
            return if (body.startsWith("- ") || body == "-") parseList(indent) else parseMapping(indent)
        }

        parseMapping = { indent ->
            val result = linkedMapOf<String, Any?>()
            while (pos < lines.size) {
                val (lineno, line) = lines[pos]
                val cur = indentOf(line)
                if (cur < indent) break
                require(cur <= indent) { "line $lineno: unexpected indent in mapping" }
                val body = line.trim()
                if (body.startsWith("-")) break
                require(':' in body) { "line $lineno: expected 'key: value', got $body" }
                val key = body.substringBefore(':').trim()
                val rest = body.substringAfter(':').trim()
                pos += 1
                if (rest.isNotEmpty()) {
                    result[key] = parseScalar(rest)
                    continue
                }
                val nxt = peek()
                if (nxt == null) {
                    result[key] = null
                    continue
                }
                val nIndent = indentOf(nxt.second)
                if (nIndent <= indent) {
                    result[key] = null
                    continue
                }
                result[key] = if (nxt.second.trim().startsWith("-")) {
                    parseList(nIndent)
                } else {
                    parseMapping(nIndent)
                }
            }
            result
        }

        parseList = { indent ->
            val result = mutableListOf<Any?>()
            while (pos < lines.size) {
                val (lineno, line) = lines[pos]
                val cur = indentOf(line)
                if (cur < indent) break
                require(cur <= indent) { "line $lineno: unexpected indent in list" }
                val body = line.trim()
                if (!body.startsWith("-")) break
                val itemBody = body.drop(1).trim()
                pos += 1
                if (itemBody.isEmpty()) {
                    val nxt = peek()
                    if (nxt == null || indentOf(nxt.second) <= indent) {
                        result += null
                    } else {
                        result += parseValue(indentOf(nxt.second))
                    }
                    continue
                }
                require(!itemBody.startsWith("-")) {
                    "line $lineno: nested dash without indent is unsupported"
                }
                val looksMap = ':' in itemBody &&
                    !itemBody.startsWith("[") &&
                    !itemBody.startsWith("'") &&
                    !itemBody.startsWith("\"")
                if (looksMap) {
                    val key = itemBody.substringBefore(':').trim()
                    val rest = itemBody.substringAfter(':').trim()
                    val mapping = linkedMapOf<String, Any?>(
                        key to if (rest.isEmpty()) null else parseScalar(rest),
                    )
                    val nxt = peek()
                    if (nxt != null) {
                        val nIndent = indentOf(nxt.second)
                        if (nIndent > indent && !nxt.second.trim().startsWith("-")) {
                            mapping.putAll(parseMapping(nIndent))
                        }
                    }
                    result += mapping
                } else {
                    result += parseScalar(itemBody)
                }
            }
            result
        }

        val root = parseMapping(0)
        require(pos == lines.size) {
            val leftover = lines[pos]
            "line ${leftover.first}: unparsed content: ${leftover.second}"
        }
        return YamlMap(root)
    }

    private fun stripComment(line: String): String {
        var inSingle = false
        var inDouble = false
        val out = StringBuilder()
        for (ch in line) {
            when {
                ch == '\'' && !inDouble -> inSingle = !inSingle
                ch == '"' && !inSingle -> inDouble = !inDouble
                ch == '#' && !inSingle && !inDouble -> break
            }
            if (!(ch == '#' && !inSingle && !inDouble)) out.append(ch)
        }
        return out.toString().trimEnd()
    }

    private fun splitTopCommas(text: String): List<String> {
        val parts = mutableListOf<String>()
        val buf = StringBuilder()
        var depth = 0
        var inSingle = false
        var inDouble = false
        for (ch in text) {
            when {
                ch == '\'' && !inDouble -> {
                    inSingle = !inSingle
                    buf.append(ch)
                }
                ch == '"' && !inSingle -> {
                    inDouble = !inDouble
                    buf.append(ch)
                }
                ch == '[' && !inSingle && !inDouble -> {
                    depth += 1
                    buf.append(ch)
                }
                ch == ']' && !inSingle && !inDouble -> {
                    depth -= 1
                    buf.append(ch)
                }
                ch == ',' && depth == 0 && !inSingle && !inDouble -> {
                    parts += buf.toString().trim()
                    buf.clear()
                }
                else -> buf.append(ch)
            }
        }
        if (buf.isNotEmpty()) parts += buf.toString().trim()
        return parts.filter { it.isNotEmpty() }
    }
}

internal class YamlMap(private val data: Map<String, Any?>) {
    fun string(key: String): String {
        val value = data[key]
        require(value is String && value.isNotEmpty()) { "missing string '$key' in $data" }
        return value
    }

    fun child(key: String): YamlMap {
        val value = data[key]
        require(value is Map<*, *>) { "missing mapping '$key'" }
        @Suppress("UNCHECKED_CAST")
        return YamlMap(value as Map<String, Any?>)
    }

    fun optionalChild(key: String): YamlMap? {
        val value = data[key] ?: return null
        require(value is Map<*, *>) { "'$key' must be a mapping" }
        @Suppress("UNCHECKED_CAST")
        return YamlMap(value as Map<String, Any?>)
    }

    fun list(key: String): List<YamlMap> = optionalList(key).also {
        require(it.isNotEmpty() || data[key] is List<*>) { "missing list '$key'" }
    }

    fun optionalList(key: String): List<YamlMap> {
        val value = data[key] ?: return emptyList()
        require(value is List<*>) { "'$key' must be a list" }
        return value.map { item ->
            require(item is Map<*, *>) { "list '$key' item must be a mapping" }
            @Suppress("UNCHECKED_CAST")
            YamlMap(item as Map<String, Any?>)
        }
    }
}

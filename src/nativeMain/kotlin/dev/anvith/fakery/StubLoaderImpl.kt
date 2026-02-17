package dev.anvith.fakery

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

actual fun loadStubsFromDirectory(directoryPath: String): List<StubDefinition> {
    val root = Path(directoryPath)
    return collectJsonFiles(root, root)
        .sortedBy { (sortKey, _) -> sortKey }
        .flatMap { (_, path) -> loadStubsFromFile(path.toString()) }
}

actual fun loadStubsFromFile(filePath: String): List<StubDefinition> {
    val content = SystemFileSystem.source(Path(filePath)).buffered().readString()
    return content.trim().let { s ->
        if (s.startsWith("[")) parseStubs(s) else listOf(parseStub(s))
    }
}

/**
 * Recursively collects all `.json` files under [dir], paired with their sort key.
 *
 * The sort key is the path relative to [root] with directory separators replaced by `_`,
 * so `auth/login.json` → `auth_login.json` and `users/list.json` → `users_list.json`.
 * This produces deterministic alphabetical ordering across nested directories.
 */
private fun collectJsonFiles(dir: Path, root: Path): List<Pair<String, Path>> {
    val result = mutableListOf<Pair<String, Path>>()
    for (child in SystemFileSystem.list(dir)) {
        val metadata = SystemFileSystem.metadataOrNull(child) ?: continue
        if (metadata.isDirectory) {
            result += collectJsonFiles(child, root)
        } else if (child.name.endsWith(".json")) {
            val sortKey = child.toString()
                .removePrefix(root.toString())
                .trimStart('/', '\\')
                .replace('/', '_')
                .replace('\\', '_')
            result.add(sortKey to child)
        }
    }
    return result
}

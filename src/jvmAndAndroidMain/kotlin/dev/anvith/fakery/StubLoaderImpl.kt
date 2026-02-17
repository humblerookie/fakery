package dev.anvith.fakery

import java.io.File

actual fun loadStubsFromDirectory(directoryPath: String): List<StubDefinition> {
    val root = File(directoryPath)
    return root.walkTopDown()
        .filter { it.isFile && it.extension == "json" }
        .sortedBy { file ->
            // Sort key: relative path from root with OS separator replaced by '_'.
            // e.g. root=stubs/, file=stubs/auth/login.json → sort key "auth_login.json"
            file.relativeTo(root).path.replace(File.separatorChar, '_')
        }
        .flatMap { loadStubsFromFile(it.absolutePath) }
        .toList()
}

actual fun loadStubsFromFile(filePath: String): List<StubDefinition> {
    val content = File(filePath).readText()
    return content.trim().let { s ->
        if (s.startsWith("[")) parseStubs(s) else listOf(parseStub(s))
    }
}

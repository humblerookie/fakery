package dev.anvith.fakery

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

actual fun loadStubsFromDirectory(directoryPath: String): List<StubDefinition> {
    val dir = Path(directoryPath)
    return SystemFileSystem.list(dir)
        .filter { it.name.endsWith(".json") }
        .flatMap { loadStubsFromFile(it.toString()) }
}

actual fun loadStubsFromFile(filePath: String): List<StubDefinition> {
    val content = SystemFileSystem.source(Path(filePath)).buffered().readString()
    return content.trim().let { s ->
        if (s.startsWith("[")) parseStubs(s) else listOf(parseStub(s))
    }
}

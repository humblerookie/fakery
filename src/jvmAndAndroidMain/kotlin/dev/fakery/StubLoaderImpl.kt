package dev.fakery

import java.io.File

actual fun loadStubsFromDirectory(directoryPath: String): List<StubDefinition> =
    File(directoryPath)
        .listFiles { f -> f.isFile && f.extension == "json" }
        ?.flatMap { loadStubsFromFile(it.absolutePath) }
        ?: emptyList()

actual fun loadStubsFromFile(filePath: String): List<StubDefinition> {
    val content = File(filePath).readText()
    return content.trim().let { s ->
        if (s.startsWith("[")) parseStubs(s) else listOf(parseStub(s))
    }
}

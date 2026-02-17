package dev.anvith.fakery.server

import dev.anvith.fakery.fakeryFromDirectory

/**
 * Fakery standalone server — loads stubs from a directory and serves them over HTTP.
 *
 * Usage:
 * ```
 * java -jar fakery-server.jar --directory <path> [--port <port>]
 * ```
 *
 * Options:
 *   --directory  Path to a directory containing `.json` stub files (required).
 *                Subdirectories are walked recursively. Files are loaded in
 *                alphabetical order of their fully-qualified name, where path
 *                separators are replaced by underscores:
 *                `auth/login.json` → sorts as `auth_login.json`.
 *
 *   --port       Port to listen on (optional, defaults to 8080).
 *                Pass 0 to let the OS pick a free port.
 */
fun main(args: Array<String>) {
    val argMap = parseArgs(args)

    val directory = argMap["--directory"] ?: run {
        System.err.println("Error: --directory <path> is required.")
        System.err.println()
        System.err.println("Usage: fakery-server --directory <path> [--port <port>]")
        System.exit(1)
        return
    }

    val port = argMap["--port"]?.toIntOrNull() ?: 8080

    val server = fakeryFromDirectory(port = port, directoryPath = directory)
    server.start()

    println("┌─────────────────────────────────────────┐")
    println("│           Fakery Standalone Server       │")
    println("├─────────────────────────────────────────┤")
    println("│  Listening on : ${server.baseUrl.padEnd(24)}│")
    println("│  Stubs from   : ${directory.take(24).padEnd(24)}│")
    println("│  Press Ctrl+C to stop                   │")
    println("└─────────────────────────────────────────┘")

    Runtime.getRuntime().addShutdownHook(Thread {
        println("\nShutting down Fakery server...")
        server.stop()
    })

    // Block the main thread until the process is killed.
    Thread.currentThread().join()
}

/** Parses `--key value` pairs from [args], ignoring bare flags with no following value. */
private fun parseArgs(args: Array<String>): Map<String, String> {
    val result = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i]
        if (key.startsWith("--") && i + 1 < args.size && !args[i + 1].startsWith("--")) {
            result[key] = args[i + 1]
            i += 2
        } else {
            i++
        }
    }
    return result
}

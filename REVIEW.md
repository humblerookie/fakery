# Fakery Code Review

> Reviewed: 2026-02-16  
> Reviewer: Senior KMP Engineer  
> Codebase: `src/` + `sample/src/`, `build.gradle.kts`, `sample/build.gradle.kts`, `gradle/libs.versions.toml`

---

## 1. API Design

### ✅ What works

- `fakery()` as a top-level entry point with multiple overloads is idiomatic Kotlin.
- `FakeryServer` interface is clean: `start()`, `stop()`, `addStub()`, `clearStubs()` cover the testing lifecycle.
- `StubDefinition` / `StubRequest` / `StubResponse` data classes with sensible defaults (`method = "GET"`, `status = 200`) are easy to construct.
- Port `0` defaulting to OS-assigned port is the right default for tests.

### ❌ Issues

**1.1 `parseStub` and `parseStubs` are public but should be internal**  
`src/commonMain/kotlin/dev/fakery/StubLoader.kt`, lines 16–26  
These are parsing utilities that exist to serve `fakery(json = ...)`. Exposing them makes them part of the API contract and forces semver concerns when you want to change parsing behavior.  
```kotlin
// Before (public — leaks implementation)
fun parseStub(json: String): StubDefinition
fun parseStubs(json: String): List<StubDefinition>

// After
internal fun parseStub(json: String): StubDefinition
internal fun parseStubs(json: String): List<StubDefinition>
```

**1.2 `FakeryServer` does not implement `AutoCloseable` / `Closeable`**  
`src/commonMain/kotlin/dev/fakery/FakeryServer.kt`  
Users can't write `server.use { ... }` or leverage Kotlin's `use {}` operator. For a test-lifecycle object, this is an ergonomic gap. `AutoCloseable` is available in `kotlin.io.use` for KMP.
```kotlin
// After
interface FakeryServer : AutoCloseable {
    // ... existing members ...

    override fun close() = stop()  // default in interface body
}
```

**1.3 First-match-wins stub resolution is undocumented**  
`src/commonMain/kotlin/dev/fakery/StubMatcher.kt`, line 34 (`stubs.firstOrNull { ... }`)  
When multiple stubs could match a request (e.g., same path, one with headers and one without), the first registered stub wins. WireMock uses last-registered-wins. Neither is wrong, but this is surprising behaviour that has zero documentation in `FakeryServer.addStub` or the README.

**1.4 No way to inspect registered stubs**  
`FakeryServer` has `addStub` and `clearStubs` but no read access to the current stub list. Users can't assert "this stub is registered" in tests, or build tooling on top of it.

**1.5 `fakery(json = ...)` array detection is positional, not structural**  
`src/commonMain/kotlin/dev/fakery/Fakery.kt`, line 24  
```kotlin
if (s.startsWith("[")) parseStubs(s) else listOf(parseStub(s))
```
Correct after `trim()`, but the heuristic is a code smell. If a stub body starts with `[` (a JSON array response), a malformed outer document could hit the wrong branch. Use `Json.parseToJsonElement` and check `instanceof JsonArray` instead.

---

## 2. Correctness

### 🔴 Critical Bugs

**2.1 JVM thread safety: reads on `stubs` are not protected**  
`src/jvmAndAndroidMain/kotlin/dev/fakery/FakeryServerImpl.kt`, lines 29–30  
`src/commonMain/kotlin/dev/fakery/FakeryApplication.kt`, lines 19–27  

`addStub` and `clearStubs` both `synchronized(stubs)` for mutations, but `matchStub` iterates `stubs` via `stubs.any {}` and `stubs.firstOrNull {}` *without* holding the lock. This is a race: a `clearStubs()` call concurrent with an in-flight request causes `ConcurrentModificationException` on JVM.

```kotlin
// Before — mutation protected, reads not protected
override fun addStub(stub: StubDefinition) { synchronized(stubs) { stubs.add(stub) } }
// matchStub iterates stubs without a lock in FakeryApplication.kt

// After — use CopyOnWriteArrayList for lock-free read safety
import java.util.concurrent.CopyOnWriteArrayList

internal class JvmFakeryServer(
    initialPort: Int,
    private val stubs: MutableList<StubDefinition> = CopyOnWriteArrayList(),
) : FakeryServer {
    // addStub / clearStubs no longer need synchronized()
    override fun addStub(stub: StubDefinition) { stubs.add(stub) }
    override fun clearStubs() { stubs.clear() }
}
```
`CopyOnWriteArrayList` is the correct structure here: iteration (reads) are lock-free and safe; writes take a copy. Perfect for low-write / high-read test scenarios.

**2.2 Native thread safety: zero synchronization**  
`src/nativeMain/kotlin/dev/fakery/FakeryServerImpl.kt`, lines 27–28  

```kotlin
override fun addStub(stub: StubDefinition) { stubs.add(stub) }  // NO sync
override fun clearStubs() { stubs.clear() }                       // NO sync
```
Under Kotlin/Native's new memory model (default since Kotlin 1.7.20), shared mutable state accessed from multiple threads is a data race. The Ktor CIO server processes requests on coroutines that can span multiple threads. Use `AtomicReference` wrapping an immutable list, or use `kotlinx-atomicfu`'s `AtomicArray`:

```kotlin
// After (using AtomicReference + immutable copy)
import kotlin.concurrent.AtomicReference

private val _stubs = AtomicReference(stubs.toList())

override fun addStub(stub: StubDefinition) {
    do {
        val old = _stubs.value
        val new = old + stub
    } while (!_stubs.compareAndSet(old, new))
}

override fun clearStubs() { _stubs.value = emptyList() }
```
Then pass `{ _stubs.value }` as a lambda into `fakeryModule` rather than a direct list reference.

**2.3 Native: port selection is a random number with no collision check**  
`src/nativeMain/kotlin/dev/fakery/FakeryServerImpl.kt`, line 14  

```kotlin
// Before — random port in ephemeral range, no availability check
private val _port: Int = if (initialPort != 0) initialPort else (49152..65535).random()
```

If the randomly chosen port is already bound, Ktor will throw at `start()`. This is inherently flaky in CI where many test processes run in parallel. The JVM correctly uses `ServerSocket(0)` to let the OS assign a free port. Ktor CIO supports port `0` natively — you can read the actual bound port back after `start()`:

```kotlin
// After
private var _port: Int = initialPort  // may be 0

override val port: Int get() = _port

override fun start() {
    server = embeddedServer(CIO, port = _port) {
        fakeryModule(stubs)
    }.also {
        it.start(wait = false)
        if (_port == 0) {
            _port = it.engine.resolvedConnectors().first().port
        }
    }
}
```

### 🟡 Medium Bugs

**2.4 Header value comparison is incorrectly case-insensitive**  
`src/commonMain/kotlin/dev/fakery/StubMatcher.kt`, line 29  

```kotlin
incomingHeaders[key]?.equals(value, ignoreCase = true) == true
```

HTTP header *names* are case-insensitive (and Ktor's `Headers` already handles key lookup case-insensitively). Header *values* are **not** case-insensitive in general. `Authorization: Bearer TOKEN` and `Authorization: Bearer token` are different credentials. This will cause false-positive stub matches for authorization headers.

```kotlin
// After — case-sensitive value comparison
incomingHeaders[key] == value
```

**2.5 Body parser inconsistency: stub uses lenient `FakeryJson`, incoming request uses strict `Json`**  
`src/commonMain/kotlin/dev/fakery/StubMatcher.kt`, line 22  

```kotlin
runCatching { Json.parseToJsonElement(call.receiveText()) }.getOrNull()
//            ^^^^ strict default Json
```

`parseStub` uses `FakeryJson` (which has `isLenient = true`, `ignoreUnknownKeys = true`). If a stub file was parsed leniently, its `body` field was parsed leniently too. But the incoming request body is parsed strictly. Parsing failures are silently swallowed (`.getOrNull()` → `null`), causing the body check to fail with no diagnostic. At minimum, log the parse error. Ideally use `FakeryJson` consistently:

```kotlin
runCatching { FakeryJson.parseToJsonElement(call.receiveText()) }.getOrElse { null }
```

**2.6 Error response body is JSON-unsafe (string interpolation into JSON)**  
`src/commonMain/kotlin/dev/fakery/FakeryApplication.kt`, line 28  

```kotlin
text = """{"error":"No stub for $method $path"}"""
```

If `path` contains a `"` or `\`, the output is invalid JSON (e.g., a request to `/path/"evil"` produces `{"error":"No stub for GET /path/"evil""}`). Fix:

```kotlin
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

text = buildJsonObject {
    put("error", "No stub for $method $path")
}.toString()
```

**2.7 `receiveText()` can only be called once per request**  
`src/commonMain/kotlin/dev/fakery/StubMatcher.kt`, lines 20–23  

Calling `call.receiveText()` consumes the request body channel. If any other pipeline stage or plugin tries to read the body after `matchStub`, it gets an empty or closed stream. The current architecture only reads the body in `matchStub` and never uses it again (the response comes from the stub), so this is safe *today* — but it's a landmine. Add a comment.

---

## 3. Code Quality

**3.1 `FakeryJson` is named like a class (PascalCase for a property)**  
`src/commonMain/kotlin/dev/fakery/StubLoader.kt`, line 4  

```kotlin
// Before
internal val FakeryJson = Json { ... }

// After — standard Kotlin property naming
internal val fakeryJson = Json { ... }
```

**3.2 `intercept(ApplicationCallPipeline.Call)` is a deprecated Ktor pattern**  
`src/commonMain/kotlin/dev/fakery/FakeryApplication.kt`, line 16  

Ktor 3.x recommends routing plugins or `createApplicationPlugin` instead of raw pipeline interception. The current approach bypasses routing entirely which is intentional, but the API is soft-deprecated. Consider:

```kotlin
val FakeryPlugin = createApplicationPlugin("Fakery", { stubs: List<StubDefinition> }) {
    onCall { call ->
        val stub = matchStub(call, pluginConfig)
        // respond ...
    }
}
```

**3.3 `@Suppress("DEPRECATION")` for `URL.openConnection()` in integration test**  
`src/jvmTest/kotlin/dev/fakery/FakeryIntegrationTest.kt`, line 91  

The integration test uses the deprecated raw `HttpURLConnection`. The sample module already demonstrates using Ktor client. The integration test should use the same. This also removes the need for the `@Suppress`.

**3.4 Duplicate JSON array detection logic (3 copies)**  
The pattern `if (s.startsWith("[")) parseStubs(s) else listOf(parseStub(s))` appears in:
- `Fakery.kt` line 24
- `jvmAndAndroidMain/StubLoaderImpl.kt` line 9
- `nativeMain/StubLoaderImpl.kt` line 8

Extract to a single `internal fun parseStubsOrSingle(json: String)` in `StubLoader.kt`.

**3.5 `NativeFakeryServer` and `JvmFakeryServer` are nearly identical**  
Both classes have identical `start()`, `stop()`, `baseUrl` logic. Only `findFreePort()` and synchronization differ. The shared logic in `jvmAndAndroidMain/FakeryServerImpl.kt` could be abstracted into an `abstract class BaseFakeryServer` in a shared source set, reducing duplication.

---

## 4. KMP Architecture

**4.1 `kotlinx-io` declared in `commonMain` but only used in `nativeMain`**  
`build.gradle.kts`, line 33  

```kotlin
// Before — commonMain pulls kotlinx-io into JVM + Android unnecessarily
commonMain.dependencies {
    implementation(libs.kotlinx.io)
}

// After — native only
nativeMain.dependencies {
    implementation(libs.kotlinx.io)
}
```

The JVM/Android implementation uses `java.io.File`; `kotlinx-io` adds ~100 KB to the JVM artifact for zero benefit.

**4.2 `expect/actual` split is correct but `loadStubsFromFile`/`loadStubsFromDirectory` should be `internal`**  
`src/commonMain/kotlin/dev/fakery/StubLoader.kt`, lines 33–40  

These `expect` functions are `public`, which means they are part of the library's API surface. But they are platform-specific file I/O helpers. If the implementations ever need to change (e.g., to support `okio` on Android), the public API is locked. Mark them `internal`:

```kotlin
internal expect fun loadStubsFromDirectory(directoryPath: String): List<StubDefinition>
internal expect fun loadStubsFromFile(filePath: String): List<StubDefinition>
```
The `fakeryFromDirectory` and `fakeryFromFile` top-level functions in `Fakery.kt` are the public entry points and remain public.

**4.3 `jvmAndAndroidMain` intermediate source set is set up correctly**  
The manual `by creating { dependsOn(commonMain.get()) }` pattern followed by `jvmMain.get().dependsOn(jvmAndAndroidMain)` and `androidMain.get().dependsOn(jvmAndAndroidMain)` is the correct approach for non-default intermediate source sets with `applyDefaultHierarchyTemplate()`. ✅

**4.4 Android usability concern: `embeddedServer` on Android requires background threading**  
`src/jvmAndAndroidMain/kotlin/dev/fakery/FakeryServerImpl.kt`, line 22  

`start(wait = false)` is non-blocking, which is correct. However, on Android, `ServerSocket` and port binding have stricter security restrictions — the library should document that this is intended for **Android unit tests (JVM)** only, not for use in Android instrumented tests on a real device/emulator, where `localhost` server binding may require the `INTERNET` permission and is subject to security policies.

**4.5 `ktor-server-core` in `commonMain` is a heavy common dependency**  
Every platform target gets the full Ktor server framework (request parsing, pipeline, routing infrastructure) in its transitive closure. For a KMP testing library this is expected — just make sure the library is declared as `testImplementation` in consuming projects.

**4.6 No `nativeTest` source set**  
There are zero native-platform tests. Native behaviour (port allocation, file I/O, thread safety) is untested. At minimum, add `linuxX64Test` or a commonTest that exercises the parsing logic on native.

---

## 5. Test Coverage

### What's covered
- `StubParsingTest` (commonTest): single stub, array, method/status/body/header defaults, `fakery()` constructors — good coverage of the happy path for deserialization.
- `FakeryIntegrationTest` (jvmTest): GET/POST/DELETE routing, header matching (present/absent), `addStub`/`clearStubs` runtime mutation. Full E2E coverage for the core use case.
- `UserApiClientTest` (sample jvmTest): demonstrates real-world usage with Ktor client, all CRUD operations, 404 surfacing.

### What's missing

**5.1 Body matching is not tested anywhere.**  
There is no test that sends a request with a JSON body and verifies the correct body-matched stub fires (or doesn't, on mismatch). This is a core feature with zero test coverage.

**5.2 Case-insensitive method matching is untested.**  
No test sends `method: "get"` (lowercase) or `"Post"` and confirms it matches a stub with `"GET"`/`"POST"`. This is explicitly documented as case-insensitive.

**5.3 Query-string stripping is untested.**  
`GET /users?page=1` should match a stub for `/users`. There is no test for this.

**5.4 `fakeryFromDirectory` and `fakeryFromFile` are completely untested.**  
Both are public entry points with platform-specific implementations. Zero test coverage.

**5.5 The "required header not matched" test is testing the wrong thing**  
`sample/src/jvmTest/kotlin/sample/UserApiClientTest.kt`, line 147  

The test is named `stub with required header is only matched when header is present`. It adds a duplicate `/users/me` stub (already registered in the initial array), then makes a request to `/users/99` and asserts an exception. It does **not** exercise the missing-header → 404 scenario at all. It's accidentally testing that an unregistered path returns 404, which is covered elsewhere.

**5.6 No test for `clearStubs` + re-add sequence.**  
`clearStubs()` followed by `addStub()` is a documented capability. No test covers this combined flow.

**5.7 No test for concurrent `addStub` / request racing.**  
The thread-safety guarantee is completely untested.

**5.8 No negative parsing tests.**  
What happens with malformed JSON? Missing `path`? Invalid `status`? The lenient parser silently coerces — but that behavior isn't tested.

**5.9 Flaky risk: server readiness in `FakeryIntegrationTest`**  
`setUp()` calls `server.start()` (which returns with `wait = false`) and immediately makes HTTP requests in the test. On a heavily loaded machine the server may not be bound yet. Ktor CIO's `start(wait = false)` does wait for the connector to bind before returning on JVM, but this is an implementation detail not a contract. Adding a short readiness check or using `ktor-server-test-host` eliminates the risk.

---

## 6. Build Config

**6.1 `kotlinx-io` version `0.3.0` is pre-stable API**  
`gradle/libs.versions.toml`, line 6  
`kotlinx-io` 0.3.x is still in early development with no stability guarantee. If the API changes in 0.4.x, the native file I/O implementation breaks silently. Pin to an exact version and monitor releases, or wrap it so it's swappable.

**6.2 Kotlin `2.1.0` — patch release available**  
Kotlin 2.1.20 is the latest stable as of early 2026. Consider upgrading to get compiler bugfixes relevant to KMP.

**6.3 `ktor = "3.0.3"` — minor update available**  
Ktor 3.1.x series has been released with CIO improvements. Worth updating.

**6.4 `coroutines-test` missing from the main library's `jvmTest` dependencies**  
`build.gradle.kts` has no `jvmTest` source set dependencies at all. `FakeryIntegrationTest` doesn't use coroutines in test bodies (it uses raw `HttpURLConnection`), so this isn't a bug today — but if tests are refactored to use Ktor client (see 5.9), `coroutines-test` and `ktor-server-test-host` will be needed:

```kotlin
jvmTest.dependencies {
    implementation(libs.kotlin.test)
    implementation(libs.coroutines.test)
    implementation(libs.ktor.server.test.host)  // add to catalog
}
```

**6.5 `ktor-server-core` transitive via `ktor-server-cio` — explicit dependency is redundant on native**  
`nativeMain.dependencies` declares `ktor-server-cio`; `commonMain.dependencies` also declares `ktor-server-core`. Since CIO depends on core, the commonMain declaration is a redundant direct dependency. It's not wrong (makes the intent explicit), but it should be documented.

**6.6 `settings.gradle.kts` places `pluginManagement` AFTER `include`**  
`settings.gradle.kts`, lines 3 and 5  
Gradle requires `pluginManagement {}` to be the **first** block in `settings.gradle.kts`. Placing `include(":sample")` before it works in current Gradle versions but triggers a deprecation warning in newer Gradle and will become an error. Reorder:

```kotlin
// Before
rootProject.name = "fakery"
include(":sample")
pluginManagement { ... }

// After
pluginManagement { ... }
rootProject.name = "fakery"
include(":sample")
```

---

## 7. Quick Wins (Top 5)

### QW-1 — Fix JVM concurrent read/write race with `CopyOnWriteArrayList`

**File:** `src/jvmAndAndroidMain/kotlin/dev/fakery/FakeryServerImpl.kt`  
**Impact:** Correctness / production bug

```kotlin
// Before
internal class JvmFakeryServer(
    initialPort: Int,
    private val stubs: MutableList<StubDefinition>,
) : FakeryServer {
    override fun addStub(stub: StubDefinition) { synchronized(stubs) { stubs.add(stub) } }
    override fun clearStubs() { synchronized(stubs) { stubs.clear() } }
    // matchStub reads stubs without holding the lock → race
}

// After
import java.util.concurrent.CopyOnWriteArrayList

internal actual fun createFakeryServer(port: Int, stubs: MutableList<StubDefinition>): FakeryServer =
    JvmFakeryServer(port, CopyOnWriteArrayList(stubs))  // wrap on creation

internal class JvmFakeryServer(
    initialPort: Int,
    private val stubs: MutableList<StubDefinition>,  // backed by COWAL
) : FakeryServer {
    override fun addStub(stub: StubDefinition) { stubs.add(stub) }    // COWAL is thread-safe
    override fun clearStubs() { stubs.clear() }
}
```

---

### QW-2 — Fix native port allocation (random → OS-assigned)

**File:** `src/nativeMain/kotlin/dev/fakery/FakeryServerImpl.kt`  
**Impact:** Flakiness in CI / correctness

```kotlin
// Before
private val _port: Int = if (initialPort != 0) initialPort else (49152..65535).random()
// Fixed at construction time, no availability check

// After
internal class NativeFakeryServer(
    initialPort: Int,
    private val stubs: MutableList<StubDefinition>,
) : FakeryServer {
    private var _port: Int = initialPort   // 0 = "let OS decide"
    override val port: Int get() = _port
    override val baseUrl: String get() = "http://localhost:$_port"

    override fun start() {
        server = embeddedServer(CIO, port = _port) {
            fakeryModule(stubs)
        }.also {
            it.start(wait = false)
            if (_port == 0) {
                // Read back OS-assigned port after binding
                _port = it.engine.resolvedConnectors().first().port
            }
        }
    }
}
```

---

### QW-3 — Deduplicate array-vs-object detection into one function

**Files:** `Fakery.kt:24`, `jvmAndAndroidMain/StubLoaderImpl.kt:9`, `nativeMain/StubLoaderImpl.kt:8`  
**Impact:** Code quality / DRY

```kotlin
// Add to StubLoader.kt (internal)
internal fun parseStubContent(content: String): List<StubDefinition> {
    val trimmed = content.trim()
    return if (trimmed.startsWith("[")) parseStubs(trimmed) else listOf(parseStub(trimmed))
}

// Replace all three sites:

// Fakery.kt
fun fakery(port: Int = 0, json: String): FakeryServer =
    fakery(port, parseStubContent(json))

// jvmAndAndroidMain/StubLoaderImpl.kt
actual fun loadStubsFromFile(filePath: String): List<StubDefinition> =
    parseStubContent(File(filePath).readText())

// nativeMain/StubLoaderImpl.kt
actual fun loadStubsFromFile(filePath: String): List<StubDefinition> =
    parseStubContent(SystemFileSystem.source(Path(filePath)).buffered().readString())
```

---

### QW-4 — Move `kotlinx-io` from `commonMain` to `nativeMain`

**File:** `build.gradle.kts`, line 33  
**Impact:** Reduced JVM/Android artifact size, cleaner dependency graph

```kotlin
// Before
commonMain.dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.kotlinx.io)         // ← pulled into JVM/Android for no reason
}

// After
commonMain.dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    // kotlinx-io removed from here
}

nativeMain.dependencies {
    implementation(libs.ktor.server.cio)
    implementation(libs.kotlinx.io)         // ← native only, where it's actually used
}
```

---

### QW-5 — Add `AutoCloseable` to `FakeryServer` + fix header value case sensitivity

**File A:** `src/commonMain/kotlin/dev/fakery/FakeryServer.kt`  
**File B:** `src/commonMain/kotlin/dev/fakery/StubMatcher.kt`, line 29  
**Impact:** API ergonomics + correctness of auth header matching

```kotlin
// FakeryServer.kt — After
interface FakeryServer : AutoCloseable {
    val baseUrl: String
    val port: Int
    fun start()
    fun stop()
    fun addStub(stub: StubDefinition)
    fun clearStubs()
    override fun close() = stop()
}

// Usage in tests:
fakery(json = stubs).also { it.start() }.use { server ->
    // server is automatically stopped when block exits
}
```

```kotlin
// StubMatcher.kt line 29 — After (case-sensitive value comparison)
val headersMatch = req.headers.all { (key, value) ->
    incomingHeaders[key] == value   // remove ignoreCase = true
}
```

---

## Summary Table

| # | Severity | Area | File | Fix |
|---|----------|------|------|-----|
| 2.1 | 🔴 Critical | Thread safety (JVM reads) | `JvmFakeryServerImpl.kt` | Use `CopyOnWriteArrayList` |
| 2.2 | 🔴 Critical | Thread safety (Native) | `nativeMain/FakeryServerImpl.kt` | `AtomicReference<List>` |
| 2.3 | 🔴 Critical | Native port collision | `nativeMain/FakeryServerImpl.kt` | OS-assigned port post-bind |
| 2.4 | 🟡 Medium | Header value case | `StubMatcher.kt:29` | Remove `ignoreCase = true` |
| 2.5 | 🟡 Medium | Body parse inconsistency | `StubMatcher.kt:22` | Use `FakeryJson` not `Json` |
| 2.6 | 🟡 Medium | JSON injection in error | `FakeryApplication.kt:28` | Use `buildJsonObject` |
| 1.1 | 🟡 Medium | API surface pollution | `StubLoader.kt` | Make `parseStub/parseStubs` internal |
| 4.1 | 🟡 Medium | Wrong source set dep | `build.gradle.kts:33` | Move `kotlinx-io` to nativeMain |
| 4.2 | 🟡 Medium | API surface pollution | `StubLoader.kt:33` | Make `loadStubs*` internal |
| 5.1 | 🟡 Medium | Missing tests | — | Add body-matching tests |
| 1.2 | 🟢 Low | Ergonomics | `FakeryServer.kt` | Implement `AutoCloseable` |
| 3.1 | 🟢 Low | Naming | `StubLoader.kt:4` | Rename `FakeryJson` → `fakeryJson` |
| 3.4 | 🟢 Low | DRY | 3 files | Extract `parseStubContent()` |
| 6.6 | 🟢 Low | Build config | `settings.gradle.kts` | Move `pluginManagement` to top |
| 5.5 | 🟢 Low | Misleading test | `UserApiClientTest.kt:147` | Fix or rename the test |

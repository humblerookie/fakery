# Stateful Stubs — Code Review

> Reviewed files: `StubDefinition.kt`, `StubRegistry.kt`, `IncomingRequest.kt`,
> `StubMatcher.kt`, `FakeryApplication.kt`, `FakeryServer.kt`, `Fakery.kt`,
> `jvmAndAndroidMain/FakeryServerImpl.kt`, `nativeMain/FakeryServerImpl.kt`,
> `StatefulStubTest.kt`, `FakeryIntegrationTest.kt`

---

## 1. Correctness

### 1.1 `callCount` is NOT thread-safe on JVM

**`StubRegistry.kt`, lines 35–51**

The class-level KDoc says _"paired with an **atomic** call counter"_ but the
implementation is a plain `var`:

```kotlin
internal class StatefulEntry(val definition: StubDefinition) {
    private var callCount: Int = 0          // ← plain var, no synchronisation

    fun nextResponse(): StubResponse {
        val responses = definition.responses
        val index     = minOf(callCount, responses.size - 1)
        callCount++                         // ← read-modify-write, not atomic
        return responses[index]
    }
}
```

`CopyOnWriteArrayList` (JVM impl) makes list mutations thread-safe but provides
**zero** synchronisation for the mutable state _inside_ the elements. Two
concurrent requests to the same stub can both read `callCount == 1`, both compute
`index == 1`, both increment to 2, and both return the same response — silently
skipping a sequence step. Under heavy retries this will produce incorrect behaviour
without any exception.

The documentation actively misdirects by saying "atomic".

---

### 1.2 Native `@Volatile` does not make `callCount` increments safe

**`nativeMain/FakeryServerImpl.kt`, line 49**

```kotlin
@Volatile private var entries: List<StatefulEntry> = initial.map { StatefulEntry(it) }
```

`@Volatile` is on the _list reference_, not on `callCount` inside each
`StatefulEntry`. On Native, `StatefulEntry.callCount` is still a plain
`private var`. The Kotlin/Native memory model (since 1.7.20) does allow sharing
objects between threads, so two coroutine dispatchers can race on `callCount`
exactly as on JVM. The result is the same: non-deterministic sequence advancement.

Additionally, `add` is a TOCTOU race:

```kotlin
override fun add(stub: StubDefinition) {
    entries = entries + StatefulEntry(stub)   // read … write: not atomic
}
```

Two concurrent `addStub` calls can both read the same snapshot of `entries`,
each append their entry, and one write overwrites the other — silently dropping
a stub.

---

### 1.3 `StatefulEntry.matches()` always receives `body = null` — body matching is silently broken

**`StubRegistry.kt`, line 53 / `IncomingRequest.kt`, line 34**

`matchesStub` calls the no-stubs overload of `from`:

```kotlin
// StubRegistry.kt line 53
private suspend fun matchesStub(call: ApplicationCall, stub: StubDefinition): Boolean {
    val incoming = IncomingRequest.from(call)   // ← delegates to from(call, emptyList())
    ...
    val bodyMatch = req.body == null || req.body == incoming.body
    return methodMatch && pathMatch && headersMatch && bodyMatch
}

// IncomingRequest.kt line 34
suspend fun from(call: ApplicationCall): IncomingRequest = from(call, emptyList())
```

Inside `from(call, stubs)`:

```kotlin
val needsBody = stubs.any { it.request.body != null }   // always false for emptyList()
val body: JsonElement? = if (needsBody) { … } else null  // always null
```

**Consequence:** `incoming.body` is always `null`. When `stub.request.body != null`,
`bodyMatch` evaluates to `false` and the stub never matches. Any sequence stub that
discriminates by request body silently returns 404 — no error, no warning. This is
a silent correctness bug.

There is also a latent stream-exhaustion hazard: if the `from()` call is later
fixed to read the body (by passing `listOf(stub)`), `call.receiveText()` would
be called once per entry in `matchStub`'s iteration, but Ktor's request body can
only be consumed once. The fix (§5.2 below) must address both issues together.

---

### 1.4 `IncomingRequest.from(call)` overload is a broken abstraction

**`IncomingRequest.kt`, line 33–34**

```kotlin
/** Overload used by [StatefulEntry] where the stub list isn't available. */
suspend fun from(call: ApplicationCall): IncomingRequest = from(call, emptyList())
```

The comment acknowledges that the stub list isn't threaded through, but the
consequence — that body matching is completely disabled — is not called out. The
overload exists only to paper over the fact that `matchesStub` doesn't have access
to the stub list at call time. This is a design flaw that produced a real bug
(§1.3). See §5.2 for the fix.

---

## 2. API Design

### 2.1 `StubRegistry` abstraction earns its place, but the hierarchy is shallow

The abstract class enables platform-specific concurrency implementations
(`JvmStubRegistry` uses `CopyOnWriteArrayList`; `NativeStubRegistry` uses
`@Volatile`). For a library that targets JVM + Native + Android, this split is
justified. However, the `StubRegistry` → `matchStub()` → `StatefulEntry.matches()`
call chain passes `ApplicationCall` through three layers only to construct an
`IncomingRequest` that could be snapshotted once at the top. Flattening that would
make both body-reading and concurrency easier to reason about (see §5.2).

### 2.2 `IncomingRequest` is live code but leaks platform coupling into `commonMain`

`IncomingRequest` holds `io.ktor.http.Headers` directly, which ties it to Ktor's
API surface. It is used only internally in `matchesStub` and is not part of any
public interface. It is not dead code, but it is more tightly coupled to Ktor's
type hierarchy than necessary for a data-transfer snapshot. A plain
`Map<String, String>` for headers would decouple it and make testing easier.

### 2.3 `reset()` vs `clearStubs()` semantics are documented but the naming is asymmetric

`FakeryServer` exposes:
- `clearStubs()` — removes stubs **and** resets counters
- `reset()` — resets counters only, stubs survive

The KDoc on `reset()` is clear. The risk is that `clearStubs()` does two things
(the name implies only one), and a developer who calls `clearStubs()` expecting
a fresh state for a new test scenario may be surprised that stubs are gone.
Consider renaming to `clearStubsAndCounters()` or adding a single-sentence
warning to `clearStubs()` KDoc: _"Also resets all sequence counters."_

### 2.4 `FakeryServer.reset()` is absent from `FakeryIntegrationTest`

`FakeryIntegrationTest` tests `clearStubs()` but never calls `reset()`. Since
`reset()` is on the public `FakeryServer` interface, it deserves at least one
smoke test in the integration suite — even a trivial one that verifies calling it
on a server with only single-response stubs does not throw.

---

## 3. Edge Cases

### 3.1 `sequence = emptyList()` is silently treated as absent

**`StubDefinition.kt`, lines 49–55**

```kotlin
val hasSequence = !sequence.isNullOrEmpty()
require(hasResponse xor hasSequence) { "…must define exactly one of 'response' or 'sequence'…" }
```

`isNullOrEmpty()` maps an empty list to the same code path as `null`. If a caller
passes `sequence = emptyList()` and `response = null`, the `require` fires with a
message that says _"not both, not neither"_ — but the user DID provide `sequence`,
just an empty one. The error message is technically correct but misleading. Worse,
if the JSON contains `"sequence": []`, Kotlinx serialization will deserialise it as
`emptyList()` (not `null`), triggering the same confusing exception.

### 3.2 Stop/restart does NOT reset sequence counters

`NativeFakeryServer.stop()` nulls the engine reference but leaves `registry`
alive. `NativeFakeryServer.start()` reconstructs the engine against the same
`registry`. The `StatefulEntry` objects (and their `callCount` values) are
therefore preserved across a stop/restart cycle.

```kotlin
// nativeMain/FakeryServerImpl.kt
override fun stop() {
    server?.engine?.stop(…)
    server = null          // registry is untouched; callCount values survive
}
override fun start() {
    server = embeddedServer(CIO, port = initialPort) { fakeryModule(registry) }.start(…)
}
```

Same behaviour on JVM. This is **undocumented**. A developer who calls
`stop()` / `start()` expecting a clean slate will be confused when the sequence
continues from where it left off. `FakeryServer.stop()` KDoc should state:
_"Does not reset sequence counters; call `reset()` or `clearStubs()` explicitly."_

### 3.3 Multiple stubs matching the same path — first-match-wins is documented but interaction with sequences is not

`StubDefinition.kt` documents _"first-match-wins in registration order"_.
Consider:

```
Stub A: GET /api → sequence [503, 503, 200]   (registered first)
Stub B: GET /api → sequence [200, 200]
```

Stub B is completely unreachable regardless of how many times Stub A's sequence
is exhausted. There is no round-robin or per-sequence promotion. This is
correct by the documented contract, but `StatefulStubTest` has no test for
this scenario, so the behaviour is untested.

---

## 4. Test Quality

### 4.1 Happy-path coverage is solid; failure modes are not

`StatefulStubTest` covers:
- Sequence in order ✓
- Last-response repeat ✓
- Reset rewinds counters ✓
- Backward compatibility (single response) ✓
- Construction validation (both/neither) ✓
- JSON parsing round-trip ✓

Missing:
- **Body-matched sequence stub** — no test for `request.body != null` on a
  sequence stub. Because of the bug in §1.3 this test would fail, but its
  absence means the bug went undetected.
- **Header-matched sequence stub** — `FakeryIntegrationTest` tests header
  matching for single-response stubs; no equivalent for sequences.
- **Empty sequence from JSON** (`"sequence": []`) — the confusing exception
  path (§3.1) is untested.
- **First-match-wins with two overlapping sequence stubs** — untested (§3.3).
- **Stop / restart counter persistence** — undocumented and untested (§3.2).

### 4.2 No concurrency test

There is no test that fires multiple concurrent requests at a sequence stub and
asserts that each step is visited exactly once. Given the real data race on
`callCount` (§1.1), this test would be the minimum evidence that the counter
is safe. A basic test with 10 parallel `HttpURLConnection` calls and a 10-element
sequence would surface the bug reliably.

### 4.3 Flaky test risk: `setUp` starts the server with `wait = false`

**`StatefulStubTest.kt`, line 8 / `FakeryIntegrationTest.kt`, line 15**

```kotlin
@BeforeTest
fun setUp() { server = fakery(stubs = emptyList()).also { it.start() } }
```

`JvmFakeryServer.start()`:
```kotlin
server = embeddedServer(CIO, port = _port) { fakeryModule(registry) }.start(wait = false)
```

Ktor's `EmbeddedServer.start(wait = false)` returns as soon as the engine
starts — but CIO's socket may not be accepting connections yet on slow CI hosts
(GH Actions Windows/Mac runners). The tests make real HTTP calls immediately
after. In practice this is rare but not impossible. Adding a minimal
`Thread.sleep(50)` or, better, a retry-with-backoff `connect` loop in `start()`
would eliminate the risk.

---

## 5. Quick Wins — Top 3 Fixes

---

### Fix 1 — Make `callCount` actually atomic

**File:** `StubRegistry.kt` — `StatefulEntry` class

The class KDoc claims an "atomic call counter" but the field is a plain `var`.
Use `kotlin.concurrent.AtomicInt` (available in the Kotlin stdlib for all
platforms since Kotlin 1.9 / `kotlin-stdlib` ≥ 2.0 via `kotlin.concurrent.atomics`;
for older toolchains, add `kotlinx-atomicfu`).

**Before (`StubRegistry.kt`, lines 37–50):**
```kotlin
internal class StatefulEntry(val definition: StubDefinition) {

    private var callCount: Int = 0

    suspend fun matches(call: ApplicationCall): Boolean = matchesStub(call, definition)

    fun nextResponse(): StubResponse {
        val responses = definition.responses
        val index     = minOf(callCount, responses.size - 1)
        callCount++
        return responses[index]
    }

    fun resetCounter() { callCount = 0 }
}
```

**After:**
```kotlin
import java.util.concurrent.atomic.AtomicInteger   // JVM-only: move to jvmMain,
                                                    // OR use kotlinx-atomicfu in commonMain

internal class StatefulEntry(val definition: StubDefinition) {

    private val callCount = AtomicInteger(0)        // truly atomic read-modify-write

    suspend fun matches(call: ApplicationCall): Boolean = matchesStub(call, definition)

    fun nextResponse(): StubResponse {
        val responses = definition.responses
        // getAndIncrement() is a single atomic op — no window for races
        val index = minOf(callCount.getAndIncrement(), responses.size - 1)
        return responses[index]
    }

    fun resetCounter() { callCount.set(0) }
}
```

If `StatefulEntry` must stay in `commonMain`, replace `AtomicInteger` with the
atomicfu `AtomicInt`:
```kotlin
// build.gradle.kts
implementation("org.jetbrains.kotlinx:atomicfu:0.25.0")

// StubRegistry.kt
import kotlinx.atomicfu.atomic

private val callCount = atomic(0)

fun nextResponse(): StubResponse {
    val responses = definition.responses
    val index = minOf(callCount.getAndIncrement(), responses.size - 1)
    return responses[index]
}

fun resetCounter() { callCount.value = 0 }
```

Also delete the KDoc claim _"atomic call counter"_ — it should say _"thread-safe
call counter"_ — and update after the fix.

---

### Fix 2 — Fix body matching without causing stream exhaustion

**Files:** `StubRegistry.kt` line 52–53, `IncomingRequest.kt` line 34,
`StubMatcher.kt` lines 11–13

The root cause: `matchesStub` calls `IncomingRequest.from(call)` (no stubs) so
`body` is never read. The fix is to read the body **once** before iterating
stubs, and thread that snapshot through to `matchesStub`.

**Step A — refactor `matchesStub` to accept a pre-parsed `IncomingRequest`:**

```kotlin
// StubRegistry.kt — replace the private fun at the bottom

/** Shared matching logic reused by [StatefulEntry]. */
private fun matchesEntry(incoming: IncomingRequest, stub: StubDefinition): Boolean {
    val req          = stub.request
    val methodMatch  = req.method.equals(incoming.method, ignoreCase = true)
    val pathMatch    = req.path == incoming.path
    val headersMatch = req.headers.all { (name, value) -> incoming.headers[name] == value }
    val bodyMatch    = req.body == null || req.body == incoming.body
    return methodMatch && pathMatch && headersMatch && bodyMatch
}

// StatefulEntry — accept the snapshot, never call receiveText() per-entry
internal class StatefulEntry(val definition: StubDefinition) {
    …
    fun matches(incoming: IncomingRequest): Boolean = matchesEntry(incoming, definition)
}
```

**Step B — refactor `matchStub` (StubMatcher.kt) to parse body once:**

Before:
```kotlin
// StubMatcher.kt
internal suspend fun matchStub(call: ApplicationCall, stubs: List<StatefulEntry>): StubResponse? {
    return stubs.firstOrNull { it.matches(call) }?.nextResponse()
}
```

After:
```kotlin
// StubMatcher.kt
internal suspend fun matchStub(call: ApplicationCall, stubs: List<StatefulEntry>): StubResponse? {
    // Parse body once; pass snapshot to every entry — no stream exhaustion possible.
    val incoming = IncomingRequest.from(call, stubs.map { it.definition })
    return stubs.firstOrNull { it.matches(incoming) }?.nextResponse()
}
```

**Step C — delete the broken no-stubs overload in `IncomingRequest`:**

```kotlin
// IncomingRequest.kt — DELETE this:
/** Overload used by [StatefulEntry] where the stub list isn't available. */
suspend fun from(call: ApplicationCall): IncomingRequest = from(call, emptyList())
```

Removing it will produce a compile error for any future callers that try to
bypass body parsing, catching the mistake at build time.

---

### Fix 3 — Make `NativeStubRegistry.add()` atomic

**File:** `nativeMain/FakeryServerImpl.kt`, lines 54–56

`entries = entries + StatefulEntry(stub)` is a non-atomic read-copy-update. Under
concurrent `addStub` calls one addition will be silently lost.

**Before:**
```kotlin
private class NativeStubRegistry(initial: List<StubDefinition>) : StubRegistry() {

    @Volatile private var entries: List<StatefulEntry> = initial.map { StatefulEntry(it) }

    override fun add(stub: StubDefinition) {
        entries = entries + StatefulEntry(stub)   // ← TOCTOU race
    }
    …
}
```

**After (use a `Mutex` — Ktor already depends on `kotlinx-coroutines`):**
```kotlin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private class NativeStubRegistry(initial: List<StubDefinition>) : StubRegistry() {

    private val mutex = Mutex()
    @Volatile private var entries: List<StatefulEntry> = initial.map { StatefulEntry(it) }

    override suspend fun match(call: ApplicationCall): StubResponse? =
        matchStub(call, entries)               // snapshot read; @Volatile guarantees visibility

    override fun add(stub: StubDefinition) {
        // runBlocking is acceptable here: add() is called from test setup, not a hot path.
        kotlinx.coroutines.runBlocking {
            mutex.withLock { entries = entries + StatefulEntry(stub) }
        }
    }

    override fun clear() {
        kotlinx.coroutines.runBlocking {
            mutex.withLock { entries = emptyList() }
        }
    }

    override fun reset() { entries.forEach { it.resetCounter() } }
}
```

Alternatively, make `add`/`clear`/`reset` suspend functions on `StubRegistry` and
propagate `withLock` throughout — a cleaner long-term direction if the API allows
it.

---

## Summary Table

| # | Severity | File | Issue |
|---|----------|------|-------|
| 1.1 | 🔴 Bug | `StubRegistry.kt:37,43–44` | `callCount` is a plain `var`; not thread-safe despite KDoc claiming "atomic" |
| 1.2 | 🔴 Bug | `nativeMain/FakeryServerImpl.kt:50,54–56` | `callCount` unprotected on Native; `add()` is a TOCTOU race |
| 1.3 | 🔴 Bug | `StubRegistry.kt:53` | `from(call)` always returns `body=null`; body-matching silently disabled for all stubs |
| 1.4 | 🟠 Design | `IncomingRequest.kt:34` | No-stubs overload exists only to paper over the missing body-parse; misleading comment |
| 2.3 | 🟡 Clarity | `FakeryServer.kt` | `clearStubs()` KDoc doesn't mention it also resets counters |
| 2.4 | 🟡 Coverage | `FakeryIntegrationTest.kt` | `reset()` is untested in the integration suite |
| 3.1 | 🟡 UX | `StubDefinition.kt:50` | `sequence=[]` gives a confusing error; message says "not neither" but user did provide the field |
| 3.2 | 🟡 Docs | `FakeryServer.kt` | `stop()` does not document that sequence counters survive a restart |
| 4.2 | 🟡 Coverage | — | No concurrency test for sequence advancement |
| 4.3 | 🟡 Flakiness | `StatefulStubTest.kt:8` | First request fires before CIO socket is guaranteed ready on slow hosts |

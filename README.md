# Fakery 🎭

A **Kotlin Multiplatform** fake HTTP server for testing your networking layer.  
Define stubs as JSON files. Point your client at `server.baseUrl`. No real network, no flakiness.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-blue)](https://kotlinlang.org)
[![Ktor](https://img.shields.io/badge/Ktor-3.0.3-orange)](https://ktor.io)

---

## Supported platforms

| Platform | Engine |
|---|---|
| JVM | Ktor CIO |
| Android | Ktor CIO |
| iOS (arm64, x64, Simulator) | Ktor CIO |
| macOS (arm64, x64) | Ktor CIO |
| Linux (x64) | Ktor CIO |
| Windows (mingwX64) | Ktor CIO |

---

## Quick start

### 1. Add the dependency

```kotlin
// build.gradle.kts
testImplementation("dev.fakery:fakery:0.1.0")
```

### 2. Define your stubs as JSON

```json
[
  {
    "request":  { "method": "GET", "path": "/users" },
    "response": { "status": 200, "body": [{"id": 1, "name": "Alice"}] }
  },
  {
    "request":  { "method": "POST", "path": "/users" },
    "response": { "status": 201, "body": {"id": 2, "name": "Bob"} }
  }
]
```

### 3. Use in your test

```kotlin
class UserApiTest {

    private lateinit var server: FakeryServer

    @BeforeTest
    fun setUp() {
        server = fakery(json = File("src/test/resources/stubs/users.json").readText())
        server.start()
    }

    @AfterTest
    fun tearDown() = server.stop()

    @Test
    fun `fetch users calls correct endpoint`() = runTest {
        val client = UserApiClient(baseUrl = server.baseUrl)
        val users  = client.getUsers()
        assertEquals(1, users.size)
        assertEquals("Alice", users[0].name)
    }
}
```

---

## Stub format

```json
{
  "request": {
    "method":  "GET",
    "path":    "/users/123",
    "headers": { "Authorization": "Bearer token" },
    "body":    { "filter": "active" }
  },
  "response": {
    "status":  200,
    "headers": { "X-Request-Id": "abc-123" },
    "body":    { "id": 123, "name": "Alice" }
  }
}
```

| Field | Required | Default | Description |
|---|---|---|---|
| `request.method` | No | `"GET"` | HTTP method, case-insensitive |
| `request.path` | **Yes** | — | Exact path match (query string is stripped) |
| `request.headers` | No | `{}` | Header subset — only declared keys are checked |
| `request.body` | No | `null` | JSON body match — skipped when absent |
| `response.status` | No | `200` | HTTP status code |
| `response.headers` | No | `{}` | Headers added to the response |
| `response.body` | No | `null` | Any JSON value — object, array, primitive |

### Matching rules

Stubs are evaluated in **registration order** (first match wins). A stub matches when **all** of:
1. `method` matches (case-insensitive)
2. `path` matches exactly (query string stripped)
3. All `headers` in the stub are present in the request
4. `body` is equal (only checked when the stub defines a body)

If no stub matches, Fakery returns `404` with:
```json
{ "error": "No stub for GET /users/unknown" }
```

---

## Entry points

### `fakery(json)` — from a JSON string
```kotlin
val server = fakery(json = """
    { "request": { "path": "/health" }, "response": { "status": 200 } }
""")
```

### `fakery(stubs)` — from parsed stubs
```kotlin
val stubs = listOf(
    StubDefinition(
        request  = StubRequest(path = "/ping"),
        response = StubResponse(status = 200),
    )
)
val server = fakery(stubs = stubs)
```

### `fakeryFromFile(path)` — from a single file
```kotlin
val server = fakeryFromFile("src/test/resources/stubs/users.json")
```

### `fakeryFromDirectory(path)` — from a directory of `.json` files
```kotlin
// Loads all *.json files in the directory
val server = fakeryFromDirectory("src/test/resources/stubs/")
```

---

## Runtime stub management

```kotlin
val server = fakery(stubs = emptyList()).also { it.start() }

// Add stubs at runtime (safe after start)
server.addStub(
    StubDefinition(
        request  = StubRequest(method = "GET", path = "/feature-flags"),
        response = StubResponse(status = 200, body = buildJsonObject {
            put("darkMode", JsonPrimitive(true))
        }),
    )
)

// Reset between test cases
server.clearStubs()
```

---

## Organising stubs

For large test suites, keep stubs in `src/test/resources/stubs/`:

```
src/test/resources/stubs/
├── users.json        ← array of user endpoint stubs
├── auth.json         ← login / logout stubs
└── errors.json       ← 4xx / 5xx scenarios
```

Load them all at once:
```kotlin
server = fakeryFromDirectory("src/test/resources/stubs/")
```

Each file can hold a **single stub** or an **array of stubs**.

---

## Architecture

```
fakery/
├── commonMain/             ← Public API + stub matching (pure Kotlin, no Ktor)
│   ├── Fakery.kt           ← Entry points: fakery(), fakeryFromFile(), fakeryFromDirectory()
│   ├── FakeryServer.kt     ← Interface: start/stop/addStub/clearStubs
│   ├── StubDefinition.kt   ← @Serializable data classes: StubDefinition, StubRequest, StubResponse
│   ├── StubLoader.kt       ← JSON parsing + expect file-loading functions
│   ├── StubMatcher.kt      ← Request matching logic (internal)
│   └── FakeryApplication.kt← Ktor application module (internal)
│
├── jvmAndAndroidMain/      ← JVM + Android implementation
│   ├── FakeryServerImpl.kt ← Ktor CIO server, java.net.ServerSocket for free-port
│   └── StubLoaderImpl.kt   ← java.io.File for directory/file reading
│
└── nativeMain/             ← iOS / macOS / Linux / Windows implementation
    ├── FakeryServerImpl.kt ← Ktor CIO server
    └── StubLoaderImpl.kt   ← kotlinx-io SystemFileSystem for file reading
```

---

## Sample module

See [`sample/`](sample/) for a full example:
- `UserApiClient` — a real Ktor HTTP client interface
- `UserApiClientImpl` — the production implementation
- `UserApiClientTest` — tests that use Fakery to stub the server

```kotlin
// From sample/src/jvmTest/kotlin/sample/UserApiClientTest.kt
@BeforeTest
fun setUp() {
    server = fakery(json = stubs)
    server.start()
    apiClient = UserApiClientImpl(client = HttpClient { ... }, baseUrl = server.baseUrl)
}
```

---

## Roadmap

- [ ] Path pattern matching (`/users/{id}`)
- [ ] Response delays (simulating slow/flaky networks)
- [ ] Request recording & verification (`server.verify(GET, "/users").wasCalled(1)`)
- [ ] JUnit 5 Extension + JUnit 4 Rule
- [ ] Maven Central publishing

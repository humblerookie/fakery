# Fakery 🎭

A Kotlin Multiplatform fake HTTP server for testing your networking layer.  
Define stubs as JSON files. Point your client at `server.baseUrl`. Done.

## Platforms

JVM · Android · iOS · macOS · Linux · Windows

## Stub format

```json
{
  "request": {
    "method":  "GET",
    "path":    "/users/123",
    "headers": { "Authorization": "Bearer token" },
    "body":    {}
  },
  "response": {
    "status":  200,
    "headers": { "Content-Type": "application/json" },
    "body":    { "id": 123, "name": "Alice" }
  }
}
```

| Field | Required | Default | Notes |
|---|---|---|---|
| `request.method` | No | `GET` | Case-insensitive |
| `request.path` | Yes | — | Exact match, no query string |
| `request.headers` | No | `{}` | Subset match |
| `request.body` | No | `null` | Skipped when absent |
| `response.status` | No | `200` | |
| `response.headers` | No | `{}` | Added to the response |
| `response.body` | No | `null` | Any JSON value |

## Usage

### From a JSON string

```kotlin
val server = fakery(json = """
    {
      "request":  { "method": "GET", "path": "/users" },
      "response": { "status": 200, "body": {"users": []} }
    }
""")
server.start()
// test against server.baseUrl
server.stop()
```

### From a file (JVM / Desktop)

```kotlin
val server = fakeryFromFile("src/test/resources/stubs/users.json")
server.start()
```

### From a directory

```kotlin
// Loads all *.json files in the directory
val server = fakeryFromDirectory("src/test/resources/stubs/")
server.start()
```

### Each file can be a single stub or an array

```json
[
  { "request": { "path": "/a" }, "response": { "status": 200 } },
  { "request": { "path": "/b" }, "response": { "status": 404 } }
]
```

### Add stubs at runtime

```kotlin
val server = fakery(stubs = emptyList())
server.start()

server.addStub(StubDefinition(
    request  = StubRequest(path = "/dynamic"),
    response = StubResponse(status = 200),
))
```

## Architecture

```
commonMain          ← StubDefinition, FakeryServer, matching logic, JSON parsing (pure KMP)
jvmAndAndroidMain   ← Ktor CIO server + java.io.File loader
nativeMain          ← Ktor CIO server + kotlinx-io file loader
```

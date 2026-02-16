package sample.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import sample.model.CreateUserRequest
import sample.model.User

/**
 * Ktor-backed implementation of [UserApiClient].
 *
 * Pass [baseUrl] as `server.baseUrl` in tests to point at Fakery.
 */
class UserApiClientImpl(
    private val client: HttpClient,
    private val baseUrl: String,
) : UserApiClient {

    override suspend fun getUsers(): List<User> =
        client.get("$baseUrl/users").body()

    override suspend fun getUser(id: Int): User =
        client.get("$baseUrl/users/$id").body()

    override suspend fun createUser(request: CreateUserRequest): User =
        client.post("$baseUrl/users") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    override suspend fun deleteUser(id: Int) {
        client.delete("$baseUrl/users/$id")
    }
}

package sample.api

import sample.model.CreateUserRequest
import sample.model.User

/**
 * Contract for the User API.
 * The implementation (below) uses Ktor; tests stub it via Fakery.
 */
interface UserApiClient {
    suspend fun getUsers(): List<User>
    suspend fun getUser(id: Int): User
    suspend fun createUser(request: CreateUserRequest): User
    suspend fun deleteUser(id: Int)
}

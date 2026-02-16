package sample.model

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: Int,
    val name: String,
    val email: String,
)

@Serializable
data class CreateUserRequest(
    val name: String,
    val email: String,
)

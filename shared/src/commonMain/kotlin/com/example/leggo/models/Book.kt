package com.example.leggo.models

import kotlinx.serialization.Serializable

@Serializable
data class Book(
    val title: String,
    val uriString: String,
    val coverPath: String? = null,
    val lastOpened: Long = 0L,
    val isTrash: Boolean = false
)

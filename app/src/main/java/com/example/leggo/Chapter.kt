package com.example.leggo

data class Chapter(
    val title: String,
    val pageIndex: Int
) {
    val startIndex: Int get() = pageIndex
}

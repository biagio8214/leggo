package com.example.leggo

import com.example.leggo.database.DatabaseDriverFactory
import com.example.leggo.database.DatabaseModule
import com.example.leggo.database.LeggoDatabase

actual object PlatformUtils {
    actual fun getPlatformName(): String = "Android"

    actual fun getDatabase(factory: DatabaseDriverFactory): LeggoDatabase {
        return DatabaseModule.getDatabase(factory)
    }
}

package com.example.leggo.database

import app.cash.sqldelight.db.SqlDriver

expect class DatabaseDriverFactory {
    fun createDriver(): SqlDriver
}

object DatabaseModule {
    private var database: LeggoDatabase? = null

    fun getDatabase(driverFactory: DatabaseDriverFactory): LeggoDatabase {
        if (database == null) {
            database = LeggoDatabase(driverFactory.createDriver())
        }
        return database!!
    }
}

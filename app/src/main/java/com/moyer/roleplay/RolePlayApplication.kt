package com.moyer.roleplay

import android.app.Application
import androidx.room.Room
import com.moyer.roleplay.data.*

class RolePlayApplication : Application() {
    val repository by lazy {
        ChatRepository(Room.databaseBuilder(this, AppDatabase::class.java, "roleplay.db")
            .addMigrations(AppDatabase.MIGRATION_1_2).build())
    }
    val secrets by lazy { SecretStore(this) }
}

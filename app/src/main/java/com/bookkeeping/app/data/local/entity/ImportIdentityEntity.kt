package com.bookkeeping.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "import_identities")
data class ImportIdentityEntity(
    @PrimaryKey val importKey: String,
    val transactionId: Long,
)

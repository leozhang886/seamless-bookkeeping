package com.bookkeeping.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "import_batches")
data class ImportBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val source: String,
    val importedAt: Long,
    val originalRows: Int,
    val parsedRows: Int,
    val rejectedRows: Int,
    val amountCents: Long,
    val fingerprint: String,
    val undoneAt: Long? = null,
)

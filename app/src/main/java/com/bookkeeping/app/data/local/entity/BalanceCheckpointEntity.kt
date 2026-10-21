package com.bookkeeping.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "balance_checkpoints")
data class BalanceCheckpointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val balanceCents: Long,
    val occurredAt: Long,
    val reason: String,
)

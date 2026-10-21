package com.bookkeeping.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(tableName = "pending_notifications", indices = [Index(value = ["fingerprint"], unique = true)])
data class PendingNotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fingerprint: String,
    val sourcePackage: String,
    val rawText: String,
    val rawTimestamp: Long,
    val amountCents: Long,
    val type: String,
    val category: String,
    val merchant: String,
    val source: String,
    val confidence: Int,
    val status: String = "PENDING",
)

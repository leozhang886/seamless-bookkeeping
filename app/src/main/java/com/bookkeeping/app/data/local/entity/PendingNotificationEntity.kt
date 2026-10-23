package com.bookkeeping.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import androidx.room.ColumnInfo

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
    @ColumnInfo(defaultValue = "1") val parserVersion: Int = 2,
    val sourceAccount: String? = null,
    val sourceAccountKind: String? = null,
    val bankFlow: String? = null,
    val relationKind: String? = null,
    val receivedAt: Long? = null,
    val eventKey: String? = null,
    val transactionId: Long? = null,
)

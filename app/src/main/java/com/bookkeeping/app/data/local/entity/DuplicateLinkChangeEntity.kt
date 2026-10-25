package com.bookkeeping.app.data.local.entity

import androidx.room.Entity

/** Only fields changed by a duplicate link are reversible; later edits must not be overwritten. */
@Entity(tableName = "duplicate_link_changes", primaryKeys = ["secondaryId", "transactionId"])
data class DuplicateLinkChangeEntity(
    val secondaryId: Long,
    val transactionId: Long,
    val primaryId: Long,
    val beforeRelatedId: Long?,
    val beforeRelationKind: String?,
    val beforeCategory: String,
    val afterRelatedId: Long?,
    val afterRelationKind: String?,
    val afterCategory: String,
)

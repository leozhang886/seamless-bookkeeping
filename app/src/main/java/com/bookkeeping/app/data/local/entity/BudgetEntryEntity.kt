package com.bookkeeping.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Budget templates share the ledger transaction and its backup/restore boundary. */
@Entity(tableName = "budget_entries")
data class BudgetEntryEntity(@PrimaryKey val category: String, val amountCents: Long)

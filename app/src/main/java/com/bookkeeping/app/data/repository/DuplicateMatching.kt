package com.bookkeeping.app.data.repository

import com.bookkeeping.app.data.local.entity.TransactionEntity
import kotlin.math.abs

internal fun movementDirection(row: TransactionEntity): String? = when (row.type) {
    "EXPENSE" -> "OUT"
    "INCOME" -> "IN"
    else -> row.bankFlow
}

/** A candidate is never deleted or merged by this predicate. It requires review. */
internal fun possibleSameMovement(first: TransactionEntity, second: TransactionEntity): Boolean {
    if (first.id != 0L && first.id == second.id || first.amountCents != second.amountCents ||
        movementDirection(first) != movementDirection(second) || abs(first.timestamp - second.timestamp) > 2 * 86_400_000L ||
        first.deletedAt != null || second.deletedAt != null || first.duplicateOfId != null || second.duplicateOfId != null)
        return false
    if (movementDirection(first) == null) return false
    if (first.balanceAccountId != null && second.balanceAccountId != null && first.balanceAccountId != second.balanceAccountId)
        return false
    if (first.source == "BANK_PDF" && second.source == "BANK_PDF") {
        if (first.account != second.account) return false
        if (first.sourceBalanceCents != null && second.sourceBalanceCents != null &&
            first.sourceBalanceCents != second.sourceBalanceCents) return false
        return first.sourceIdentity != null && first.sourceIdentity == second.sourceIdentity ||
            first.timestamp == second.timestamp && first.merchant == second.merchant
    }
    if (first.source == second.source && first.source != "MANUAL" &&
        (first.importKey != null) == (second.importKey != null)) return false
    if (first.type == "TRANSFER" || second.type == "TRANSFER") {
        return first.balanceAccountId != null && first.balanceAccountId == second.balanceAccountId ||
            first.account != null && first.account == second.account
    }
    return true
}

internal fun isPairedTransfer(row: TransactionEntity) = row.relationKind in setOf("TRANSFER_OUT", "TRANSFER_IN")

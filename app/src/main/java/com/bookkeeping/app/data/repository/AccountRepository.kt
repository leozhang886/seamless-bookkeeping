package com.bookkeeping.app.data.repository

import androidx.room.withTransaction
import com.bookkeeping.app.data.local.BookkeepingDatabase
import com.bookkeeping.app.data.local.dao.AccountDao
import com.bookkeeping.app.data.local.dao.LedgerMetaDao
import com.bookkeeping.app.data.local.dao.TransactionDao
import com.bookkeeping.app.data.local.entity.AccountEntity
import com.bookkeeping.app.data.local.entity.BalanceCheckpointEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepository @Inject constructor(
    private val dao: AccountDao,
    private val transactions: TransactionDao,
    private val meta: LedgerMetaDao,
    private val database: BookkeepingDatabase,
) {
    fun observeAll() = combine(dao.observeAll(), transactions.observeBalanceMovements()) { accounts, bills ->
        effectiveBalanceAccounts(accounts, bills)
    }
    suspend fun add(name: String, kind: String, balanceCents: Long) = database.withTransaction {
        val now = System.currentTimeMillis()
        val id = dao.insert(AccountEntity(name = name, kind = kind, balanceCents = balanceCents, updatedAt = now))
        meta.insertCheckpoint(BalanceCheckpointEntity(accountId = id, balanceCents = balanceCents,
            occurredAt = now, reason = "建立账户"))
        id
    }

    suspend fun updateBalance(id: Long, balanceCents: Long, reason: String = "手动校准") = database.withTransaction {
        require(dao.getById(id)?.archivedAt == null) { "账户已归档" }
        val now = System.currentTimeMillis()
        dao.updateBalance(id, balanceCents, now)
        meta.insertCheckpoint(BalanceCheckpointEntity(accountId = id, balanceCents = balanceCents,
            occurredAt = now, reason = reason.take(100)))
    }

    suspend fun delete(id: Long) = dao.archive(id, System.currentTimeMillis())
    suspend fun restore(id: Long) = dao.restore(id)
    suspend fun checkpoints(id: Long) = meta.checkpointsFor(id)
}

internal fun effectiveBalanceAccounts(
    accounts: List<AccountEntity>,
    transactions: List<TransactionEntity>,
): List<AccountEntity> = accounts.map { account ->
    val change = transactions.asSequence()
        .filter { it.deletedAt == null && it.duplicateOfId == null &&
            it.balanceAccountId == account.id && it.timestamp > account.updatedAt && canLinkBalance(it) }
        .mapNotNull(::balanceDeltaCents)
        .sum()
    account.copy(balanceCents = account.balanceCents + change)
}

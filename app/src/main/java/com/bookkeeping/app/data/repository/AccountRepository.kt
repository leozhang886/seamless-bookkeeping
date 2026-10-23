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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
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
    }.flowOn(Dispatchers.Default)
    fun observeArchived() = dao.observeArchived()
    suspend fun rename(id: Long, name: String) = database.writeLedger {
        require(name.isNotBlank() && name.length <= 40) { "账户名称不能为空，最多40字" }
        require(dao.getById(id) != null) { "账户不存在" }
        dao.rename(id, name.trim())
    }
    suspend fun add(name: String, kind: String, balanceCents: Long) = database.writeLedger {
        require(name.isNotBlank() && kind in setOf("BANK", "ALIPAY", "WECHAT", "FUND", "CASH")) { "账户信息无效" }
        require(balanceCents in -MAX_TRANSACTION_CENTS..MAX_TRANSACTION_CENTS) { "余额超出支持范围" }
        val now = System.currentTimeMillis()
        val id = dao.insert(AccountEntity(name = name, kind = kind, balanceCents = balanceCents, updatedAt = now))
        meta.insertCheckpoint(BalanceCheckpointEntity(accountId = id, balanceCents = balanceCents,
            occurredAt = now, reason = "建立账户"))
        id
    }

    suspend fun updateBalance(id: Long, balanceCents: Long, reason: String = "手动校准") = database.writeLedger {
        require(dao.getById(id)?.let { it.archivedAt == null } == true) { "账户不存在或已归档" }
        require(balanceCents in -MAX_TRANSACTION_CENTS..MAX_TRANSACTION_CENTS) { "余额超出支持范围" }
        val now = System.currentTimeMillis()
        dao.updateBalance(id, balanceCents, now)
        meta.insertCheckpoint(BalanceCheckpointEntity(accountId = id, balanceCents = balanceCents,
            occurredAt = now, reason = reason.take(100)))
    }

    suspend fun delete(id: Long) = database.writeLedger { dao.archive(id, System.currentTimeMillis()) }
    suspend fun restore(id: Long) = database.writeLedger { dao.restore(id) }
    suspend fun checkpoints(id: Long) = meta.checkpointsFor(id)
}

internal fun effectiveBalanceAccounts(
    accounts: List<AccountEntity>,
    transactions: List<TransactionEntity>,
): List<AccountEntity> {
    val byAccount = transactions.asSequence().filter { it.deletedAt == null && it.duplicateOfId == null }
        .groupBy { it.balanceAccountId }
    return accounts.map { account ->
        val change = byAccount[account.id].orEmpty().asSequence()
            .filter { it.timestamp > account.updatedAt && canLinkBalance(it) }
            .mapNotNull(::balanceDeltaCents).fold(0L, Math::addExact)
        account.copy(balanceCents = Math.addExact(account.balanceCents, change))
    }
}

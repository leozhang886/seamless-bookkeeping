package com.bookkeeping.app.data.repository

import com.bookkeeping.app.data.local.dao.AccountDao
import com.bookkeeping.app.data.local.dao.TransactionDao
import com.bookkeeping.app.data.local.entity.AccountEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepository @Inject constructor(
    private val dao: AccountDao,
    private val transactions: TransactionDao,
) {
    fun observeAll() = combine(dao.observeAll(), transactions.observeAll()) { accounts, bills ->
        effectiveBalanceAccounts(accounts, bills)
    }
    suspend fun add(name: String, kind: String, balanceCents: Long) =
        dao.insert(AccountEntity(name = name, kind = kind, balanceCents = balanceCents, updatedAt = System.currentTimeMillis()))
    suspend fun updateBalance(id: Long, balanceCents: Long) =
        dao.updateBalance(id, balanceCents, System.currentTimeMillis())
    suspend fun delete(id: Long) = dao.delete(id)
}

internal fun effectiveBalanceAccounts(
    accounts: List<AccountEntity>,
    transactions: List<TransactionEntity>,
): List<AccountEntity> = accounts.map { account ->
    val change = transactions.asSequence()
        .filter { it.balanceAccountId == account.id && it.timestamp > account.updatedAt && canLinkBalance(it) }
        .mapNotNull(::balanceDeltaCents)
        .sum()
    account.copy(balanceCents = account.balanceCents + change)
}

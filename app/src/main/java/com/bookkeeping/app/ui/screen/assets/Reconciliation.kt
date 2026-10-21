package com.bookkeeping.app.ui.screen.assets

import com.bookkeeping.app.data.local.entity.AccountEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.repository.canLinkBalance
import com.bookkeeping.app.data.repository.eligibleBalanceAccount
import com.bookkeeping.app.data.repository.matchingAccountForLiveTransaction

internal fun pendingForAccount(
    account: AccountEntity,
    accounts: List<AccountEntity>,
    transactions: List<TransactionEntity>,
): List<TransactionEntity> = transactions.filter { transaction ->
    if (transaction.balanceAccountId != null || transaction.balanceIgnored || !canLinkBalance(transaction) ||
        !eligibleBalanceAccount(account, transaction)) return@filter false
    if (transaction.source in setOf("BANK_SMS", "BANK_APP") && account.kind == "BANK") {
        val sourceAccount = transaction.account.orEmpty()
        val bankName = sourceAccount.substringBefore(" (").trim()
        val tail = Regex("(?<!\\d)\\d{4}(?!\\d)").find(sourceAccount)?.value
        if (bankName.isNotEmpty() && !account.name.contains(bankName)) return@filter false
        if (tail != null && !account.name.contains(tail)) return@filter false
    }
    val known = matchingAccountForLiveTransaction(accounts, transaction)
    known == null || known.id == account.id
}.sortedByDescending { it.timestamp }

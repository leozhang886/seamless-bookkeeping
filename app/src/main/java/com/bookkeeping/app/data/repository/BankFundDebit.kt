package com.bookkeeping.app.data.repository

import com.bookkeeping.app.data.local.entity.AccountEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import java.math.BigDecimal

internal fun balanceDeltaCents(transaction: TransactionEntity): Long? {
    if (transaction.amountCents <= 0) return null
    return when (transaction.type) {
        TransactionType.EXPENSE.name -> -transaction.amountCents
        TransactionType.INCOME.name -> transaction.amountCents
        TransactionType.TRANSFER.name -> when (transaction.bankFlow) {
            "OUT" -> -transaction.amountCents
            "IN" -> transaction.amountCents
            else -> null
        }
        else -> null
    }
}

internal fun canLinkBalance(transaction: TransactionEntity): Boolean =
    transaction.balanceTrackingVersion == 1 &&
        (transaction.importKey == null || transaction.source in setOf(
            TransactionSource.ALIPAY.name, TransactionSource.WECHAT.name, TransactionSource.BANK_PDF.name)) &&
        balanceDeltaCents(transaction) != null

internal fun balanceEditImpactCents(
    old: TransactionEntity,
    edited: TransactionEntity,
    accounts: List<AccountEntity>,
): Map<Long, BigDecimal> = accounts.mapNotNull { account ->
    fun contribution(transaction: TransactionEntity): BigDecimal =
        if (transaction.balanceAccountId == account.id && transaction.timestamp > account.updatedAt &&
            canLinkBalance(transaction)) BigDecimal.valueOf(balanceDeltaCents(transaction) ?: 0L)
        else BigDecimal.ZERO
    val change = contribution(edited) - contribution(old)
    if (change.signum() == 0) null else account.id to change
}.toMap()

internal fun eligibleBalanceAccount(account: AccountEntity, transaction: TransactionEntity): Boolean =
    account.kind in when {
        transaction.importKey != null -> when (transaction.source) {
            TransactionSource.BANK_PDF.name -> setOf("BANK")
            TransactionSource.ALIPAY.name -> if (transaction.note.contains("[付款账户:其他方式]")) emptySet() else setOf("ALIPAY")
            TransactionSource.WECHAT.name -> if (transaction.note.contains("[付款账户:其他方式]")) emptySet() else setOf("WECHAT")
            else -> emptySet()
        }
        else -> when (transaction.source) {
        TransactionSource.BANK_SMS.name, TransactionSource.BANK_APP.name -> setOf("BANK")
        TransactionSource.ALIPAY.name -> setOf("BANK", "ALIPAY")
        TransactionSource.WECHAT.name -> setOf("BANK", "WECHAT")
        else -> setOf("BANK", "ALIPAY", "WECHAT")
        }
    } && transaction.timestamp > account.updatedAt

internal fun matchingAccountForLiveTransaction(
    accounts: List<AccountEntity>,
    transaction: TransactionEntity,
): AccountEntity? {
    if (!canLinkBalance(transaction)) return null
    val candidates = accounts.filter { eligibleBalanceAccount(it, transaction) }
    return when (transaction.source) {
        TransactionSource.BANK_SMS.name, TransactionSource.BANK_APP.name,
        TransactionSource.BANK_PDF.name -> {
            val sourceAccount = transaction.account ?: return null
            val bank = sourceAccount.substringBefore(" (").substringBefore('（').trim()
            val tail = Regex("""(?<!\d)\d{4}(?!\d)""").find(sourceAccount)?.value ?: return null
            candidates.filter { account ->
                account.kind == "BANK" && account.name.contains(bank) &&
                    Regex("""(?<!\d)\d{4}(?!\d)""").findAll(account.name).any { it.value == tail }
            }.singleOrNull()
        }
        TransactionSource.ALIPAY.name -> {
            val explicit = if (transaction.importKey != null)
                transaction.note.contains("[付款账户:支付宝余额]")
            else listOf("支付宝余额", "余额支付", "余额付款", "余额扣款", "余额收款")
                .any(transaction.note::contains)
            if (explicit && (transaction.importKey != null || !transaction.note.contains("余额宝")))
                candidates.filter { it.kind == "ALIPAY" }.singleOrNull() else null
        }
        TransactionSource.WECHAT.name -> {
            val explicit = if (transaction.importKey != null)
                transaction.note.contains("[付款账户:微信零钱]")
            else listOf("微信零钱", "零钱支付", "零钱付款", "零钱扣款", "零钱收款")
                .any(transaction.note::contains)
            if (explicit && !transaction.note.contains("零钱通"))
                candidates.filter { it.kind == "WECHAT" }.singleOrNull() else null
        }
        else -> null
    }
}

internal fun matchingBankForFundDebit(
    accounts: List<AccountEntity>,
    transaction: TransactionEntity,
): AccountEntity? {
    if (transaction.source != TransactionSource.BANK_SMS.name ||
        transaction.type != TransactionType.TRANSFER.name ||
        listOf("基金申购", "基金购买").none(transaction.note::contains)) return null
    return matchingAccountForLiveTransaction(accounts, transaction)
}

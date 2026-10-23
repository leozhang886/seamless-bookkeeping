package com.bookkeeping.app.data.repository

import androidx.room.withTransaction
import com.bookkeeping.app.data.local.BookkeepingDatabase
import com.bookkeeping.app.data.local.dao.LedgerMetaDao
import com.bookkeeping.app.data.local.dao.AccountDao
import com.bookkeeping.app.data.local.dao.TransactionDao
import com.bookkeeping.app.data.local.entity.PendingNotificationEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PendingNotificationRepository @Inject constructor(
    private val database: BookkeepingDatabase,
    private val meta: LedgerMetaDao,
    private val transactions: TransactionDao,
    private val accounts: AccountDao,
) {
    fun observePending() = meta.observePending()
    fun observeProcessed() = meta.observeProcessedPending()
    suspend fun reopen(id: Long) = database.writeLedger { meta.reopenPending(id) }

    suspend fun save(
        packageName: String,
        notificationKey: String,
        title: String?,
        text: String?,
        parsed: TransactionEntity,
        postedAt: Long = parsed.timestamp,
    ): Boolean {
        val full = listOfNotNull(title, text).joinToString(" / ")
        val raw = full.take(1200)
        val identity = "$packageName|$notificationKey|$postedAt|$raw"
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return database.writeLedger { meta.insertPending(PendingNotificationEntity(
            fingerprint = fingerprint,
            sourcePackage = packageName,
            rawText = raw,
            rawTimestamp = parsed.timestamp,
            amountCents = parsed.amountCents,
            type = parsed.type,
            category = parsed.category,
            merchant = parsed.merchant.take(80),
            source = parsed.source,
            confidence = if (parsed.merchant.isBlank()) 55 else 70,
            sourceAccount = parsed.account, bankFlow = parsed.bankFlow, receivedAt = postedAt,
            sourceAccountKind = explicitWalletKind(full, parsed.source) ?: "OTHER",
            relationKind = pendingReturnKind(parsed.type, full),
            eventKey = MessageDigest.getInstance("SHA-256").digest("$packageName|$raw".toByteArray())
                .joinToString("") { "%02x".format(it) },
        )) != -1L }
    }

    suspend fun nearby(id: Long, amountCents: Long? = null, type: String? = null): List<TransactionEntity> {
        val pending = meta.pendingById(id) ?: return emptyList()
        return transactions.findNear(amountCents ?: pending.amountCents, type ?: pending.type,
            pending.rawTimestamp - 86_400_000L, pending.rawTimestamp + 86_400_000L)
            .filter { it.duplicateOfId == null }.sortedByDescending { it.timestamp }
    }

    suspend fun confirm(id: Long, amountCents: Long, type: String, category: String,
        merchant: String, accountId: Long?, acknowledgedIds: Set<Long> = emptySet()): Boolean = database.writeLedger {
        val pending = meta.pendingById(id)?.takeIf { it.status == "PENDING" } ?: return@writeLedger false
        if (amountCents !in 1..MAX_TRANSACTION_CENTS || type !in setOf("EXPENSE", "INCOME") || category == "TRANSFER")
            return@writeLedger false
        val currentCandidates = nearby(id, amountCents, type).map { it.id }.toSet()
        check(acknowledgedIds.containsAll(currentCandidates)) { "发现新的相似流水，请重新核对重复候选" }
        val account = accountId?.let { accounts.getById(it) }
        if (accountId != null && (account == null || account.archivedAt != null ||
            pending.rawTimestamp <= account.updatedAt ||
            !when (pending.source) {
                "ALIPAY" -> account.kind == "ALIPAY" &&
                    listOf("支付宝余额", "余额支付", "余额付款").any(pending.rawText::contains) &&
                    !pending.rawText.contains("余额宝")
                "WECHAT" -> account.kind == "WECHAT" &&
                    listOf("微信零钱", "零钱支付", "零钱付款").any(pending.rawText::contains) &&
                    !pending.rawText.contains("零钱通")
                else -> false
            })) return@writeLedger false
        val transactionId = transactions.insert(TransactionEntity(
            amountCents = amountCents,
            type = type,
            category = category,
            merchant = merchant.trim().take(80),
            note = "通知确认：${pending.rawText.take(120)}",
            source = pending.source,
            account = pending.sourceAccount ?: if (pending.source == "BANK_APP") "建设银行" else if (pending.source == "WECHAT") "微信" else "支付宝",
            timestamp = pending.rawTimestamp,
            balanceAccountId = accountId, balanceTrackingVersion = if (pending.source == "BANK_APP") null else 1,
            createdAt = System.currentTimeMillis(), sourceAccountKind = pending.sourceAccountKind,
            sourcePaymentMethod = pending.sourceAccount, parserVersion = pending.parserVersion, bankFlow = pending.bankFlow,
            rawData = pending.rawText,
            relationKind = if (type == "INCOME") pending.relationKind ?: pendingReturnKind(type, pending.rawText) else null,
        ))
        meta.completePending(id, transactionId) == 1
    }

    suspend fun dismiss(id: Long, duplicate: Boolean = false) =
        database.writeLedger { meta.setPendingStatus(id, if (duplicate) "DUPLICATE" else "DISMISSED") }
}

internal fun explicitWalletKind(text: String, source: String): String? = when {
    source == "ALIPAY" && !text.contains("余额宝") &&
        listOf("支付宝余额", "余额支付", "余额付款", "余额扣款", "余额收款").any(text::contains) -> "ALIPAY"
    source == "WECHAT" && !text.contains("零钱通") &&
        listOf("微信零钱", "零钱支付", "零钱付款", "零钱扣款", "零钱收款").any(text::contains) -> "WECHAT"
    else -> null
}

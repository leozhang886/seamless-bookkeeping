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

    suspend fun save(
        packageName: String,
        notificationKey: String,
        title: String?,
        text: String?,
        parsed: TransactionEntity,
    ): Boolean {
        val raw = listOfNotNull(title, text).joinToString(" / ").take(300)
        val identity = "$packageName|$notificationKey|${parsed.timestamp}|$raw"
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return meta.insertPending(PendingNotificationEntity(
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
        )) != -1L
    }

    suspend fun nearby(id: Long): List<TransactionEntity> {
        val pending = meta.pendingById(id) ?: return emptyList()
        return transactions.findNear(pending.amountCents, pending.type,
            pending.rawTimestamp - 86_400_000L, pending.rawTimestamp + 86_400_000L)
            .filter { it.duplicateOfId == null }.take(10)
    }

    suspend fun confirm(id: Long, amountCents: Long, type: String, category: String,
        merchant: String, accountId: Long?): Boolean = database.withTransaction {
        val pending = meta.pendingById(id)?.takeIf { it.status == "PENDING" } ?: return@withTransaction false
        if (amountCents <= 0 || type !in setOf("EXPENSE", "INCOME") || category == "TRANSFER")
            return@withTransaction false
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
            })) return@withTransaction false
        transactions.insert(TransactionEntity(
            amountCents = amountCents,
            type = type,
            category = category,
            merchant = merchant.trim().take(80),
            note = "通知确认：${pending.rawText.take(120)}",
            source = pending.source,
            account = if (pending.source == "BANK_APP") "建设银行" else if (pending.source == "WECHAT") "微信" else "支付宝",
            timestamp = pending.rawTimestamp,
            balanceAccountId = accountId,
            relationKind = if (type == "INCOME" &&
                listOf("退款", "退回", "冲正").any(pending.rawText::contains)) "REFUND_PENDING"
                else if (type == "INCOME" && pending.rawText.contains("报销")) "REIMBURSEMENT_PENDING" else null,
        ))
        meta.setPendingStatus(id, "CONFIRMED") == 1
    }

    suspend fun dismiss(id: Long, duplicate: Boolean = false) =
        meta.setPendingStatus(id, if (duplicate) "DUPLICATE" else "DISMISSED")
}

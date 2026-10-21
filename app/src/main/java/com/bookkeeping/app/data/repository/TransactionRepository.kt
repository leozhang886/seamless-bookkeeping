package com.bookkeeping.app.data.repository

import androidx.room.withTransaction
import com.bookkeeping.app.data.local.BookkeepingDatabase
import com.bookkeeping.app.data.local.dao.AccountDao
import com.bookkeeping.app.data.local.dao.TransactionDao
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.local.entity.TransactionSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

// Repository：业务层与数据层的桥梁。
// 上层（ViewModel）只跟 Repository 打交道，不直接接触 DAO/网络/SharedPreferences。
// 好处：将来加缓存、加远程同步、切换数据源都只改这里，不动 UI。
// 嵌入式类比：HAL 层封装了寄存器操作，上层应用代码不直接读寄存器。
@Singleton
class TransactionRepository @Inject constructor(
    private val dao: TransactionDao,
    private val accountDao: AccountDao,
    private val database: BookkeepingDatabase,
) {
    fun observeAll(): Flow<List<TransactionEntity>> = dao.observeAll()

    fun observeBetween(startMs: Long, endMs: Long): Flow<List<TransactionEntity>> =
        dao.observeBetween(startMs, endMs)

    fun observeTotalExpenseBetween(startMs: Long, endMs: Long): Flow<Long> =
        dao.observeTotalExpenseBetween(startMs, endMs)

    fun observeTotalIncomeBetween(startMs: Long, endMs: Long): Flow<Long> =
        dao.observeTotalIncomeBetween(startMs, endMs)

    suspend fun insert(transaction: TransactionEntity): Long = database.withTransaction {
        val account = transaction.balanceAccountId?.let { accountDao.getById(it) }
        val linked = account?.takeIf {
            eligibleBalanceAccount(it, transaction) && canLinkBalance(transaction)
        }
        dao.insert(transaction.copy(balanceAccountId = linked?.id))
    }

    // 自动抓取路径专用：银行通知按账户、金额、方向和正文去重；其他通知沿用原有时间窗口。
    // 返回 null 表示重复，返回 Long 表示新插入的 id。
    suspend fun insertIfNotDuplicate(
        transaction: TransactionEntity,
        windowMs: Long = 60_000L,
    ): Long? = database.withTransaction {
        val bankSources = setOf(TransactionSource.BANK_SMS.name, TransactionSource.BANK_APP.name)
        if (transaction.source in bankSources) {
            val nearby = dao.findNear(transaction.amountCents, transaction.type,
                transaction.timestamp - 300_000L, transaction.timestamp + 300_000L)
            val duplicate = nearby.any { sameBankMovement(it, transaction, windowMs) }
            if (duplicate) return@withTransaction null
            val account = matchingAccountForLiveTransaction(accountDao.getAll(), transaction)
            return@withTransaction dao.insert(transaction.copy(balanceAccountId = account?.id))
        }
        val existing = dao.findRecentSameAmount(
            source = transaction.source,
            amountCents = transaction.amountCents,
            type = transaction.type,
            account = transaction.account,
            merchant = transaction.merchant,
            windowStartMs = transaction.timestamp - windowMs,
            windowEndMs = transaction.timestamp + windowMs,
        )
        if (existing != null) return@withTransaction null
        val account = matchingAccountForLiveTransaction(accountDao.getAll(), transaction)
        dao.insert(transaction.copy(balanceAccountId = account?.id))
    }

    suspend fun insertBankSmsIfNotDuplicate(transaction: TransactionEntity): Long? =
        if (transaction.source == TransactionSource.BANK_SMS.name)
            insertIfNotDuplicate(transaction) else null

    suspend fun setBalanceAccount(transactionId: Long, accountId: Long?, ignore: Boolean = false): Boolean = database.withTransaction {
        val transaction = dao.getById(transactionId) ?: return@withTransaction false
        if (!canLinkBalance(transaction)) return@withTransaction false
        val account = accountId?.let { accountDao.getById(it) }
        if (accountId != null && (account == null || !eligibleBalanceAccount(account, transaction)))
            return@withTransaction false
        dao.update(transaction.copy(balanceAccountId = accountId, balanceIgnored = accountId == null && ignore))
        true
    }

    suspend fun edit(
        id: Long,
        amountCents: Long,
        type: TransactionType,
        category: TransactionCategory,
        merchant: String,
        note: String,
        accountId: Long?,
    ): Boolean = database.withTransaction {
        val old = dao.getById(id) ?: return@withTransaction false
        val edited = editedTransaction(old, amountCents, type, category, merchant, note, accountId)
            ?: return@withTransaction false
        if (accountId != null) {
            val account = accountDao.getById(accountId) ?: return@withTransaction false
            if (!canLinkBalance(edited) ||
                (!eligibleBalanceAccount(account, edited) && old.balanceAccountId != accountId))
                return@withTransaction false
        }
        dao.update(edited)
        true
    }

    suspend fun delete(transaction: TransactionEntity) = dao.delete(transaction)

    data class ImportResult(
        val found: Int,
        val added: Int,
        val duplicates: Int,
        val transfers: Int,
        val directionsUpdated: Int,
        val balancesLinked: Int,
    )

    suspend fun importBills(bills: List<TransactionEntity>): ImportResult {
        var added = 0
        var duplicates = 0
        var transfers = 0
        var directionsUpdated = 0
        var balancesLinked = 0
        val balanceAccounts = accountDao.getAll()
        for (bill in bills) {
            val exact = bill.importKey?.let { dao.getByImportKey(it) }
            if (exact != null) {
                val upgraded = directionUpgrade(exact, bill)
                val linked = if (exact.balanceAccountId == null && !exact.balanceIgnored)
                    matchingAccountForLiveTransaction(balanceAccounts, bill) else null
                val updated = (upgraded ?: exact).copy(balanceAccountId = linked?.id ?: exact.balanceAccountId)
                if (updated != exact) dao.update(updated)
                if (upgraded != null) directionsUpdated++
                if (linked != null) balancesLinked++
                duplicates++
                continue
            }
            val nearby = dao.findNear(bill.amountCents, bill.type, bill.timestamp - 60_000, bill.timestamp + 60_000)
                .any { it.importKey == null && it.source == bill.source && it.account == bill.account &&
                    (it.merchant.isBlank() || bill.merchant.isBlank() || it.merchant == bill.merchant) }
            if (nearby) { duplicates++; continue }
            val linked = matchingAccountForLiveTransaction(balanceAccounts, bill)
            dao.insert(bill.copy(balanceAccountId = linked?.id))
            if (linked != null) balancesLinked++
            added++
            if (bill.type == TransactionType.TRANSFER.name) {
                val expectedBankType = if (listOf("提现", "转出").any { bill.note.contains(it) })
                    TransactionType.INCOME.name else TransactionType.EXPENSE.name
                val matches = dao.findNear(bill.amountCents, expectedBankType,
                    bill.timestamp - 600_000, bill.timestamp + 600_000)
                    .filter { it.account != null && it.account != bill.account &&
                        it.source == "BANK_SMS" && it.category == TransactionCategory.TRANSFER.name }
                if (matches.size == 1) {
                    val match = matches.single()
                    dao.update(match.copy(type = TransactionType.TRANSFER.name,
                        category = TransactionCategory.TRANSFER.name,
                        bankFlow = if (match.type == TransactionType.EXPENSE.name) "OUT" else "IN"))
                    transfers++
                }
            }
        }
        return ImportResult(bills.size, added, duplicates, transfers, directionsUpdated, balancesLinked)
    }
}

internal fun sameBankMovement(
    existing: TransactionEntity,
    incoming: TransactionEntity,
    crossSourceWindowMs: Long = 60_000L,
): Boolean {
    val bankSources = setOf(TransactionSource.BANK_SMS.name, TransactionSource.BANK_APP.name)
    if (existing.source !in bankSources || incoming.source !in bankSources ||
        existing.account != incoming.account || existing.amountCents != incoming.amountCents ||
        existing.type != incoming.type ||
        kotlin.math.abs(existing.timestamp - incoming.timestamp) > 300_000L) return false
    return if (existing.source == incoming.source) existing.note == incoming.note
    else kotlin.math.abs(existing.timestamp - incoming.timestamp) <= crossSourceWindowMs
}

internal fun editedTransaction(
    old: TransactionEntity,
    amountCents: Long,
    type: TransactionType,
    category: TransactionCategory,
    merchant: String,
    note: String,
    accountId: Long?,
): TransactionEntity? {
    if (amountCents <= 0 || (old.type == TransactionType.TRANSFER.name) != (type == TransactionType.TRANSFER))
        return null
    if (type == TransactionType.TRANSFER && category != TransactionCategory.TRANSFER) return null
    return old.copy(
        amountCents = amountCents,
        type = type.name,
        category = category.name,
        merchant = merchant.trim(),
        note = note.trim(),
        balanceAccountId = accountId,
        balanceIgnored = accountId == null && old.balanceIgnored,
    )
}

internal fun directionUpgrade(existing: TransactionEntity, imported: TransactionEntity): TransactionEntity? =
    if (existing.importKey != null && existing.importKey == imported.importKey &&
        existing.bankFlow == null && imported.bankFlow != null)
        existing.copy(bankFlow = imported.bankFlow)
    else null

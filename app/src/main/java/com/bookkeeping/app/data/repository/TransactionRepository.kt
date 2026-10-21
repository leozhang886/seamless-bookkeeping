package com.bookkeeping.app.data.repository

import androidx.room.withTransaction
import com.bookkeeping.app.data.local.BookkeepingDatabase
import com.bookkeeping.app.data.local.dao.AccountDao
import com.bookkeeping.app.data.local.dao.TransactionDao
import com.bookkeeping.app.data.local.dao.LedgerMetaDao
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.ImportBatchEntity
import com.bookkeeping.app.data.local.entity.ImportIdentityEntity
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.local.entity.TransactionSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
    private val metaDao: LedgerMetaDao,
    private val database: BookkeepingDatabase,
) {
    fun observeAll(): Flow<List<TransactionEntity>> = dao.observeAll()

    fun observeBetween(startMs: Long, endMs: Long): Flow<List<TransactionEntity>> =
        dao.observeActiveForAccounting().map { all -> accountingPeriod(all, startMs, endMs) }

    fun observeTotalExpenseBetween(startMs: Long, endMs: Long): Flow<Long> =
        observeBetween(startMs, endMs).map { rows -> rows.filter { it.type == "EXPENSE" }.sumOf { it.amountCents } }

    fun observeTotalIncomeBetween(startMs: Long, endMs: Long): Flow<Long> =
        observeBetween(startMs, endMs).map { rows -> rows.filter { it.type == "INCOME" }.sumOf { it.amountCents } }

    suspend fun recordReturn(originalId: Long, kind: String, amountCents: Long,
                             accountId: Long?, note: String): Boolean = database.withTransaction {
        val original = dao.getById(originalId) ?: return@withTransaction false
        if (original.deletedAt != null || original.duplicateOfId != null ||
            original.type != TransactionType.EXPENSE.name || kind !in setOf("REFUND", "REIMBURSEMENT") ||
            amountCents <= 0 || amountCents > original.amountCents - dao.returnedAmount(originalId))
            return@withTransaction false
        val account = accountId?.let { accountDao.getById(it) }
        if (accountId != null && (account == null || account.archivedAt != null || account.kind == "FUND"))
            return@withTransaction false
        val time = maxOf(System.currentTimeMillis(), (account?.updatedAt ?: 0) + 1)
        dao.insert(TransactionEntity(amountCents = amountCents, type = TransactionType.INCOME.name,
            category = original.category, merchant = original.merchant,
            note = note.take(200), source = TransactionSource.MANUAL.name,
            account = account?.name, timestamp = time, balanceAccountId = accountId,
            relatedTransactionId = originalId, relationKind = kind))
        true
    }

    suspend fun linkExistingReturn(originalId: Long, returnId: Long, kind: String): Boolean = database.withTransaction {
        val original = dao.getById(originalId) ?: return@withTransaction false
        val returned = dao.getById(returnId) ?: return@withTransaction false
        if (original.deletedAt != null || returned.deletedAt != null ||
            original.type != TransactionType.EXPENSE.name || returned.type != TransactionType.INCOME.name ||
            original.duplicateOfId != null || returned.duplicateOfId != null ||
            returned.relatedTransactionId != null || kind !in setOf("REFUND", "REIMBURSEMENT") ||
            returned.amountCents > original.amountCents - dao.returnedAmount(originalId)) return@withTransaction false
        dao.update(returned.copy(relatedTransactionId = originalId, relationKind = kind,
            category = original.category))
        true
    }

    suspend fun unlinkReturn(returnId: Long): Boolean = database.withTransaction {
        val returned = dao.getById(returnId) ?: return@withTransaction false
        if (returned.relationKind !in setOf("REFUND", "REIMBURSEMENT")) return@withTransaction false
        dao.update(returned.copy(relatedTransactionId = null,
            relationKind = if (returned.relationKind == "REFUND") "REFUND_PENDING" else "REIMBURSEMENT_PENDING",
            category = TransactionCategory.OTHER.name))
        true
    }

    suspend fun markOrdinaryIncome(id: Long): Boolean = database.withTransaction {
        val row = dao.getById(id) ?: return@withTransaction false
        if (row.deletedAt != null || row.type != TransactionType.INCOME.name ||
            row.relationKind !in setOf("REFUND_PENDING", "REIMBURSEMENT_PENDING")) return@withTransaction false
        dao.update(row.copy(relationKind = null, category = TransactionCategory.OTHER.name))
        true
    }

    suspend fun insert(transaction: TransactionEntity): Long = database.withTransaction {
        val account = transaction.balanceAccountId?.let { accountDao.getById(it) }
        val timestamp = if (transaction.source == TransactionSource.MANUAL.name && account != null)
            maxOf(transaction.timestamp, account.updatedAt + 1) else transaction.timestamp
        val dated = transaction.copy(timestamp = timestamp)
        val linked = account?.takeIf {
            eligibleBalanceAccount(it, dated) && canLinkBalance(dated)
        }
        dao.insert(dated.copy(balanceAccountId = linked?.id))
    }

    suspend fun createTransfer(fromAccountId: Long, toAccountId: Long?, amountCents: Long,
                               feeCents: Long, note: String): Boolean = database.withTransaction {
        if (amountCents <= 0 || feeCents < 0 || fromAccountId == toAccountId) return@withTransaction false
        val from = accountDao.getById(fromAccountId)?.takeIf { it.archivedAt == null && it.kind != "FUND" }
            ?: return@withTransaction false
        val to = toAccountId?.let { accountDao.getById(it)?.takeIf { target ->
            target.archivedAt == null && target.kind != "FUND" } }
        if (toAccountId != null && to == null) return@withTransaction false
        val time = maxOf(System.currentTimeMillis(), from.updatedAt + 1, (to?.updatedAt ?: 0) + 1)
        val anchor = dao.insert(TransactionEntity(amountCents = amountCents,
            type = TransactionType.TRANSFER.name, category = TransactionCategory.TRANSFER.name,
            merchant = to?.name ?: "基金（市值手动更新）", note = note.take(200),
            source = TransactionSource.MANUAL.name, account = from.name, timestamp = time,
            bankFlow = "OUT", balanceAccountId = from.id, relationKind = "TRANSFER_OUT"))
        if (to != null) {
            val otherId = dao.insert(TransactionEntity(amountCents = amountCents,
                type = TransactionType.TRANSFER.name, category = TransactionCategory.TRANSFER.name,
                merchant = from.name, note = note.take(200), source = TransactionSource.MANUAL.name,
                account = to.name, timestamp = time, bankFlow = "IN", balanceAccountId = to.id,
                relatedTransactionId = anchor, relationKind = "TRANSFER_IN"))
            dao.getById(anchor)?.let { dao.update(it.copy(relatedTransactionId = otherId)) }
        }
        if (feeCents > 0) dao.insert(TransactionEntity(amountCents = feeCents,
            type = TransactionType.EXPENSE.name, category = TransactionCategory.OTHER.name,
            merchant = "转账手续费", note = note.take(200), source = TransactionSource.MANUAL.name,
            account = from.name, timestamp = time, balanceAccountId = from.id,
            relatedTransactionId = anchor, relationKind = "TRANSFER_FEE"))
        true
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
        if (dao.activeReturnCount(id) > 0 &&
            (type != TransactionType.EXPENSE || amountCents < dao.returnedAmount(id)))
            return@withTransaction false
        val edited = editedTransaction(old, amountCents, type, category, merchant, note, accountId)
            ?: return@withTransaction false
        if (accountId != null) {
            val account = accountDao.getById(accountId) ?: return@withTransaction false
            if (!canLinkBalance(edited) || !eligibleBalanceAccount(account, edited))
                return@withTransaction false
        }
        if (old.relationKind in setOf("REFUND", "REIMBURSEMENT")) {
            val originalId = old.relatedTransactionId ?: return@withTransaction false
            val original = dao.getById(originalId) ?: return@withTransaction false
            val otherReturns = dao.returnedAmount(originalId) - old.amountCents
            if (type != TransactionType.INCOME || amountCents > original.amountCents - otherReturns ||
                category.name != original.category) return@withTransaction false
        }
        if (old.relationKind == "TRANSFER_OUT") {
            if (accountId != old.balanceAccountId) return@withTransaction false
            old.relatedTransactionId?.let { pairedId ->
                val paired = dao.getById(pairedId) ?: return@withTransaction false
                dao.update(paired.copy(amountCents = amountCents, note = note.trim()))
            }
        }
        dao.update(if (old.relationKind in setOf("REFUND_PENDING", "REIMBURSEMENT_PENDING") &&
            type != TransactionType.INCOME) edited.copy(relationKind = null) else edited)
        true
    }

    suspend fun delete(transaction: TransactionEntity) = database.withTransaction {
        if (dao.activeReturnCount(transaction.id) > 0 ||
            dao.activeDuplicateCount(transaction.id) > 0) return@withTransaction 0
        val anchorId = if (transaction.relationKind == "TRANSFER_IN")
            transaction.relatedTransactionId ?: transaction.id else transaction.id
        if (transaction.relationKind in setOf("TRANSFER_OUT", "TRANSFER_IN"))
            dao.softDeleteTransferGroup(anchorId, System.currentTimeMillis())
        else dao.softDelete(transaction.id, System.currentTimeMillis())
    }
    suspend fun restore(id: Long) = database.withTransaction {
        val transaction = dao.getById(id) ?: return@withTransaction
        val anchorId = if (transaction.relationKind == "TRANSFER_IN")
            transaction.relatedTransactionId ?: id else id
        if (transaction.relationKind in setOf("TRANSFER_OUT", "TRANSFER_IN"))
            dao.restoreTransferGroup(anchorId) else dao.restore(id)
    }

    suspend fun undoLatestImport(): Int = database.withTransaction {
        val batch = metaDao.latestActiveBatch() ?: return@withTransaction 0
        if (dao.byBatch(batch.id).any { row -> row.deletedAt == null &&
                (dao.activeReturnCount(row.id) > 0 || dao.activeDuplicateCount(row.id) > 0) })
            return@withTransaction -1
        val now = System.currentTimeMillis()
        metaDao.markBatchUndone(batch.id, now)
        dao.softDeleteBatch(batch.id, now)
    }

    suspend fun latestImportBatch(): ImportBatchEntity? = metaDao.latestActiveBatch()

    data class DuplicateSuggestion(val other: TransactionEntity, val score: Int)

    suspend fun suggestDuplicates(id: Long): List<DuplicateSuggestion> {
        val current = dao.getById(id) ?: return emptyList()
        if (current.deletedAt != null || current.type == TransactionType.TRANSFER.name) return emptyList()
        return dao.findNear(current.amountCents, current.type,
            current.timestamp - 2 * 86_400_000L, current.timestamp + 2 * 86_400_000L)
            .asSequence().filter { it.id != id && it.source != current.source && it.duplicateOfId == null }
            .map { other ->
                val hours = kotlin.math.abs(other.timestamp - current.timestamp) / 3_600_000L
                val sameMerchant = current.merchant.isNotBlank() && other.merchant.isNotBlank() &&
                    (current.merchant.contains(other.merchant) || other.merchant.contains(current.merchant))
                val orderMatch = current.sourceOrderId != null && current.sourceOrderId == other.sourceOrderId
                val score = (55 + if (hours <= 2) 15 else if (hours <= 24) 10 else 0) +
                    (if (sameMerchant) 15 else 0) + (if (orderMatch) 20 else 0) +
                    (if (current.account == other.account) 5 else 0)
                DuplicateSuggestion(other, score.coerceAtMost(99))
            }.sortedByDescending { it.score }.take(10).toList()
    }

    suspend fun linkDuplicate(firstId: Long, secondId: Long): Boolean = database.withTransaction {
        val first = dao.getById(firstId) ?: return@withTransaction false
        val second = dao.getById(secondId) ?: return@withTransaction false
        if (first.id == second.id || first.deletedAt != null || second.deletedAt != null ||
            first.duplicateOfId != null || second.duplicateOfId != null || first.type != second.type ||
            first.type == TransactionType.TRANSFER.name || first.amountCents != second.amountCents ||
            first.source == second.source ||
            kotlin.math.abs(first.timestamp - second.timestamp) > 2 * 86_400_000L) return@withTransaction false
        if (first.balanceAccountId != null && second.balanceAccountId != null &&
            first.balanceAccountId != second.balanceAccountId) return@withTransaction false
        val primary = if (first.source == TransactionSource.BANK_PDF.name) first else
            if (second.source == TransactionSource.BANK_PDF.name) second else first
        val secondary = if (primary.id == first.id) second else first
        if (secondary.relationKind in setOf("REFUND", "REIMBURSEMENT")) {
            if (primary.relatedTransactionId != null && primary.relatedTransactionId != secondary.relatedTransactionId)
                return@withTransaction false
            dao.update(primary.copy(relatedTransactionId = secondary.relatedTransactionId,
                relationKind = secondary.relationKind, category = secondary.category))
        }
        val secondaryReturns = dao.returnsFor(secondary.id)
        if (dao.returnedAmount(primary.id) + secondaryReturns.sumOf { it.amountCents } > primary.amountCents)
            return@withTransaction false
        secondaryReturns.forEach { dao.update(it.copy(relatedTransactionId = primary.id,
            category = primary.category)) }
        dao.update(secondary.copy(duplicateOfId = primary.id))
        true
    }

    suspend fun unlinkDuplicate(id: Long): Boolean = database.withTransaction {
        val transaction = dao.getById(id) ?: return@withTransaction false
        if (transaction.duplicateOfId == null) return@withTransaction false
        val primary = dao.getById(transaction.duplicateOfId)
        val sameReturn = transaction.relationKind in setOf("REFUND", "REIMBURSEMENT") &&
            primary != null && primary.relatedTransactionId == transaction.relatedTransactionId &&
            primary.relationKind == transaction.relationKind
        dao.update(transaction.copy(duplicateOfId = null,
            relatedTransactionId = if (sameReturn) null else transaction.relatedTransactionId,
            relationKind = if (sameReturn) "${transaction.relationKind}_PENDING" else transaction.relationKind,
            category = if (sameReturn) TransactionCategory.OTHER.name else transaction.category))
        true
    }

    data class ImportResult(
        val found: Int,
        val added: Int,
        val duplicates: Int,
        val transfers: Int,
        val directionsUpdated: Int,
        val balancesLinked: Int,
        val batchId: Long? = null,
    )

    data class ImportAssessment(val exactDuplicates: Int, val crossSourceCandidates: Int)

    suspend fun assessImport(bills: List<TransactionEntity>): ImportAssessment {
        val existing = dao.getAllRaw()
        val keys = existing.mapNotNull { it.importKey }.toHashSet()
        val byAmount = existing.asSequence().filter { it.deletedAt == null && it.duplicateOfId == null }
            .groupBy { it.amountCents to it.type }
        val exact = bills.count { it.importKey != null && it.importKey in keys }
        val possible = bills.count { bill ->
            bill.type != TransactionType.TRANSFER.name && bill.importKey !in keys &&
                byAmount[bill.amountCents to bill.type].orEmpty().any { other ->
                other.source != bill.source && kotlin.math.abs(other.timestamp - bill.timestamp) <= 2 * 86_400_000L
            }
        }
        return ImportAssessment(exact, possible)
    }

    suspend fun importBills(bills: List<TransactionEntity>, batch: ImportBatchEntity? = null): ImportResult = database.withTransaction {
        var added = 0
        var duplicates = 0
        var balancesLinked = 0
        val batchId = batch?.let { metaDao.insertBatch(it) }
        val balanceAccounts = accountDao.getAll()
        for (bill in bills) {
            val exact = bill.importKey?.let { dao.getByImportKey(it) }
            if (exact != null) {
                if (exact.deletedAt != null && batchId != null) {
                    val linked = matchingAccountForLiveTransaction(balanceAccounts, bill)
                    dao.update(exact.copy(deletedAt = null, importBatchId = batchId,
                        balanceAccountId = linked?.id))
                    added++
                    if (linked != null) balancesLinked++
                    continue
                }
                duplicates++
                continue
            }
            if (bill.importKey != null && metaDao.reserveIdentity(ImportIdentityEntity(bill.importKey, 0)) == -1L) {
                duplicates++
                continue
            }
            val linked = matchingAccountForLiveTransaction(balanceAccounts, bill)
            val id = dao.insert(bill.copy(balanceAccountId = linked?.id, importBatchId = batchId))
            bill.importKey?.let { metaDao.setIdentityTransaction(it, id) }
            if (linked != null) balancesLinked++
            added++
        }
        if (added == 0 && batchId != null) metaDao.deleteBatch(batchId)
        ImportResult(bills.size, added, duplicates, 0, 0, balancesLinked,
            if (added == 0) null else batchId)
    }
}

internal fun accountingPeriod(all: List<TransactionEntity>, startMs: Long, endMs: Long): List<TransactionEntity> {
    val active = all.filter { it.deletedAt == null && it.duplicateOfId == null }
    val returned = active.asSequence().filter { it.relationKind in setOf("REFUND", "REIMBURSEMENT") && it.type == "INCOME" }
        .groupBy { it.relatedTransactionId }.mapValues { (_, rows) -> rows.sumOf { it.amountCents } }
    return active.asSequence().filter { it.timestamp >= startMs && it.timestamp < endMs &&
        it.relationKind !in setOf("REFUND", "REIMBURSEMENT", "REFUND_PENDING",
            "REIMBURSEMENT_PENDING", "TRANSFER_IN") }
        .map { row -> if (row.type == "EXPENSE") row.copy(amountCents =
            (row.amountCents - (returned[row.id] ?: 0L)).coerceAtLeast(0L)) else row }
        .sortedByDescending { it.timestamp }.toList()
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

package com.bookkeeping.app.data.repository

import androidx.room.withTransaction
import com.bookkeeping.app.data.local.BookkeepingDatabase
import com.bookkeeping.app.data.local.dao.AccountDao
import com.bookkeeping.app.data.local.dao.TransactionDao
import com.bookkeeping.app.data.local.dao.LedgerMetaDao
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.ImportBatchEntity
import com.bookkeeping.app.data.local.entity.DuplicateLinkChangeEntity
import com.bookkeeping.app.data.local.entity.ImportIdentityEntity
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.local.entity.TransactionSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
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
        dao.observeAccountingInputs(startMs, endMs).map { all -> accountingPeriod(all, startMs, endMs) }.flowOn(Dispatchers.Default)

    fun observeDeleted() = dao.observeDeleted()
    fun observeImportBatches() = metaDao.observeBatches()
    suspend fun getById(id: Long) = dao.getById(id)

    fun observeTotalExpenseBetween(startMs: Long, endMs: Long): Flow<Long> =
        observeBetween(startMs, endMs).map { rows -> rows.filter { it.type == "EXPENSE" }.sumOf { it.amountCents } }

    fun observeTotalIncomeBetween(startMs: Long, endMs: Long): Flow<Long> =
        observeBetween(startMs, endMs).map { rows -> rows.filter { it.type == "INCOME" }.sumOf { it.amountCents } }

    suspend fun recordReturn(originalId: Long, kind: String, amountCents: Long,
                             accountId: Long?, note: String, occurredAt: Long = System.currentTimeMillis(), requestId: String? = null): Boolean = database.writeLedger {
        requestId?.let { dao.getByRequestId(it)?.let { existing ->
            return@writeLedger existing.deletedAt == null && existing.relatedTransactionId == originalId &&
                existing.relationKind == kind && existing.amountCents == amountCents &&
                existing.balanceAccountId == accountId && existing.note == note.take(200) && existing.timestamp == occurredAt
        } }
        val original = dao.getById(originalId) ?: return@writeLedger false
        if (original.deletedAt != null || original.duplicateOfId != null ||
            original.type != TransactionType.EXPENSE.name || kind !in setOf("REFUND", "REIMBURSEMENT") ||
            amountCents <= 0 || amountCents > original.amountCents - dao.returnedAmount(originalId))
            return@writeLedger false
        val account = accountId?.let { accountDao.getById(it) }
        if (accountId != null && (account == null || account.archivedAt != null || account.kind == "FUND"))
            return@writeLedger false
        val time = occurredAt
        dao.insert(TransactionEntity(amountCents = amountCents, type = TransactionType.INCOME.name,
            category = original.category, merchant = original.merchant,
            note = note.take(200), source = TransactionSource.MANUAL.name,
            account = account?.name, timestamp = time, balanceAccountId = accountId,
            relatedTransactionId = originalId, relationKind = kind, createdAt = System.currentTimeMillis(), clientRequestId = requestId))
        true
    }

    suspend fun linkExistingReturn(originalId: Long, returnId: Long, kind: String): Boolean = database.writeLedger {
        val original = dao.getById(originalId) ?: return@writeLedger false
        val returned = dao.getById(returnId) ?: return@writeLedger false
        if (original.deletedAt != null || returned.deletedAt != null ||
            original.type != TransactionType.EXPENSE.name || returned.type != TransactionType.INCOME.name ||
            original.duplicateOfId != null || returned.duplicateOfId != null ||
            returned.relatedTransactionId != null || kind !in setOf("REFUND", "REIMBURSEMENT") ||
            returned.amountCents > original.amountCents - dao.returnedAmount(originalId)) return@writeLedger false
        dao.update(returned.copy(relatedTransactionId = originalId, relationKind = kind,
            category = original.category, reviewReason = null, modifiedAt = System.currentTimeMillis()))
        true
    }

    suspend fun unlinkReturn(returnId: Long): Boolean = database.writeLedger {
        val returned = dao.getById(returnId) ?: return@writeLedger false
        if (returned.relationKind !in setOf("REFUND", "REIMBURSEMENT")) return@writeLedger false
        dao.update(returned.copy(relatedTransactionId = null,
            relationKind = if (returned.relationKind == "REFUND") "REFUND_PENDING" else "REIMBURSEMENT_PENDING",
            category = TransactionCategory.OTHER.name))
        true
    }

    suspend fun markOrdinaryIncome(id: Long): Boolean = database.writeLedger {
        val row = dao.getById(id) ?: return@writeLedger false
        if (row.deletedAt != null || row.type != TransactionType.INCOME.name ||
            row.relationKind !in setOf("REFUND_PENDING", "REIMBURSEMENT_PENDING")) return@writeLedger false
        dao.update(row.copy(relationKind = null, category = TransactionCategory.OTHER.name))
        true
    }

    suspend fun insert(transaction: TransactionEntity): Long = database.writeLedger {
        transaction.clientRequestId?.let { requestId -> dao.getByRequestId(requestId)?.let { existing ->
            check(existing.amountCents == transaction.amountCents && existing.type == transaction.type &&
                existing.timestamp == transaction.timestamp && existing.merchant == transaction.merchant && existing.note == transaction.note &&
                existing.category == transaction.category && existing.balanceAccountId == transaction.balanceAccountId && existing.deletedAt == null) {
                "这个表单已经保存，请打开原记录编辑"
            }
            return@writeLedger existing.id
        } }
        require(transaction.amountCents in 1..MAX_TRANSACTION_CENTS) { "金额需大于0且不超过一万亿元" }
        val account = transaction.balanceAccountId?.let { accountDao.getById(it) }
        require(transaction.balanceAccountId == null || account != null && account.archivedAt == null && account.kind != "FUND") {
            "账户不存在或不可用于流水"
        }
        val dated = transaction.copy(createdAt = System.currentTimeMillis(),
            relationKind = transaction.relationKind ?: pendingReturnKind(transaction.type, transaction.note + transaction.merchant))
        val linked = account?.takeIf {
            (dated.source == "MANUAL" || eligibleBalanceAccount(it, dated)) && canLinkBalance(dated)
        }
        val complete = dated.copy(balanceAccountId = linked?.id, account = dated.account ?: account?.name)
        val possible = dao.findAmountNear(complete.amountCents, complete.timestamp - 2 * 86_400_000L,
            complete.timestamp + 2 * 86_400_000L).any { possibleSameMovement(complete, it) }
        dao.insert(complete.copy(reviewReason = complete.reviewReason ?: if (possible)
            "疑似已有同一笔流水，请核对后计入" else null))
    }

    suspend fun createTransfer(fromAccountId: Long, toAccountId: Long?, amountCents: Long,
                               feeCents: Long, note: String, occurredAt: Long = System.currentTimeMillis(), requestId: String? = null): Boolean = database.writeLedger {
        requestId?.let { dao.getByRequestId(it)?.let { existing ->
            val group = dao.transferGroup(existing.id)
            val incoming = group.firstOrNull { row -> row.relationKind == "TRANSFER_IN" }
            val fee = group.firstOrNull { row -> row.relationKind == "TRANSFER_FEE" }
            return@writeLedger existing.relationKind == "TRANSFER_OUT" && group.all { row -> row.deletedAt == null } &&
                existing.balanceAccountId == fromAccountId && incoming?.balanceAccountId == toAccountId &&
                existing.amountCents == amountCents && (fee?.amountCents ?: 0L) == feeCents &&
                existing.timestamp == occurredAt && existing.note == note.take(200)
        } }
        if (amountCents !in 1..MAX_TRANSACTION_CENTS || feeCents !in 0..MAX_TRANSACTION_CENTS || fromAccountId == toAccountId) return@writeLedger false
        val from = accountDao.getById(fromAccountId)?.takeIf { it.archivedAt == null && it.kind != "FUND" }
            ?: return@writeLedger false
        val to = toAccountId?.let { accountDao.getById(it)?.takeIf { target ->
            target.archivedAt == null && target.kind != "FUND" } }
        if (toAccountId != null && to == null) return@writeLedger false
        val time = occurredAt
        suspend fun reviewReason(row: TransactionEntity): String? = if (dao.findAmountNear(row.amountCents,
            time - 2 * 86_400_000L, time + 2 * 86_400_000L).any { possibleSameMovement(row, it) })
            "疑似已有同一资金腿，请核对转账证据" else null
        val outgoing = TransactionEntity(amountCents = amountCents,
            type = TransactionType.TRANSFER.name, category = TransactionCategory.TRANSFER.name,
            merchant = to?.name ?: "基金（市值手动更新）", note = note.take(200),
            source = TransactionSource.MANUAL.name, account = from.name, timestamp = time,
            bankFlow = "OUT", balanceAccountId = from.id, relationKind = "TRANSFER_OUT", createdAt = System.currentTimeMillis(), clientRequestId = requestId)
        val anchor = dao.insert(outgoing.copy(reviewReason = reviewReason(outgoing)))
        if (to != null) {
            val incoming = TransactionEntity(amountCents = amountCents,
                type = TransactionType.TRANSFER.name, category = TransactionCategory.TRANSFER.name,
                merchant = from.name, note = note.take(200), source = TransactionSource.MANUAL.name,
                account = to.name, timestamp = time, bankFlow = "IN", balanceAccountId = to.id,
                relatedTransactionId = anchor, relationKind = "TRANSFER_IN", createdAt = System.currentTimeMillis())
            val otherId = dao.insert(incoming.copy(reviewReason = reviewReason(incoming)))
            dao.getById(anchor)?.let { dao.update(it.copy(relatedTransactionId = otherId)) }
        }
        if (feeCents > 0) { val fee = TransactionEntity(amountCents = feeCents,
            type = TransactionType.EXPENSE.name, category = TransactionCategory.OTHER.name,
            merchant = "转账手续费", note = note.take(200), source = TransactionSource.MANUAL.name,
            account = from.name, timestamp = time, balanceAccountId = from.id,
            relatedTransactionId = anchor, relationKind = "TRANSFER_FEE", createdAt = System.currentTimeMillis())
            dao.insert(fee.copy(reviewReason = reviewReason(fee)))
        }
        true
    }

    // 自动抓取路径专用：银行通知按账户、金额、方向和正文去重；其他通知沿用原有时间窗口。
    // 返回 null 表示重复，返回 Long 表示新插入的 id。
    suspend fun insertIfNotDuplicate(
        transaction: TransactionEntity,
        windowMs: Long = 60_000L,
    ): Long? = database.writeLedger {
        val bankSources = setOf(TransactionSource.BANK_SMS.name, TransactionSource.BANK_APP.name)
        if (transaction.source in bankSources) {
            val nearby = dao.findNear(transaction.amountCents, transaction.type,
                transaction.timestamp - 300_000L, transaction.timestamp + 300_000L)
            val duplicate = nearby.any { sameBankMovement(it, transaction, windowMs) }
            if (duplicate) return@writeLedger null
            val account = matchingAccountForLiveTransaction(accountDao.getAll(), transaction)
            return@writeLedger dao.insert(transaction.copy(balanceAccountId = account?.id))
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
        if (existing != null) return@writeLedger null
        val account = matchingAccountForLiveTransaction(accountDao.getAll(), transaction)
        dao.insert(transaction.copy(balanceAccountId = account?.id))
    }

    suspend fun insertBankSmsIfNotDuplicate(transaction: TransactionEntity): Long? =
        if (transaction.source == TransactionSource.BANK_SMS.name)
            insertIfNotDuplicate(transaction) else null

    suspend fun setBalanceAccount(transactionId: Long, accountId: Long?, ignore: Boolean = false): Boolean = database.writeLedger {
        val transaction = dao.getById(transactionId) ?: return@writeLedger false
        if (!canLinkBalance(transaction)) return@writeLedger false
        val account = accountId?.let { accountDao.getById(it) }
        if (accountId != null && (account == null || !eligibleBalanceAccount(account, transaction)))
            return@writeLedger false
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
        occurredAt: Long? = null,
    ): Boolean = database.writeLedger {
        val old = dao.getById(id) ?: return@writeLedger false
        if (old.deletedAt != null || old.duplicateOfId != null) return@writeLedger false
        if (dao.activeDuplicateCount(id) > 0 && (old.amountCents != amountCents || old.type != type.name))
            return@writeLedger false
        if (dao.activeReturnCount(id) > 0 &&
            (type != TransactionType.EXPENSE || amountCents < dao.returnedAmount(id)))
            return@writeLedger false
        val edited = editedTransaction(old, amountCents, type, category, merchant, note, accountId)
            ?.copy(timestamp = occurredAt ?: old.timestamp, modifiedAt = System.currentTimeMillis(), reviewReason = null)
            ?: return@writeLedger false
        if (accountId != null) {
            val account = accountDao.getById(accountId) ?: return@writeLedger false
            if (!canLinkBalance(edited.copy(reviewReason = null)) ||
                !(edited.source == "MANUAL" && account.archivedAt == null && account.kind != "FUND" || eligibleBalanceAccount(account, edited)))
                return@writeLedger false
        }
        if (old.relationKind in setOf("REFUND", "REIMBURSEMENT")) {
            val originalId = old.relatedTransactionId ?: return@writeLedger false
            val original = dao.getById(originalId) ?: return@writeLedger false
            val otherReturns = dao.returnedAmount(originalId) - old.amountCents
            if (type != TransactionType.INCOME || amountCents > original.amountCents - otherReturns ||
                category.name != original.category) return@writeLedger false
        }
        if (isPairedTransfer(old)) {
            if (accountId != old.balanceAccountId) return@writeLedger false
            old.relatedTransactionId?.let { pairedId ->
                val paired = dao.getById(pairedId) ?: return@writeLedger false
                if (dao.activeDuplicateCount(paired.id) > 0 && paired.amountCents != amountCents) return@writeLedger false
                dao.update(paired.copy(amountCents = amountCents, note = note.trim(),
                    timestamp = edited.timestamp, modifiedAt = System.currentTimeMillis()))
            }
        }
        if (isPairedTransfer(old)) {
            val anchor = if (old.relationKind == "TRANSFER_IN") old.relatedTransactionId ?: old.id else old.id
            dao.transferGroup(anchor).filter { it.relationKind == "TRANSFER_FEE" && it.deletedAt == null }.forEach {
                dao.update(it.copy(timestamp = edited.timestamp, modifiedAt = System.currentTimeMillis()))
            }
        }
        dao.update(if (old.relationKind in setOf("REFUND_PENDING", "REIMBURSEMENT_PENDING") &&
            type != TransactionType.INCOME) edited.copy(relationKind = null) else edited)
        if (type == TransactionType.EXPENSE) dao.syncReturnCategory(id, category.name)
        true
    }

    suspend fun delete(requested: TransactionEntity) = database.writeLedger {
        val transaction = dao.getById(requested.id)?.takeIf { it.deletedAt == null } ?: return@writeLedger 0
        if (dao.activeReturnCount(transaction.id) > 0 ||
            dao.activeDuplicateCount(transaction.id) > 0) return@writeLedger 0
        val anchorId = if (transaction.relationKind == "TRANSFER_IN")
            transaction.relatedTransactionId ?: transaction.id else transaction.id
        if (transaction.relationKind in setOf("TRANSFER_OUT", "TRANSFER_IN"))
            {
                if (dao.transferGroup(anchorId).any { it.deletedAt == null &&
                    (dao.activeReturnCount(it.id) > 0 || dao.activeDuplicateCount(it.id) > 0) }) return@writeLedger 0
                dao.softDeleteTransferGroup(anchorId, System.currentTimeMillis())
            }
        else dao.softDelete(transaction.id, System.currentTimeMillis())
    }
    suspend fun restore(id: Long): Boolean = database.writeLedger {
        val transaction = dao.getById(id) ?: return@writeLedger false
        val deletedAt = transaction.deletedAt ?: return@writeLedger true
        if (transaction.duplicateOfId?.let { dao.getById(it)?.deletedAt != null } == true) return@writeLedger false
        if (transaction.duplicateOfId == null && transaction.relationKind in setOf("REFUND", "REIMBURSEMENT")) {
            val original = transaction.relatedTransactionId?.let { dao.getById(it) } ?: return@writeLedger false
            if (original.deletedAt != null || original.duplicateOfId != null || original.type != "EXPENSE" ||
                dao.returnedAmount(original.id) + transaction.amountCents > original.amountCents)
                return@writeLedger false
        }
        val anchorId = if (transaction.relationKind == "TRANSFER_IN")
            transaction.relatedTransactionId ?: id else id
        if (transaction.relationKind in setOf("TRANSFER_OUT", "TRANSFER_IN"))
            dao.restoreTransferGroup(anchorId, deletedAt) else dao.restore(id)
        true
    }

    suspend fun undoLatestImport(): Int {
        val id = metaDao.latestActiveBatch()?.id ?: return 0
        return undoImport(id)
    }

    suspend fun undoImport(batchId: Long): Int = database.writeLedger {
        val batch = metaDao.batchById(batchId)?.takeIf { it.undoneAt == null } ?: return@writeLedger 0
        if (dao.byBatch(batch.id).any { row -> row.deletedAt == null &&
                (dao.activeReturnCount(row.id) > 0 || dao.activeDuplicateCount(row.id) > 0) })
            return@writeLedger -1
        val now = System.currentTimeMillis()
        metaDao.markBatchUndone(batch.id, now)
        dao.softDeleteBatch(batch.id, now)
    }

    suspend fun restoreImport(batchId: Long): Int = database.writeLedger {
        val batch = metaDao.batchById(batchId) ?: return@writeLedger 0
        val at = batch.undoneAt ?: return@writeLedger 0
        // Returning evidence cannot exceed a newly recorded refund after the batch was undone.
        val restoring = dao.byBatch(batchId).filter { it.deletedAt == at }
        val restoringIds = restoring.map { it.id }.toSet()
        for (row in restoring) {
            row.duplicateOfId?.let { primaryId ->
                val primary = dao.getById(primaryId) ?: return@writeLedger -1
                if (primary.duplicateOfId != null || primary.deletedAt != null && primaryId !in restoringIds)
                    return@writeLedger -1
            }
        }
        val returns = restoring.filter { it.relationKind in setOf("REFUND", "REIMBURSEMENT") }
            .groupBy { it.relatedTransactionId }
        for ((originalId, rows) in returns) {
            val original = originalId?.let { dao.getById(it) } ?: return@writeLedger -1
            if (original.type != "EXPENSE" || original.duplicateOfId != null ||
                original.deletedAt != null && original.id !in restoringIds ||
                dao.returnedAmount(original.id) + rows.filter { it.duplicateOfId == null }.sumOf { it.amountCents } > original.amountCents)
                return@writeLedger -1
        }
        metaDao.restoreBatchState(batchId)
        dao.restoreBatchRows(batchId, at)
    }

    suspend fun confirmIndependent(id: Long): Boolean = database.writeLedger {
        val row = dao.getById(id)?.takeIf { it.deletedAt == null && it.duplicateOfId == null } ?: return@writeLedger false
        val reviewed = row.copy(reviewReason = null, modifiedAt = System.currentTimeMillis())
        val linked = reviewed.balanceAccountId ?: if (reviewed.balanceIgnored) null else
            matchingAccountForLiveTransaction(accountDao.getAll(), reviewed)?.id
        dao.update(reviewed.copy(balanceAccountId = linked))
        true
    }

    suspend fun latestImportBatch(): ImportBatchEntity? = metaDao.latestActiveBatch()

    data class DuplicateSuggestion(val other: TransactionEntity, val score: Int)

    suspend fun suggestDuplicates(id: Long): List<DuplicateSuggestion> {
        val current = dao.getById(id) ?: return emptyList()
        if (current.deletedAt != null) return emptyList()
        return dao.findAmountNear(current.amountCents,
            current.timestamp - 2 * 86_400_000L, current.timestamp + 2 * 86_400_000L)
            .asSequence().filter { possibleSameMovement(current, it) }
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

    suspend fun linkDuplicate(firstId: Long, secondId: Long): Boolean = database.writeLedger {
        val first = dao.getById(firstId) ?: return@writeLedger false
        val second = dao.getById(secondId) ?: return@writeLedger false
        if (!possibleSameMovement(first, second) || isPairedTransfer(first) && isPairedTransfer(second)) return@writeLedger false
        val primary = when {
            isPairedTransfer(first) -> first
            isPairedTransfer(second) -> second
            first.source == "MANUAL" && first.balanceAccountId != null -> first
            second.source == "MANUAL" && second.balanceAccountId != null -> second
            first.source == "BANK_PDF" -> first
            second.source == "BANK_PDF" -> second
            else -> first
        }
        val secondary = if (primary.id == first.id) second else first
        if (dao.activeDuplicateCount(secondary.id) > 0) return@writeLedger false
        var updatedPrimary = primary.copy(reviewReason = null)
        if (secondary.relationKind in setOf("REFUND", "REIMBURSEMENT")) {
            if (primary.relatedTransactionId != null && primary.relatedTransactionId != secondary.relatedTransactionId)
                return@writeLedger false
            updatedPrimary = updatedPrimary.copy(relatedTransactionId = secondary.relatedTransactionId,
                relationKind = secondary.relationKind, category = secondary.category)
        }
        if (metaDao.duplicateChanges(secondary.id).isNotEmpty()) return@writeLedger false
        val secondaryReturns = dao.returnsFor(secondary.id)
        val before = (listOf(primary, secondary) + secondaryReturns).associateBy { it.id }
        val changed = linkedMapOf(
            primary.id to updatedPrimary,
            secondary.id to secondary.copy(duplicateOfId = primary.id, reviewReason = null),
        )
        secondaryReturns.forEach { changed[it.id] = it.copy(relatedTransactionId = primary.id,
            category = updatedPrimary.category) }
        if (!validReturnChanges(changed)) return@writeLedger false
        // A secondary marker is saved even when no relationship needed to move.
        for ((id, after) in changed) {
            val old = before.getValue(id)
            if (id == secondary.id || old.relatedTransactionId != after.relatedTransactionId ||
                old.relationKind != after.relationKind || old.category != after.category) {
                metaDao.insertDuplicateChange(DuplicateLinkChangeEntity(secondary.id, id, primary.id,
                    old.relatedTransactionId, old.relationKind, old.category,
                    after.relatedTransactionId, after.relationKind, after.category))
            }
        }
        changed.values.forEach { dao.update(it) }
        true
    }

    suspend fun unlinkDuplicate(id: Long): Boolean = database.writeLedger {
        val secondary = dao.getById(id)?.takeIf { it.deletedAt == null } ?: return@writeLedger false
        val primaryId = secondary.duplicateOfId ?: return@writeLedger false
        val primary = dao.getById(primaryId)?.takeIf { it.deletedAt == null && it.duplicateOfId == null }
            ?: return@writeLedger false
        val history = metaDao.duplicateChanges(id)
        val changed = linkedMapOf<Long, TransactionEntity>()
        if (history.isEmpty()) {
            // v6 links have no provenance. Never guess which refunds used to belong to this row.
            if (secondary.relationKind in setOf("REFUND", "REIMBURSEMENT") ||
                dao.returnHistoryCount(primary.id) > 0 || dao.returnHistoryCount(secondary.id) > 0)
                return@writeLedger false
        } else {
            if (history.none { it.transactionId == id } || history.any { it.primaryId != primaryId })
                return@writeLedger false
            // Later evidence can depend on metadata adopted by this link, even while soft-deleted.
            if (history.any { it.transactionId == primaryId } &&
                dao.otherDuplicateEvidenceCount(primaryId, id) > 0) return@writeLedger false
            for (change in history) {
                val current = dao.getById(change.transactionId) ?: return@writeLedger false
                if (current.relatedTransactionId != change.afterRelatedId || current.relationKind != change.afterRelationKind ||
                    current.category != change.afterCategory || current.id != id && current.duplicateOfId != null ||
                    current.id != id && current.id != primaryId && dao.otherDuplicateEvidenceCount(current.id, current.id) > 0)
                    return@writeLedger false
                changed[current.id] = current.copy(relatedTransactionId = change.beforeRelatedId,
                    relationKind = change.beforeRelationKind, category = change.beforeCategory,
                    modifiedAt = System.currentTimeMillis())
            }
        }
        changed[id] = (changed[id] ?: secondary).copy(duplicateOfId = null,
            reviewReason = "已取消关联，请确认是否为独立交易", modifiedAt = System.currentTimeMillis())
        if (!validReturnChanges(changed)) return@writeLedger false
        changed.values.forEach { dao.update(it) }
        metaDao.deleteDuplicateChanges(id)
        true
    }

    /** Validate the prospective refund graph before changing any row, including reviewed returns. */
    private suspend fun validReturnChanges(changed: Map<Long, TransactionEntity>): Boolean {
        val parents = mutableSetOf<Long>()
        for ((id, after) in changed) {
            for (row in listOfNotNull(dao.getById(id), after)) {
                if (row.relationKind in setOf("REFUND", "REIMBURSEMENT")) {
                    val parentId = row.relatedTransactionId ?: return false
                    parents += parentId
                }
            }
        }
        for (parentId in parents) {
            val rows = (dao.returnsFor(parentId).associateBy { it.id } + changed).values.filter {
                it.deletedAt == null && it.duplicateOfId == null && it.relatedTransactionId == parentId &&
                    it.relationKind in setOf("REFUND", "REIMBURSEMENT")
            }
            if (rows.isEmpty()) continue
            val parent = changed[parentId] ?: dao.getById(parentId) ?: return false
            if (parent.deletedAt != null || parent.duplicateOfId != null || parent.type != "EXPENSE") return false
            var remaining = parent.amountCents
            for (row in rows) {
                if (row.type != "INCOME" || row.amountCents <= 0 || row.amountCents > remaining) return false
                remaining -= row.amountCents
            }
        }
        return true
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
        val accounts = accountDao.getAll()
        val keys = existing.mapNotNull { it.importKey }.toHashSet()
        val byAmount = existing.asSequence().filter { it.deletedAt == null && it.duplicateOfId == null }
            .groupBy { it.amountCents to movementDirection(it) }
        val exact = bills.count { it.importKey != null && it.importKey in keys }
        val possible = bills.count { original ->
            val bill = original.copy(balanceAccountId = matchingAccountForLiveTransaction(accounts, original.copy(reviewReason = null))?.id)
            bill.importKey !in keys &&
                byAmount[bill.amountCents to movementDirection(bill)].orEmpty().any { other ->
                possibleSameMovement(bill, other)
            }
        }
        return ImportAssessment(exact, possible)
    }

    suspend fun importBills(bills: List<TransactionEntity>, batch: ImportBatchEntity? = null): ImportResult = database.writeLedger {
        var added = 0
        var duplicates = 0
        var balancesLinked = 0
        var directionsUpdated = 0
        val batchId = batch?.let { metaDao.insertBatch(it) }
        val balanceAccounts = accountDao.getAll()
        for (bill in bills) {
            val exact = bill.importKey?.let { dao.getByImportKey(it) }
            if (exact != null) {
                // A matching import is evidence, never consent to restore a tombstone.
                if (exact.deletedAt != null) { duplicates++; continue }
                val upgraded = directionUpgrade(exact, bill)
                if (upgraded != null) { dao.update(upgraded); directionsUpdated++ }
                duplicates++
                continue
            }
            if (bill.importKey != null && metaDao.reserveIdentity(ImportIdentityEntity(bill.importKey, 0)) == -1L) {
                duplicates++
                continue
            }
            val linked = matchingAccountForLiveTransaction(balanceAccounts, bill)
            val dated = bill.copy(balanceAccountId = linked?.id)
            val candidates = dao.findAmountNear(bill.amountCents,
                bill.timestamp - 2 * 86_400_000L, bill.timestamp + 2 * 86_400_000L)
            val needsReview = candidates.any { possibleSameMovement(dated, it) }
            val id = dao.insert(dated.copy(importBatchId = batchId, createdAt = System.currentTimeMillis(),
                reviewReason = bill.reviewReason ?: if (needsReview) "疑似重复流水，核对前不计入统计和余额" else null))
            bill.importKey?.let { metaDao.setIdentityTransaction(it, id) }
            if (linked != null && !needsReview && bill.reviewReason == null) balancesLinked++
            added++
        }
        if (added == 0 && batchId != null) metaDao.deleteBatch(batchId)
        ImportResult(bills.size, added, duplicates, bills.count { it.type == "TRANSFER" }, directionsUpdated, balancesLinked,
            if (added == 0) null else batchId)
    }
}

internal fun accountingPeriod(all: List<TransactionEntity>, startMs: Long, endMs: Long): List<TransactionEntity> {
    val active = all.filter { it.deletedAt == null && it.duplicateOfId == null && it.reviewReason == null }
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
    if (amountCents !in 1..MAX_TRANSACTION_CENTS || isPairedTransfer(old) && type != TransactionType.TRANSFER)
        return null
    if (type == TransactionType.TRANSFER && category != TransactionCategory.TRANSFER) return null
    if (type == TransactionType.TRANSFER && old.bankFlow == null) return null
    return old.copy(
        amountCents = amountCents,
        type = type.name,
        category = category.name,
        merchant = merchant.trim(),
        note = note.trim(),
        bankFlow = when (type) { TransactionType.INCOME -> "IN"; TransactionType.EXPENSE -> "OUT"; else -> old.bankFlow },
        balanceAccountId = accountId,
        balanceIgnored = accountId == null && old.balanceIgnored,
    )
}

internal fun directionUpgrade(existing: TransactionEntity, imported: TransactionEntity): TransactionEntity? =
    if (existing.importKey != null && existing.importKey == imported.importKey &&
        existing.bankFlow == null && imported.bankFlow != null)
        existing.copy(bankFlow = imported.bankFlow, sourceAccountKind = existing.sourceAccountKind ?: imported.sourceAccountKind,
            sourcePaymentMethod = existing.sourcePaymentMethod ?: imported.sourcePaymentMethod,
            sourceBalanceCents = existing.sourceBalanceCents ?: imported.sourceBalanceCents,
            sourceIdentity = existing.sourceIdentity ?: imported.sourceIdentity, rawData = existing.rawData ?: imported.rawData,
            parserVersion = imported.parserVersion)
    else null

internal const val MAX_TRANSACTION_CENTS = 100_000_000_000_000L
internal fun pendingReturnKind(type: String, text: String): String? = if (type != "INCOME") null else when {
    listOf("退款", "退回", "冲正").any(text::contains) -> "REFUND_PENDING"
    text.contains("报销") -> "REIMBURSEMENT_PENDING"
    else -> null
}

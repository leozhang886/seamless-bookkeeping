package com.bookkeeping.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bookkeeping.app.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

// DAO = Data Access Object
// 这是 Room 的"接口模板"，写出 SQL 注解，Room 自动生成实现类。
// 嵌入式类比：声明寄存器读写接口，Room 帮你生成具体的访问代码。
@Dao
interface TransactionDao {

    // 插入发生冲突时中止，不能覆盖已有流水。返回新插入的 id
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(transaction: TransactionEntity): Long

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Query("UPDATE transactions SET deletedAt = :time WHERE id = :id AND deletedAt IS NULL")
    suspend fun softDelete(id: Long, time: Long): Int

    @Query("UPDATE transactions SET deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: Long)

    @Query("UPDATE transactions SET deletedAt = :time WHERE deletedAt IS NULL AND (id = :anchorId OR (relatedTransactionId = :anchorId AND relationKind IN ('TRANSFER_IN','TRANSFER_FEE')))")
    suspend fun softDeleteTransferGroup(anchorId: Long, time: Long): Int

    @Query("UPDATE transactions SET deletedAt = NULL WHERE deletedAt = :deletedAt AND (id = :anchorId OR (relatedTransactionId = :anchorId AND relationKind IN ('TRANSFER_IN','TRANSFER_FEE')))")
    suspend fun restoreTransferGroup(anchorId: Long, deletedAt: Long)

    @Query("UPDATE transactions SET deletedAt = :time WHERE importBatchId = :batchId AND deletedAt IS NULL")
    suspend fun softDeleteBatch(batchId: Long, time: Long): Int

    @Query("SELECT * FROM transactions WHERE id = :anchorId OR (relatedTransactionId = :anchorId AND relationKind IN ('TRANSFER_IN','TRANSFER_FEE'))")
    suspend fun transferGroup(anchorId: Long): List<TransactionEntity>
    @Query("SELECT * FROM transactions WHERE deletedAt IS NULL AND duplicateOfId IS NULL AND reviewReason IS NULL AND ((timestamp >= :startMs AND timestamp < :endMs) OR (relationKind IN ('REFUND','REIMBURSEMENT') AND relatedTransactionId IN (SELECT id FROM transactions WHERE timestamp >= :startMs AND timestamp < :endMs)))")
    fun observeAccountingInputs(startMs: Long, endMs: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE importBatchId = :batchId")
    suspend fun byBatch(batchId: Long): List<TransactionEntity>

    @Query("UPDATE transactions SET deletedAt = NULL WHERE importBatchId = :batchId AND deletedAt = :deletedAt")
    suspend fun restoreBatchRows(batchId: Long, deletedAt: Long): Int
    @Query("SELECT * FROM transactions WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeDeleted(): Flow<List<TransactionEntity>>
    @Query("SELECT * FROM transactions WHERE amountCents = :amountCents AND timestamp BETWEEN :startMs AND :endMs AND deletedAt IS NULL AND duplicateOfId IS NULL")
    suspend fun findAmountNear(amountCents: Long, startMs: Long, endMs: Long): List<TransactionEntity>

    @Query("DELETE FROM transactions")
    suspend fun clearAll()

    @Query("SELECT * FROM transactions")
    suspend fun getAllRaw(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE deletedAt IS NULL AND duplicateOfId IS NULL")
    fun observeActiveForAccounting(): Flow<List<TransactionEntity>>

    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM transactions WHERE relatedTransactionId = :originalId AND relationKind IN ('REFUND', 'REIMBURSEMENT') AND deletedAt IS NULL AND duplicateOfId IS NULL")
    suspend fun returnedAmount(originalId: Long): Long

    @Query("SELECT COUNT(*) FROM transactions WHERE relatedTransactionId = :originalId AND relationKind IN ('REFUND', 'REIMBURSEMENT') AND deletedAt IS NULL")
    suspend fun activeReturnCount(originalId: Long): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE duplicateOfId = :primaryId AND deletedAt IS NULL")
    suspend fun activeDuplicateCount(primaryId: Long): Int

    @Query("SELECT * FROM transactions WHERE relatedTransactionId = :originalId AND relationKind IN ('REFUND', 'REIMBURSEMENT') AND deletedAt IS NULL AND duplicateOfId IS NULL")
    suspend fun returnsFor(originalId: Long): List<TransactionEntity>

    @Query("UPDATE transactions SET category = :category WHERE relatedTransactionId = :originalId AND relationKind IN ('REFUND','REIMBURSEMENT')")
    suspend fun syncReturnCategory(originalId: Long, category: String)

    // 查所有交易，按时间倒序。
    // Flow<List<...>> 表示"响应式":数据库变了，订阅者自动收到新列表。
    // 嵌入式类比：寄存器变化时自动触发中断给上层。
    @Query("SELECT * FROM transactions WHERE deletedAt IS NULL AND (relationKind IS NULL OR relationKind != 'TRANSFER_IN' OR reviewReason IS NOT NULL) ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE deletedAt IS NULL ORDER BY timestamp DESC")
    fun observeBalanceMovements(): Flow<List<TransactionEntity>>

    // 查指定时间范围内的（用于"本月"统计等）
    @Query("SELECT * FROM transactions WHERE deletedAt IS NULL AND duplicateOfId IS NULL AND timestamp >= :startMs AND timestamp < :endMs ORDER BY timestamp DESC")
    fun observeBetween(startMs: Long, endMs: Long): Flow<List<TransactionEntity>>

    // 统计某段时间内的总支出（单位:分）。COALESCE 处理"没数据时返回 0 而不是 null"
    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM transactions WHERE deletedAt IS NULL AND duplicateOfId IS NULL AND type = 'EXPENSE' AND (relationKind IS NULL OR relationKind = 'TRANSFER_FEE') AND timestamp >= :startMs AND timestamp < :endMs")
    fun observeTotalExpenseBetween(startMs: Long, endMs: Long): Flow<Long>

    // 总收入（单位:分）
    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM transactions WHERE deletedAt IS NULL AND duplicateOfId IS NULL AND type = 'INCOME' AND relationKind IS NULL AND timestamp >= :startMs AND timestamp < :endMs")
    fun observeTotalIncomeBetween(startMs: Long, endMs: Long): Flow<Long>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?
    @Query("SELECT * FROM transactions WHERE clientRequestId = :requestId LIMIT 1")
    suspend fun getByRequestId(requestId: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE importKey = :key LIMIT 1")
    suspend fun getByImportKey(key: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE deletedAt IS NULL AND amountCents = :amountCents AND type = :type AND timestamp BETWEEN :startMs AND :endMs")
    suspend fun findNear(amountCents: Long, type: String, startMs: Long, endMs: Long): List<TransactionEntity>

    // 用于自动抓取路径去重：在时间窗口内找同来源 + 同金额的记录。
    // 命中即认为是重复（同一笔交易被通知/无障碍/SMS 多个通道抓到了）。
    @Query("""
        SELECT * FROM transactions
        WHERE source = :source AND amountCents = :amountCents AND type = :type
          AND (account = :account OR (account IS NULL AND :account IS NULL))
          AND (merchant = :merchant OR merchant = '' OR :merchant = '')
          AND timestamp >= :windowStartMs AND timestamp <= :windowEndMs
        LIMIT 1
    """)
    suspend fun findRecentSameAmount(
        source: String,
        amountCents: Long,
        type: String,
        account: String?,
        merchant: String,
        windowStartMs: Long,
        windowEndMs: Long,
    ): TransactionEntity?
}

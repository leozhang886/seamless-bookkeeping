package com.bookkeeping.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bookkeeping.app.data.local.entity.BalanceCheckpointEntity
import com.bookkeeping.app.data.local.entity.ImportBatchEntity
import com.bookkeeping.app.data.local.entity.ImportIdentityEntity
import com.bookkeeping.app.data.local.entity.PendingNotificationEntity
import com.bookkeeping.app.data.local.entity.DuplicateLinkChangeEntity
import com.bookkeeping.app.data.local.entity.BudgetEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerMetaDao {
    @Insert suspend fun insertDuplicateChange(change: DuplicateLinkChangeEntity)
    @Query("SELECT * FROM duplicate_link_changes WHERE secondaryId = :secondaryId ORDER BY transactionId")
    suspend fun duplicateChanges(secondaryId: Long): List<DuplicateLinkChangeEntity>
    @Query("SELECT * FROM duplicate_link_changes ORDER BY secondaryId, transactionId")
    suspend fun allDuplicateChanges(): List<DuplicateLinkChangeEntity>
    @Query("DELETE FROM duplicate_link_changes WHERE secondaryId = :secondaryId")
    suspend fun deleteDuplicateChanges(secondaryId: Long)
    @Query("DELETE FROM duplicate_link_changes") suspend fun clearDuplicateChanges()

    @Query("SELECT * FROM budget_entries") fun observeBudgets(): Flow<List<BudgetEntryEntity>>
    @Query("SELECT * FROM budget_entries") suspend fun allBudgets(): List<BudgetEntryEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putBudget(entry: BudgetEntryEntity)
    @Query("DELETE FROM budget_entries") suspend fun clearBudgets()
    @Query("SELECT * FROM import_batches ORDER BY importedAt DESC")
    fun observeBatches(): Flow<List<ImportBatchEntity>>
    @Query("UPDATE import_batches SET undoneAt = NULL WHERE id = :id")
    suspend fun restoreBatchState(id: Long)
    @Query("SELECT * FROM import_batches WHERE id = :id") suspend fun batchById(id: Long): ImportBatchEntity?
    @Query("SELECT * FROM pending_notifications WHERE status != 'PENDING' ORDER BY rawTimestamp DESC LIMIT 100")
    fun observeProcessedPending(): Flow<List<PendingNotificationEntity>>
    @Query("UPDATE pending_notifications SET status = 'PENDING' WHERE id = :id AND status IN ('DISMISSED', 'DUPLICATE')")
    suspend fun reopenPending(id: Long): Int
    @Insert suspend fun insertBatch(batch: ImportBatchEntity): Long
    @Insert suspend fun restoreBatch(batch: ImportBatchEntity)
    @Query("DELETE FROM import_batches WHERE id = :id")
    suspend fun deleteBatch(id: Long)
    @Query("SELECT * FROM import_batches ORDER BY importedAt DESC")
    suspend fun allBatches(): List<ImportBatchEntity>
    @Query("SELECT * FROM import_batches WHERE undoneAt IS NULL ORDER BY importedAt DESC LIMIT 1")
    suspend fun latestActiveBatch(): ImportBatchEntity?
    @Query("UPDATE import_batches SET undoneAt = :time WHERE id = :id AND undoneAt IS NULL")
    suspend fun markBatchUndone(id: Long, time: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun reserveIdentity(identity: ImportIdentityEntity): Long
    @Insert suspend fun restoreIdentity(identity: ImportIdentityEntity)
    @Query("UPDATE import_identities SET transactionId = :transactionId WHERE importKey = :key")
    suspend fun setIdentityTransaction(key: String, transactionId: Long)
    @Query("SELECT * FROM import_identities WHERE importKey = :key LIMIT 1")
    suspend fun identity(key: String): ImportIdentityEntity?
    @Query("SELECT * FROM import_identities")
    suspend fun allIdentities(): List<ImportIdentityEntity>

    @Insert suspend fun insertCheckpoint(checkpoint: BalanceCheckpointEntity): Long
    @Insert suspend fun restoreCheckpoint(checkpoint: BalanceCheckpointEntity)
    @Query("SELECT * FROM balance_checkpoints WHERE accountId = :accountId ORDER BY occurredAt DESC")
    suspend fun checkpointsFor(accountId: Long): List<BalanceCheckpointEntity>
    @Query("SELECT * FROM balance_checkpoints")
    suspend fun allCheckpoints(): List<BalanceCheckpointEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPending(pending: PendingNotificationEntity): Long
    @Insert suspend fun restorePending(pending: PendingNotificationEntity)
    @Query("SELECT * FROM pending_notifications WHERE status = 'PENDING' ORDER BY rawTimestamp DESC")
    fun observePending(): Flow<List<PendingNotificationEntity>>
    @Query("SELECT * FROM pending_notifications WHERE id = :id LIMIT 1")
    suspend fun pendingById(id: Long): PendingNotificationEntity?
    @Query("UPDATE pending_notifications SET status = :status WHERE id = :id AND status = 'PENDING'")
    suspend fun setPendingStatus(id: Long, status: String): Int
    @Query("UPDATE pending_notifications SET status = 'CONFIRMED', transactionId = :transactionId WHERE id = :id AND status = 'PENDING'")
    suspend fun completePending(id: Long, transactionId: Long): Int
    @Query("SELECT * FROM pending_notifications")
    suspend fun allPending(): List<PendingNotificationEntity>

    @Query("DELETE FROM import_batches") suspend fun clearBatches()
    @Query("DELETE FROM import_identities") suspend fun clearIdentities()
    @Query("DELETE FROM balance_checkpoints") suspend fun clearCheckpoints()
    @Query("DELETE FROM pending_notifications") suspend fun clearPending()
}

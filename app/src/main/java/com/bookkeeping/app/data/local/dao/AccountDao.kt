package com.bookkeeping.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.bookkeeping.app.data.local.entity.AccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts WHERE archivedAt IS NULL")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts")
    suspend fun getAllRaw(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): AccountEntity?

    @Query("SELECT * FROM accounts WHERE archivedAt IS NULL ORDER BY kind, name")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE archivedAt IS NOT NULL ORDER BY archivedAt DESC")
    fun observeArchived(): Flow<List<AccountEntity>>
    @Query("UPDATE accounts SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Query("UPDATE accounts SET balanceCents = :balanceCents, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateBalance(id: Long, balanceCents: Long, updatedAt: Long)

    @Query("UPDATE accounts SET archivedAt = :time WHERE id = :id AND archivedAt IS NULL")
    suspend fun archive(id: Long, time: Long): Int

    @Query("UPDATE accounts SET archivedAt = NULL WHERE id = :id")
    suspend fun restore(id: Long)

    @Query("DELETE FROM accounts")
    suspend fun clearAll()
}

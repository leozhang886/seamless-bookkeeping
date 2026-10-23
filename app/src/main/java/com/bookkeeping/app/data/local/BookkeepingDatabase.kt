package com.bookkeeping.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bookkeeping.app.data.local.dao.AccountDao
import com.bookkeeping.app.data.local.dao.TransactionDao
import com.bookkeeping.app.data.local.dao.LedgerMetaDao
import com.bookkeeping.app.data.local.entity.AccountEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.ImportBatchEntity
import com.bookkeeping.app.data.local.entity.ImportIdentityEntity
import com.bookkeeping.app.data.local.entity.BalanceCheckpointEntity
import com.bookkeeping.app.data.local.entity.PendingNotificationEntity
import com.bookkeeping.app.data.local.entity.BudgetEntryEntity

// Room 数据库本体。
// entities = 这个库里有哪些"表"
// version = 数据库版本号；每次改 Entity 结构需要 +1 并写 Migration
@Database(
    entities = [TransactionEntity::class, AccountEntity::class, ImportBatchEntity::class,
        ImportIdentityEntity::class, BalanceCheckpointEntity::class, PendingNotificationEntity::class,
        BudgetEntryEntity::class],
    version = 6,
    exportSchema = true
)
abstract class BookkeepingDatabase : RoomDatabase() {
    val access = LedgerAccess()
    suspend fun <T> writeLedger(block: suspend () -> T): T = access.write { withTransaction(block) }
    suspend fun <T> replaceLedger(block: suspend () -> T): T = access.replace { withTransaction(block) }
    // 暴露 DAO 给外部使用。Room 会自动生成实现。
    abstract fun transactionDao(): TransactionDao
    abstract fun accountDao(): AccountDao
    abstract fun ledgerMetaDao(): LedgerMetaDao

    companion object {
        const val DB_NAME = "bookkeeping.db"
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("createdAt INTEGER", "modifiedAt INTEGER", "sourcePaymentMethod TEXT",
                    "sourceAccountKind TEXT", "sourceBalanceCents INTEGER", "sourceIdentity TEXT",
                    "rawData TEXT", "reviewReason TEXT", "sourcePage INTEGER", "clientRequestId TEXT").forEach {
                    db.execSQL("ALTER TABLE transactions ADD COLUMN $it")
                }
                db.execSQL("ALTER TABLE transactions ADD COLUMN timePrecision TEXT NOT NULL DEFAULT 'INSTANT'")
                db.execSQL("UPDATE transactions SET timePrecision = 'DAY' WHERE source = 'BANK_PDF'")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_sourceIdentity ON transactions(sourceIdentity)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_amountCents_timestamp ON transactions(amountCents,timestamp)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_transactions_clientRequestId ON transactions(clientRequestId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS budget_entries (category TEXT NOT NULL PRIMARY KEY, amountCents INTEGER NOT NULL)")
                db.execSQL("ALTER TABLE pending_notifications ADD COLUMN parserVersion INTEGER NOT NULL DEFAULT 1")
                listOf("sourceAccount TEXT", "sourceAccountKind TEXT", "bankFlow TEXT", "relationKind TEXT",
                    "receivedAt INTEGER", "eventKey TEXT", "transactionId INTEGER").forEach {
                    db.execSQL("ALTER TABLE pending_notifications ADD COLUMN $it")
                }
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN importKey TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS accounts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, balanceCents INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN bankFlow TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN balanceAccountId INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN balanceTrackingVersion INTEGER")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN balanceIgnored INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN importBatchId INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN duplicateOfId INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN relatedTransactionId INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN relationKind TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN parserVersion INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE transactions ADD COLUMN sourceRow INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN sourceOrderId TEXT")
                db.execSQL("ALTER TABLE accounts ADD COLUMN archivedAt INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS import_batches (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, fileName TEXT NOT NULL, source TEXT NOT NULL, importedAt INTEGER NOT NULL, originalRows INTEGER NOT NULL, parsedRows INTEGER NOT NULL, rejectedRows INTEGER NOT NULL, amountCents INTEGER NOT NULL, fingerprint TEXT NOT NULL, undoneAt INTEGER)")
                db.execSQL("CREATE TABLE IF NOT EXISTS import_identities (importKey TEXT NOT NULL PRIMARY KEY, transactionId INTEGER NOT NULL)")
                db.execSQL("INSERT OR IGNORE INTO import_identities(importKey, transactionId) SELECT importKey, id FROM transactions WHERE importKey IS NOT NULL ORDER BY id")
                db.execSQL("CREATE TABLE IF NOT EXISTS balance_checkpoints (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, accountId INTEGER NOT NULL, balanceCents INTEGER NOT NULL, occurredAt INTEGER NOT NULL, reason TEXT NOT NULL)")
                db.execSQL("INSERT INTO balance_checkpoints(accountId, balanceCents, occurredAt, reason) SELECT id, balanceCents, updatedAt, '历史余额起点' FROM accounts")
                db.execSQL("CREATE TABLE IF NOT EXISTS pending_notifications (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, fingerprint TEXT NOT NULL, sourcePackage TEXT NOT NULL, rawText TEXT NOT NULL, rawTimestamp INTEGER NOT NULL, amountCents INTEGER NOT NULL, type TEXT NOT NULL, category TEXT NOT NULL, merchant TEXT NOT NULL, source TEXT NOT NULL, confidence INTEGER NOT NULL, status TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_timestamp ON transactions(timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_importKey ON transactions(importKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_balanceAccountId ON transactions(balanceAccountId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_importBatchId ON transactions(importBatchId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_duplicateOfId ON transactions(duplicateOfId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_relatedTransactionId ON transactions(relatedTransactionId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pending_notifications_fingerprint ON pending_notifications(fingerprint)")
            }
        }
    }
}

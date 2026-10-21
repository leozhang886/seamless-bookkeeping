package com.bookkeeping.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bookkeeping.app.data.local.dao.AccountDao
import com.bookkeeping.app.data.local.dao.TransactionDao
import com.bookkeeping.app.data.local.entity.AccountEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity

// Room 数据库本体。
// entities = 这个库里有哪些"表"
// version = 数据库版本号；每次改 Entity 结构需要 +1 并写 Migration
@Database(
    entities = [TransactionEntity::class, AccountEntity::class],
    version = 4,
    exportSchema = false  // 不导出 schema 文件；正式上线建议开
)
abstract class BookkeepingDatabase : RoomDatabase() {
    // 暴露 DAO 给外部使用。Room 会自动生成实现。
    abstract fun transactionDao(): TransactionDao
    abstract fun accountDao(): AccountDao

    companion object {
        const val DB_NAME = "bookkeeping.db"
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
    }
}

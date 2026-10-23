package com.bookkeeping.app

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookkeeping.app.data.local.BookkeepingDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerMigrationTest {
    @Test fun version4UpgradePreservesAccountAndTransaction() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration_4_to_5_test.db"
        context.deleteDatabase(name)
        val legacy = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        legacy.execSQL("CREATE TABLE IF NOT EXISTS transactions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, amountCents INTEGER NOT NULL, type TEXT NOT NULL, category TEXT NOT NULL, merchant TEXT NOT NULL, note TEXT NOT NULL, source TEXT NOT NULL, account TEXT, timestamp INTEGER NOT NULL, importKey TEXT, bankFlow TEXT, balanceAccountId INTEGER, balanceTrackingVersion INTEGER, balanceIgnored INTEGER NOT NULL DEFAULT 0)")
        legacy.execSQL("CREATE TABLE IF NOT EXISTS accounts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, balanceCents INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
        legacy.execSQL("INSERT INTO accounts(id,name,kind,balanceCents,updatedAt) VALUES(1,'测试银行卡','BANK',10000,10)")
        legacy.execSQL("INSERT INTO transactions(id,amountCents,type,category,merchant,note,source,account,timestamp,importKey,balanceTrackingVersion,balanceIgnored) VALUES(1,1234,'EXPENSE','FOOD','测试商户','','ALIPAY','支付宝',20,'old-key',1,0)")
        legacy.version = 4
        legacy.close()
        val db = Room.databaseBuilder(context, BookkeepingDatabase::class.java, name)
            .addMigrations(BookkeepingDatabase.MIGRATION_4_5, BookkeepingDatabase.MIGRATION_5_6).build()
        try {
            val row = db.transactionDao().getById(1)!!
            assertEquals(1234L, row.amountCents)
            assertEquals("old-key", row.importKey)
            assertNull(row.deletedAt)
            assertEquals(10000L, db.accountDao().getById(1)!!.balanceCents)
            assertEquals(1, db.ledgerMetaDao().checkpointsFor(1).size)
            assertEquals(1L, db.ledgerMetaDao().identity("old-key")!!.transactionId)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}

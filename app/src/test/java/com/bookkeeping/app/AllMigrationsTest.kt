package com.bookkeeping.app

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.bookkeeping.app.data.local.BookkeepingDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class AllMigrationsTest {
    @Test fun everyReleasedSchemaUpgradesWithoutLosingAmountsOrIdentities() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val migrations = arrayOf(BookkeepingDatabase.MIGRATION_1_2, BookkeepingDatabase.MIGRATION_2_3,
            BookkeepingDatabase.MIGRATION_3_4, BookkeepingDatabase.MIGRATION_4_5, BookkeepingDatabase.MIGRATION_5_6, BookkeepingDatabase.MIGRATION_6_7)
        for (version in 1..6) {
            val name = "migration-from-$version.db"
            context.deleteDatabase(name)
            val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name).callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE transactions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, amountCents INTEGER NOT NULL, type TEXT NOT NULL, category TEXT NOT NULL, merchant TEXT NOT NULL, note TEXT NOT NULL, source TEXT NOT NULL, account TEXT, timestamp INTEGER NOT NULL)")
                        db.execSQL("INSERT INTO transactions(id,amountCents,type,category,merchant,note,source,timestamp) VALUES(42,1234,'EXPENSE','FOOD','测试','','BANK_PDF',1000)")
                        migrations.filter { it.endVersion <= version }.forEach { it.migrate(db) }
                        if (version >= 2) {
                            db.execSQL("INSERT INTO accounts(id,name,kind,balanceCents,updatedAt) VALUES(7,'测试卡4562','BANK',56789,100)")
                            db.execSQL("UPDATE transactions SET importKey = 'old-order' WHERE id = 42")
                        }
                        if (version >= 5) db.execSQL("INSERT INTO import_identities(importKey,transactionId) VALUES('old-order',42)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected fixture upgrade")
                }).build())
            helper.writableDatabase.close(); helper.close()
            val db = Room.databaseBuilder(context, BookkeepingDatabase::class.java, name).addMigrations(*migrations).build()
            try {
                val row = db.transactionDao().getById(42)!!
                assertEquals(1234L, row.amountCents)
                assertEquals("DAY", row.timePrecision)
                assertNull(row.deletedAt)
                assertNull(row.clientRequestId)
                if (version >= 2) {
                    assertEquals(56789L, db.accountDao().getById(7)!!.balanceCents)
                    assertEquals(42L, db.ledgerMetaDao().identity("old-order")!!.transactionId)
                }
                assertTrue(db.ledgerMetaDao().allBudgets().isEmpty())
                assertTrue(db.ledgerMetaDao().allDuplicateChanges().isEmpty())
            } finally { db.close(); context.deleteDatabase(name) }
        }
    }
}

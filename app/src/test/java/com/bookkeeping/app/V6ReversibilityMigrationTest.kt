package com.bookkeeping.app

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.bookkeeping.app.data.local.BookkeepingDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class V6ReversibilityMigrationTest {
    @Test fun committedV6SchemaAndEveryExistingTableRemainUnchangedAfterMigration() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "v6-preservation-fixture.db"
        context.deleteDatabase(name)
        val schema = javaClass.classLoader!!.getResourceAsStream("com.bookkeeping.app.data.local.BookkeepingDatabase/6.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val tables = schema.getJSONArray("entities")
        fun snapshot(database: SupportSQLiteDatabase): Map<String, List<List<String?>>> = buildMap {
            for (i in 0 until tables.length()) {
                val table = tables.getJSONObject(i).getString("tableName")
                val rows = mutableListOf<List<String?>>()
                database.query("SELECT * FROM `$table` ORDER BY 1").use { cursor ->
                    while (cursor.moveToNext()) rows += (0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }
                }
                put(table, rows)
            }
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(6) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    for (i in 0 until tables.length()) {
                        val table = tables.getJSONObject(i)
                        db.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", table.getString("tableName")))
                        val indices = table.getJSONArray("indices")
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table.getString("tableName")))
                    }
                    db.execSQL("INSERT INTO accounts VALUES(1,'Fixture bank 1234','BANK',100000,1,NULL)")
                    db.execSQL("INSERT INTO import_batches VALUES(1,'fixture.pdf','BANK_PDF',1000,1,1,0,10000,'fixture',NULL)")
                    db.execSQL("INSERT INTO transactions(id,amountCents,type,category,merchant,note,source,timestamp,importKey,balanceTrackingVersion,balanceIgnored,importBatchId,parserVersion,timePrecision,balanceAccountId,relatedTransactionId,relationKind,duplicateOfId,deletedAt,clientRequestId) VALUES(1,10000,'EXPENSE','FOOD','fixture','','BANK_PDF',1000,'fixture-1',1,0,1,1,'DAY',1,NULL,NULL,2,NULL,NULL),(2,10000,'EXPENSE','SHOPPING','fixture','','MANUAL',2000,NULL,1,0,NULL,1,'INSTANT',1,NULL,NULL,NULL,NULL,'fixture-form'),(3,3000,'INCOME','SHOPPING','fixture','','MANUAL',3000,NULL,1,0,NULL,1,'INSTANT',1,2,'REFUND',NULL,NULL,NULL),(4,500,'EXPENSE','OTHER','deleted fixture','','BANK_PDF',4000,'fixture-deleted',1,0,1,1,'DAY',1,NULL,NULL,NULL,5000,NULL),(5,1000,'TRANSFER','TRANSFER','fixture','','MANUAL',6000,NULL,1,0,NULL,1,'INSTANT',1,6,'TRANSFER_OUT',NULL,NULL,NULL),(6,1000,'TRANSFER','TRANSFER','fixture','','MANUAL',6000,NULL,1,0,NULL,1,'INSTANT',1,5,'TRANSFER_IN',NULL,NULL,NULL)")
                    db.execSQL("INSERT INTO import_identities VALUES('fixture-1',1),('fixture-deleted',4)")
                    db.execSQL("INSERT INTO balance_checkpoints VALUES(1,1,100000,1,'fixture baseline')")
                    db.execSQL("INSERT INTO budget_entries VALUES('TOTAL',50000),('FOOD',10000)")
                    db.execSQL("INSERT INTO pending_notifications(id,fingerprint,sourcePackage,rawText,rawTimestamp,amountCents,type,category,merchant,source,confidence,status,parserVersion) VALUES(1,'fixture-notification','fixture','fixture',7000,1000,'EXPENSE','FOOD','fixture','ALIPAY',70,'PENDING',2)")
                    db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                    db.execSQL("INSERT INTO room_master_table VALUES(42,'${schema.getString("identityHash")}')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected fixture upgrade")
            }).build())
        val before = snapshot(helper.writableDatabase)
        helper.close()
        val db = Room.databaseBuilder(context, BookkeepingDatabase::class.java, name)
            .addMigrations(BookkeepingDatabase.MIGRATION_6_7).build()
        try {
            assertEquals(before, snapshot(db.openHelper.writableDatabase))
            assertTrue(db.ledgerMetaDao().allDuplicateChanges().isEmpty())
            assertEquals(7, db.openHelper.readableDatabase.version)
            assertEquals(5000L, db.transactionDao().getById(4)!!.deletedAt!!)
            assertEquals(2L, db.transactionDao().getById(3)!!.relatedTransactionId!!)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}

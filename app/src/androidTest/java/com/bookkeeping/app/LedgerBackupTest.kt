package com.bookkeeping.app

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookkeeping.app.data.backup.LedgerBackup
import com.bookkeeping.app.data.budget.BudgetPreferences
import com.bookkeeping.app.data.local.BookkeepingDatabase
import com.bookkeeping.app.data.local.entity.AccountEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerBackupTest {
    @Test fun encryptedRoundTripAndWrongPasswordDoNotLoseLedger() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, BookkeepingDatabase::class.java).build()
        val file = java.io.File.createTempFile("ledger-test-", ".sbk", context.cacheDir)
        try {
            val accountId = db.accountDao().insert(AccountEntity(name = "测试账户", kind = "BANK",
                balanceCents = 10_000, updatedAt = 1))
            val txId = db.transactionDao().insert(TransactionEntity(amountCents = 1234,
                type = "EXPENSE", category = "FOOD", merchant = "测试商户", source = "MANUAL",
                timestamp = 2, balanceAccountId = accountId))
            val backup = LedgerBackup(context, db, db.accountDao(), db.transactionDao(),
                db.ledgerMetaDao(), BudgetPreferences(context, db))
            val uri = Uri.fromFile(file)
            val result = backup.export(uri, "test-password-12345")
            assertEquals(1, result.accounts)
            assertEquals(1, result.transactions)
            db.transactionDao().clearAll()
            assertTrue(runCatching { backup.restore(uri, "wrong-password-12345") }.isFailure)
            assertEquals(null, db.transactionDao().getById(txId))
            backup.restore(uri, "test-password-12345")
            assertNotNull(db.transactionDao().getById(txId))
            assertEquals(10_000L, db.accountDao().getById(accountId)!!.balanceCents)
        } finally {
            db.close()
            file.delete()
        }
    }
}

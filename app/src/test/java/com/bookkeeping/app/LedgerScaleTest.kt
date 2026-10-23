package com.bookkeeping.app

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.bookkeeping.app.data.local.BookkeepingDatabase
import com.bookkeeping.app.data.local.entity.*
import com.bookkeeping.app.data.repository.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Synthetic desktop smoke measurements, not Android device performance claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class LedgerScaleTest {
    @Test fun periodSearchAndBalancesRemainCorrectAtTenFiftyAndHundredThousandRows() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), BookkeepingDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.transactionDao()
            val repository = TransactionRepository(dao, db.accountDao(), db.ledgerMetaDao(), db)
            val account = AccountEntity(1, "Synthetic cash", "CASH", 100_000_000, 0)
            db.accountDao().insert(account)
            var previous = 0
            for (count in listOf(10_000, 50_000, 100_000)) {
                db.withTransaction {
                    for (i in previous + 1..count) dao.insert(TransactionEntity(
                        amountCents = 100, type = "EXPENSE", category = "FOOD", source = "MANUAL",
                        timestamp = i.toLong(), balanceAccountId = 1, merchant = "Synthetic merchant ${i % 30}"))
                }
                val started = System.nanoTime()
                val period = repository.observeBetween(count - 999L, count + 1L).first()
                assertEquals(1000, period.size)
                assertEquals(100_000L, period.sumOf { it.amountCents })
                val candidates = dao.findAmountNear(100, count - 9L, count.toLong())
                assertEquals(10, candidates.size)
                val all = dao.getAllRaw()
                assertEquals(count, all.size)
                assertEquals(100_000_000L - count * 100L, effectiveBalanceAccounts(listOf(account), all).single().balanceCents)
                println("SYNTHETIC_SCALE rows=$count query_and_balance_ms=${(System.nanoTime() - started) / 1_000_000}")
                if (count == 100_000) {
                    val context = RuntimeEnvironment.getApplication()
                    val backup = com.bookkeeping.app.data.backup.LedgerBackup(context, db, db.accountDao(), dao,
                        db.ledgerMetaDao(), com.bookkeeping.app.data.budget.BudgetPreferences(context, db))
                    val file = java.io.File.createTempFile("synthetic-scale", ".sbk", context.cacheDir)
                    val backupStarted = System.nanoTime()
                    try {
                        assertEquals(count, backup.export(android.net.Uri.fromFile(file), "synthetic-scale-password").transactions)
                        println("SYNTHETIC_BACKUP rows=$count export_and_readback_ms=${(System.nanoTime() - backupStarted) / 1_000_000}")
                    } finally { file.delete() }
                }
                previous = count
            }
        } finally { db.close() }
    }
}

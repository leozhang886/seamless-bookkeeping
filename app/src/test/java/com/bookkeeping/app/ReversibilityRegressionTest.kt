package com.bookkeeping.app

import android.app.Application
import androidx.room.Room
import com.bookkeeping.app.data.local.BookkeepingDatabase
import com.bookkeeping.app.data.local.entity.*
import com.bookkeeping.app.data.repository.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ReversibilityRegressionTest {
    private lateinit var db: BookkeepingDatabase
    private lateinit var repository: TransactionRepository
    private val januaryEnd = at("2026-01-31T23:50:00")
    private val februaryStart = at("2026-02-01T00:10:00")
    private fun at(value: String) = LocalDateTime.parse(value).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
    @Before fun open() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), BookkeepingDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = TransactionRepository(db.transactionDao(), db.accountDao(), db.ledgerMetaDao(), db)
        db.accountDao().insert(AccountEntity(1, "Fixture bank 1234", "BANK", 100_000, 1))
        Unit
    }
    @After fun close() { db.close() }
    private fun batch(name: String) = ImportBatchEntity(fileName = name, source = "BANK_PDF", importedAt = 1,
        originalRows = 1, parsedRows = 1, rejectedRows = 0, amountCents = 10_000, fingerprint = name)
    private suspend fun balance() = effectiveBalanceAccounts(db.accountDao().getAll(), db.transactionDao().getAllRaw()).single().balanceCents
    private suspend fun netExpense(from: Long, to: Long) = repository.observeBetween(from, to).first()
        .filter { it.type == "EXPENSE" }.sumOf { it.amountCents }

    @Test fun deletedImportedRowStaysDeletedWhenSameStatementIsImportedWithBatch() = runBlocking {
        val bill = TransactionEntity(amountCents = 10_000, type = "EXPENSE", category = "FOOD",
            merchant = "Fixture shop", source = "BANK_PDF", account = "Fixture bank (1234)",
            timestamp = januaryEnd, bankFlow = "OUT", importKey = "fixture-order-1")
        val originalBatch = repository.importBills(listOf(bill), batch("first.pdf")).batchId!!
        val row = db.transactionDao().getByImportKey(bill.importKey!!)!!
        assertEquals(90_000L, balance())
        assertEquals(1, repository.delete(row))
        val deleted = db.transactionDao().getById(row.id)!!
        assertNotNull(deleted.deletedAt)
        assertEquals(100_000L, balance())
        val result = repository.importBills(listOf(bill), batch("same-file-again.pdf"))
        val after = db.transactionDao().getById(row.id)!!
        val net = netExpense(at("2026-01-01T00:00:00"), at("2026-02-01T00:00:00"))
        val currentBalance = balance()
        val batches = db.ledgerMetaDao().allBatches()
        println("REIMPORT_OBSERVED deleted=${after.deletedAt != null} balance=$currentBalance expense=$net added=${result.added} duplicate=${result.duplicates} batchCount=${batches.size} ownerBatch=${after.importBatchId}")
        assertEquals("An import must not restore a user-deleted row", deleted, after)
        assertEquals(0, result.added)
        assertEquals(1, result.duplicates)
        assertNull(result.batchId)
        assertEquals(listOf(originalBatch), batches.map { it.id })
        assertEquals(100_000L, currentBalance)
        assertEquals(0L, net)
        assertEquals(row.id, db.ledgerMetaDao().identity(bill.importKey)!!.transactionId)
    }

    @Test fun unlinkDuplicateRestoresRefundOwnerAndOriginalMonthTotals() = runBlocking {
        val original = repository.insert(TransactionEntity(amountCents = 10_000, type = "EXPENSE", category = "FOOD",
            merchant = "Fixture shop A", source = "BANK_PDF", timestamp = januaryEnd, bankFlow = "OUT", balanceAccountId = 1))
        val other = repository.insert(TransactionEntity(amountCents = 10_000, type = "EXPENSE", category = "SHOPPING",
            merchant = "Fixture shop B", source = "MANUAL", timestamp = februaryStart, balanceAccountId = 1))
        assertTrue(repository.confirmIndependent(other))
        assertTrue(repository.recordReturn(original, "REFUND", 3_000, 1, "Fixture partial refund", februaryStart + 1000))
        val refund = db.transactionDao().getAllRaw().single { it.relationKind == "REFUND" }
        val januaryStart = at("2026-01-01T00:00:00")
        val februaryBoundary = at("2026-02-01T00:00:00")
        val marchBoundary = at("2026-03-01T00:00:00")
        assertEquals(7_000L, netExpense(januaryStart, februaryBoundary))
        assertEquals(10_000L, netExpense(februaryBoundary, marchBoundary))
        assertEquals(83_000L, balance())
        assertTrue(repository.linkDuplicate(original, other))
        assertEquals(other, db.transactionDao().getById(original)!!.duplicateOfId!!)
        assertEquals(other, db.transactionDao().getById(refund.id)!!.relatedTransactionId!!)
        assertEquals(93_000L, balance())
        assertTrue(repository.unlinkDuplicate(original))
        assertNotNull(db.transactionDao().getById(original)!!.reviewReason)
        assertEquals(original, db.transactionDao().getById(refund.id)!!.relatedTransactionId!!)
        assertEquals("FOOD", db.transactionDao().getById(refund.id)!!.category)
        assertTrue(repository.confirmIndependent(original))
        val after = db.transactionDao().getById(refund.id)!!
        val januaryNet = netExpense(januaryStart, februaryBoundary)
        val februaryNet = netExpense(februaryBoundary, marchBoundary)
        val afterBalance = balance()
        println("UNLINK_OBSERVED refundOwner=${after.relatedTransactionId} expectedOwner=$original category=${after.category} januaryExpense=$januaryNet februaryExpense=$februaryNet balance=$afterBalance")
        assertEquals("Refund ownership must survive link/unlink", original, after.relatedTransactionId!!)
        assertEquals("FOOD", after.category)
        assertEquals(7_000L, januaryNet)
        assertEquals(10_000L, februaryNet)
        assertEquals(83_000L, afterBalance)
        assertEquals(0L, repository.observeTotalIncomeBetween(januaryStart, marchBoundary).first())
    }
}

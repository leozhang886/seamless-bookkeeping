package com.bookkeeping.app

import android.app.Application
import android.net.Uri
import androidx.room.Room
import com.bookkeeping.app.data.local.BookkeepingDatabase
import com.bookkeeping.app.data.local.entity.*
import com.bookkeeping.app.data.repository.*
import com.bookkeeping.app.data.budget.BudgetPreferences
import com.bookkeeping.app.data.backup.LedgerBackup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class LedgerRepositoryTest {
    private lateinit var db: BookkeepingDatabase
    private lateinit var repository: TransactionRepository
    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), BookkeepingDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = TransactionRepository(db.transactionDao(), db.accountDao(), db.ledgerMetaDao(), db)
    }
    @After fun close() { db.close() }
    private suspend fun accounts() {
        db.accountDao().insert(AccountEntity(1, "建设银行尾号1234", "BANK", 10000, 1))
        db.accountDao().insert(AccountEntity(2, "微信零钱", "WECHAT", 0, 1))
    }
    private fun bankTransfer(key: String = "bank-1") = TransactionEntity(amountCents = 1000,
        type = "TRANSFER", category = "TRANSFER", source = "BANK_PDF", bankFlow = "OUT",
        timestamp = 2100, account = "建设银行（尾号1234）", importKey = key)
    private suspend fun balances() = effectiveBalanceAccounts(db.accountDao().getAll(), db.transactionDao().getAllRaw())
        .associate { it.id to it.balanceCents }

    @Test fun manualTransferThenImportNeverDoubleDebitsAndBatchUndoKeepsPair() = runBlocking {
        accounts()
        assertTrue(repository.createTransfer(1, 2, 1000, 0, "", 2000))
        val batch = ImportBatchEntity(fileName = "test.pdf", source = "BANK_PDF", importedAt = 3000,
            originalRows = 1, parsedRows = 1, rejectedRows = 0, amountCents = 1000, fingerprint = "fixture")
        val result = repository.importBills(listOf(bankTransfer()), batch)
        assertEquals(mapOf(1L to 9000L, 2L to 1000L), balances())
        val imported = db.transactionDao().getByImportKey("bank-1")!!
        assertNotNull(imported.reviewReason)
        val manual = repository.suggestDuplicates(imported.id).single().other
        assertTrue(repository.linkDuplicate(imported.id, manual.id))
        assertEquals(mapOf(1L to 9000L, 2L to 1000L), balances())
        assertEquals(1, repository.undoImport(result.batchId!!))
        assertEquals(mapOf(1L to 9000L, 2L to 1000L), balances())
        assertEquals(1, repository.restoreImport(result.batchId))
        assertEquals(mapOf(1L to 9000L, 2L to 1000L), balances())
    }

    @Test fun importThenManualTransferLinksWithoutLosingIncomingLeg() = runBlocking {
        accounts()
        repository.importBills(listOf(bankTransfer()))
        repository.createTransfer(1, 2, 1000, 0, "", 2200)
        assertEquals(mapOf(1L to 9000L, 2L to 1000L), balances())
        val imported = db.transactionDao().getByImportKey("bank-1")!!
        val manual = repository.suggestDuplicates(imported.id).single().other
        assertTrue(repository.linkDuplicate(imported.id, manual.id))
        assertEquals(mapOf(1L to 9000L, 2L to 1000L), balances())
    }

    @Test fun distinctBankBalancesPreserveTwoRealSameAmountTransactions() = runBlocking {
        accounts()
        val first = bankTransfer().copy(type = "EXPENSE", category = "FOOD", sourceBalanceCents = 9000, sourceIdentity = "first")
        val second = first.copy(importKey = "bank-2", sourceBalanceCents = 8000, sourceIdentity = "second")
        assertEquals(2, repository.importBills(listOf(first, second)).added)
        assertEquals(8000L, balances()[1])
        assertTrue(db.transactionDao().getAllRaw().all { it.reviewReason == null })
    }

    @Test fun duplicateRefundEvidenceDoesNotConsumeRemainingRefundTwice() = runBlocking {
        val expenseId = repository.insert(TransactionEntity(amountCents = 10000, type = "EXPENSE", category = "FOOD",
            source = "MANUAL", timestamp = 1))
        assertTrue(repository.recordReturn(expenseId, "REFUND", 6000, null, "", 2000))
        val refund = db.transactionDao().getAllRaw().first { it.relationKind == "REFUND" }
        val bankId = db.transactionDao().insert(refund.copy(id = 0, source = "BANK_PDF", importKey = "refund-bank"))
        assertTrue(repository.linkDuplicate(refund.id, bankId))
        assertEquals(6000L, db.transactionDao().returnedAmount(expenseId))
        assertTrue(repository.recordReturn(expenseId, "REFUND", 4000, null, "", 3000))
        assertEquals(0L, accountingPeriod(db.transactionDao().getAllRaw(), 0, 4000).single().amountCents)
        assertFalse(repository.recordReturn(expenseId, "REFUND", 1, null, "", 4000))
    }

    @Test fun categoryEditKeepsAllRefundRelationsInSync() = runBlocking {
        val id = repository.insert(TransactionEntity(amountCents = 10000, type = "EXPENSE", category = "FOOD", source = "MANUAL", timestamp = 1))
        repository.recordReturn(id, "REFUND", 3000, null, "", 2000)
        assertTrue(repository.edit(id, 10000, TransactionType.EXPENSE, TransactionCategory.SHOPPING, "", "", null))
        assertTrue(db.transactionDao().getAllRaw().all { it.category == "SHOPPING" })
        assertEquals(7000L, accountingPeriod(db.transactionDao().getAllRaw(), 0, 4000).single().amountCents)
    }

    @Test fun restoreCannotMakeRefundsExceedOriginalAfterReplacementRefund() = runBlocking {
        val id = repository.insert(TransactionEntity(amountCents = 10000, type = "EXPENSE", category = "FOOD", source = "MANUAL", timestamp = 1))
        repository.recordReturn(id, "REFUND", 8000, null, "", 2000)
        val old = db.transactionDao().getAllRaw().first { it.relationKind == "REFUND" }
        repository.delete(old)
        repository.recordReturn(id, "REFUND", 6000, null, "", 3000)
        assertFalse(repository.restore(old.id))
        assertEquals(6000L, db.transactionDao().returnedAmount(id))
    }

    @Test fun historicalManualDateIsNeverShiftedPastBalanceCheckpoint() = runBlocking {
        accounts()
        val id = repository.insert(TransactionEntity(amountCents = 500, type = "EXPENSE", category = "FOOD",
            source = "MANUAL", timestamp = 0, balanceAccountId = 1))
        assertEquals(0L, db.transactionDao().getById(id)!!.timestamp)
        assertEquals(10000L, balances()[1])
    }

    @Test fun encryptedRestoreIncludesBudgetAndWrongPasswordPreservesNonemptyLedger() = runBlocking {
        accounts()
        val context = RuntimeEnvironment.getApplication()
        val prefs = BudgetPreferences(context, db)
        prefs.setMonthlyTotal(50000)
        val backup = LedgerBackup(context, db, db.accountDao(), db.transactionDao(), db.ledgerMetaDao(), prefs)
        val file = java.io.File.createTempFile("fixture-backup", ".sbk", context.cacheDir)
        try {
            backup.export(Uri.fromFile(file), "audit-fixture-password")
            prefs.setMonthlyTotal(90000)
            assertTrue(runCatching { backup.restore(Uri.fromFile(file), "incorrect-fixture-password") }.isFailure)
            assertEquals(2, db.accountDao().getAll().size)
            assertEquals(90000L, prefs.observe().first().monthlyTotalCents)
            backup.restore(Uri.fromFile(file), "audit-fixture-password")
            assertEquals(50000L, prefs.observe().first().monthlyTotalCents)
            assertEquals(2, db.accountDao().getAll().size)
            assertTrue(backup.recoveryFiles().isNotEmpty())
        } finally { file.delete() }
    }
    @Test fun repeatedFormSubmissionIsIdempotentEvenUnderConcurrentCalls() = runBlocking {
        val row = TransactionEntity(amountCents = 1200, type = "EXPENSE", category = "FOOD", source = "MANUAL",
            timestamp = 1000, clientRequestId = "one-form")
        kotlinx.coroutines.coroutineScope {
            (1..16).map { async(kotlinx.coroutines.Dispatchers.IO) { repository.insert(row) } }
                .forEach { assertTrue(it.await() > 0) }
        }
        assertEquals(1, db.transactionDao().getAllRaw().size)
        assertTrue(runCatching { repository.insert(row.copy(amountCents = 1300)) }.isFailure)
    }
    @Test fun importedPaymentFindsPreviouslyConfirmedNotificationWithSamePlatform() = runBlocking {
        val id = repository.insert(TransactionEntity(amountCents = 1200, type = "EXPENSE", category = "FOOD", source = "ALIPAY", timestamp = 1000))
        repository.importBills(listOf(TransactionEntity(amountCents = 1200, type = "EXPENSE", category = "FOOD", source = "ALIPAY", timestamp = 1000, importKey = "imported-payment")))
        val imported = db.transactionDao().getByImportKey("imported-payment")!!
        assertNotNull(imported.reviewReason)
        assertTrue(repository.linkDuplicate(id, imported.id))
        assertEquals(1200L, accountingPeriod(db.transactionDao().getAllRaw(), 0, 2000).sumOf { it.amountCents })
    }
    @Test fun changingTransferDateMovesFeeAndBothLegsTogetherAndDeleteHonorsEvidence() = runBlocking {
        accounts()
        repository.createTransfer(1, 2, 1000, 100, "", 2000)
        val anchor = db.transactionDao().getAllRaw().first { it.relationKind == "TRANSFER_OUT" }
        assertTrue(repository.edit(anchor.id, 1000, TransactionType.TRANSFER, TransactionCategory.TRANSFER, "零钱", "", 1, 3000))
        assertTrue(db.transactionDao().getAllRaw().all { it.timestamp == 3000L })
        repository.importBills(listOf(bankTransfer()))
        val imported = db.transactionDao().getByImportKey("bank-1")!!
        assertTrue(repository.linkDuplicate(imported.id, anchor.id))
        val incoming = db.transactionDao().getById(anchor.relatedTransactionId!!)!!
        assertEquals(0, repository.delete(incoming))
    }
    @Test fun transferAndRefundRetriesRejectChangedPayloadAndDeletedRecords() = runBlocking {
        accounts()
        assertTrue(repository.createTransfer(1, 2, 1000, 100, "a", 2000, "transfer-form"))
        assertTrue(repository.createTransfer(1, 2, 1000, 100, "a", 2000, "transfer-form"))
        assertFalse(repository.createTransfer(1, 2, 1000, 200, "a", 2000, "transfer-form"))
        assertFalse(repository.createTransfer(1, null, 1000, 100, "a", 2000, "transfer-form"))
        val transfer = db.transactionDao().getByRequestId("transfer-form")!!
        assertTrue(repository.delete(transfer) > 0)
        assertFalse(repository.createTransfer(1, 2, 1000, 100, "a", 2000, "transfer-form"))
        val original = repository.insert(TransactionEntity(amountCents = 10000, type = "EXPENSE", category = "FOOD", source = "MANUAL", timestamp = 1))
        assertTrue(repository.recordReturn(original, "REFUND", 500, null, "b", 3000, "refund-form"))
        assertTrue(repository.recordReturn(original, "REFUND", 500, null, "b", 3000, "refund-form"))
        assertFalse(repository.recordReturn(original, "REFUND", 600, null, "b", 3000, "refund-form"))
        repository.delete(db.transactionDao().getByRequestId("refund-form")!!)
        assertFalse(repository.recordReturn(original, "REFUND", 500, null, "b", 3000, "refund-form"))
    }

    @Test fun restoreWriteFailureRollsBackTheWholeCurrentLedger() = runBlocking {
        accounts()
        val context = RuntimeEnvironment.getApplication()
        val prefs = BudgetPreferences(context, db)
        prefs.setMonthlyTotal(50000)
        repository.insert(TransactionEntity(amountCents = 1200, type = "EXPENSE", category = "FOOD", source = "MANUAL", timestamp = 1))
        val backup = LedgerBackup(context, db, db.accountDao(), db.transactionDao(), db.ledgerMetaDao(), prefs)
        val file = java.io.File.createTempFile("rollback-fixture", ".sbk", context.cacheDir)
        try {
            backup.export(Uri.fromFile(file), "audit-fixture-password")
            prefs.setMonthlyTotal(90000)
            repository.insert(TransactionEntity(amountCents = 3400, type = "EXPENSE", category = "SHOPPING", source = "MANUAL", timestamp = 2))
            val before = db.transactionDao().getAllRaw()
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_restore BEFORE INSERT ON transactions BEGIN SELECT RAISE(ABORT, 'fixture storage failure'); END")
            assertTrue(runCatching { backup.restore(Uri.fromFile(file), "audit-fixture-password") }.isFailure)
            assertEquals(before, db.transactionDao().getAllRaw())
            assertEquals(2, db.accountDao().getAll().size)
            assertEquals(90000L, prefs.observe().first().monthlyTotalCents)
            assertFalse(db.access.restoring.value)
        } finally { file.delete() }
    }

    @Test fun notificationConfirmationRechecksCandidatesAfterPreviewAndAmountCorrection() = runBlocking {
        val pending = PendingNotificationRepository(db, db.ledgerMetaDao(), db.transactionDao(), db.accountDao())
        val parsed = TransactionEntity(amountCents = 1200, type = "EXPENSE", category = "FOOD", source = "ALIPAY", timestamp = 2000)
        assertTrue(pending.save("fixture.package", "fixture-key", "付款成功", "付款成功12元", parsed))
        val draft = db.ledgerMetaDao().allPending().single()
        assertTrue(pending.nearby(draft.id, 1500, "EXPENSE").isEmpty())
        val existing = repository.insert(parsed.copy(amountCents = 1500, source = "MANUAL"))
        assertTrue(runCatching { pending.confirm(draft.id, 1500, "EXPENSE", "FOOD", "fixture", null) }.isFailure)
        assertEquals("PENDING", db.ledgerMetaDao().pendingById(draft.id)!!.status)
        assertEquals(1, db.transactionDao().getAllRaw().size)
        assertTrue(pending.confirm(draft.id, 1500, "EXPENSE", "FOOD", "fixture", null, setOf(existing)))
        assertFalse(pending.confirm(draft.id, 1500, "EXPENSE", "FOOD", "fixture", null, setOf(existing)))
        assertEquals(2, db.transactionDao().getAllRaw().size)
    }

}

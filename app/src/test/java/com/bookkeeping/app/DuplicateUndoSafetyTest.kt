package com.bookkeeping.app

import android.app.Application
import android.net.Uri
import androidx.room.Room
import com.bookkeeping.app.data.local.BookkeepingDatabase
import com.bookkeeping.app.data.local.entity.*
import com.bookkeeping.app.data.repository.*
import com.bookkeeping.app.data.budget.BudgetPreferences
import com.bookkeeping.app.data.backup.LedgerBackup
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class DuplicateUndoSafetyTest {
    private lateinit var db: BookkeepingDatabase
    private lateinit var repository: TransactionRepository
    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), BookkeepingDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = newRepository()
    }
    @After fun close() { db.close() }
    private fun newRepository() = TransactionRepository(db.transactionDao(), db.accountDao(), db.ledgerMetaDao(), db)
    private suspend fun pair(): Pair<Long, Long> {
        val primary = repository.insert(TransactionEntity(amountCents = 10_000, type = "EXPENSE", category = "SHOPPING",
            source = "MANUAL", timestamp = 1000))
        val secondary = repository.insert(TransactionEntity(amountCents = 10_000, type = "EXPENSE", category = "FOOD",
            source = "ALIPAY", timestamp = 2000))
        assertTrue(repository.confirmIndependent(secondary))
        return primary to secondary
    }
    private suspend fun addReturn(original: Long, cents: Long = 3000, kind: String = "REFUND"): Long {
        assertTrue(repository.recordReturn(original, kind, cents, null, "fixture", 3000))
        return db.transactionDao().getAllRaw().last { it.relatedTransactionId == original && it.relationKind == kind }.id
    }
    private fun batch() = ImportBatchEntity(fileName = "fixture.pdf", source = "BANK_PDF", importedAt = 1,
        originalRows = 1, parsedRows = 1, rejectedRows = 0, amountCents = 1000, fingerprint = "fixture")
    private fun imported() = TransactionEntity(amountCents = 1000, type = "EXPENSE", category = "FOOD",
        source = "BANK_PDF", timestamp = 1000, importKey = "fixture-import")

    @Test fun reimportAfterBatchUndoKeepsTombstoneUntilExplicitBatchRestore() = runBlocking {
        val first = repository.importBills(listOf(imported()), batch())
        assertEquals(1, repository.undoImport(first.batchId!!))
        val before = db.transactionDao().getAllRaw()
        assertEquals(0, repository.importBills(listOf(imported()), batch()).added)
        assertEquals(before, db.transactionDao().getAllRaw())
        assertNotNull(db.ledgerMetaDao().batchById(first.batchId)!!.undoneAt)
        assertEquals(1, db.ledgerMetaDao().allBatches().size)
        assertEquals(1, repository.restoreImport(first.batchId))
        assertNull(db.transactionDao().getAllRaw().single().deletedAt)
        assertEquals(0, repository.importBills(listOf(imported()), batch()).added)
        assertEquals(1, db.ledgerMetaDao().allBatches().size)
    }
    @Test fun deletedReimportWithoutBatchAlsoKeepsTheWholeRowUnchanged() = runBlocking {
        repository.importBills(listOf(imported()))
        val row = db.transactionDao().getAllRaw().single()
        repository.delete(row)
        val before = db.transactionDao().getById(row.id)
        assertEquals(0, repository.importBills(listOf(imported().copy(bankFlow = "OUT"))).added)
        assertEquals(before, db.transactionDao().getById(row.id))
        assertTrue(repository.restore(row.id))
        assertEquals(0, repository.importBills(listOf(imported())).added)
    }
    @Test fun multipleReturnsAndNewPrimaryReturnKeepTheirIndividualOwners() = runBlocking {
        val (primary, secondary) = pair()
        val refund = addReturn(secondary)
        val reimbursement = addReturn(secondary, 2000, "REIMBURSEMENT")
        assertTrue(repository.linkDuplicate(primary, secondary))
        val later = addReturn(primary, 1000)
        assertTrue(newRepository().unlinkDuplicate(secondary))
        assertEquals(secondary, db.transactionDao().getById(refund)!!.relatedTransactionId!!)
        assertEquals(secondary, db.transactionDao().getById(reimbursement)!!.relatedTransactionId!!)
        assertEquals(primary, db.transactionDao().getById(later)!!.relatedTransactionId!!)
        assertTrue(db.ledgerMetaDao().allDuplicateChanges().isEmpty())
        assertTrue(repository.confirmIndependent(secondary))
        assertEquals(14_000L, accountingPeriod(db.transactionDao().getAllRaw(), 0, 10000).filter { it.type == "EXPENSE" }.sumOf { it.amountCents })
    }
    @Test fun editingRefundAmountKeepsThatEditWhenOwnershipIsUndone() = runBlocking {
        val (primary, secondary) = pair()
        val refund = addReturn(secondary)
        assertTrue(repository.linkDuplicate(primary, secondary))
        assertTrue(repository.edit(refund, 4000, TransactionType.INCOME, TransactionCategory.SHOPPING, "", "edited", null))
        assertTrue(repository.unlinkDuplicate(secondary))
        val after = db.transactionDao().getById(refund)!!
        assertEquals(secondary, after.relatedTransactionId!!)
        assertEquals(4000L, after.amountCents)
        assertEquals("edited", after.note)
        assertEquals("FOOD", after.category)
    }
    @Test fun reassignedRefundBlocksUndoWithoutOverwritingNewRelationship() = runBlocking {
        val (primary, secondary) = pair()
        val refund = addReturn(secondary)
        assertTrue(repository.linkDuplicate(primary, secondary))
        val third = repository.insert(TransactionEntity(amountCents = 20_000, type = "EXPENSE", category = "FOOD", source = "MANUAL", timestamp = 1))
        assertTrue(repository.unlinkReturn(refund))
        assertTrue(repository.linkExistingReturn(third, refund, "REFUND"))
        val rows = db.transactionDao().getAllRaw()
        val history = db.ledgerMetaDao().allDuplicateChanges()
        assertFalse(repository.unlinkDuplicate(secondary))
        assertEquals(rows, db.transactionDao().getAllRaw())
        assertEquals(history, db.ledgerMetaDao().allDuplicateChanges())
    }
    @Test fun legacyLinkWithRefundsIsNotGuessedButPlainLegacyLinkCanBeUndone() = runBlocking {
        val (primary, secondary) = pair()
        val refund = addReturn(secondary)
        // Exact persisted v6 state after its linker moved the return without provenance.
        db.transactionDao().update(db.transactionDao().getById(refund)!!.copy(relatedTransactionId = primary, category = "SHOPPING"))
        db.transactionDao().update(db.transactionDao().getById(secondary)!!.copy(duplicateOfId = primary))
        val before = db.transactionDao().getAllRaw()
        assertFalse(repository.unlinkDuplicate(secondary))
        assertEquals(before, db.transactionDao().getAllRaw())
        db.transactionDao().update(db.transactionDao().getById(refund)!!.copy(relatedTransactionId = null, relationKind = "REFUND_PENDING"))
        assertTrue(repository.unlinkDuplicate(secondary))
    }
    @Test fun aDeletedMovedRefundRemainsDeletedAndCanBeRestoredAfterUndo() = runBlocking {
        val (primary, secondary) = pair()
        val refund = addReturn(secondary)
        assertTrue(repository.linkDuplicate(primary, secondary))
        assertEquals(1, repository.delete(db.transactionDao().getById(refund)!!))
        val deletedAt = db.transactionDao().getById(refund)!!.deletedAt
        assertTrue(repository.unlinkDuplicate(secondary))
        assertEquals(deletedAt, db.transactionDao().getById(refund)!!.deletedAt)
        assertEquals(secondary, db.transactionDao().getById(refund)!!.relatedTransactionId!!)
        assertTrue(repository.confirmIndependent(secondary))
        assertTrue(repository.restore(refund))
    }
    @Test fun newReturnDuplicatePreventsUndoOfItsEarlierOwnerChange() = runBlocking {
        val (primary, secondary) = pair()
        val refund = addReturn(secondary)
        assertTrue(repository.linkDuplicate(primary, secondary))
        val evidence = db.transactionDao().insert(db.transactionDao().getById(refund)!!.copy(id = 0, source = "BANK_PDF"))
        assertTrue(repository.linkDuplicate(refund, evidence))
        val before = db.transactionDao().getAllRaw()
        assertFalse(repository.unlinkDuplicate(secondary))
        assertEquals(before, db.transactionDao().getAllRaw())
    }
    @Test fun duplicateRefundLinkRestoresPrimaryAndSecondaryMetadataWithoutInventingIncome() = runBlocking {
        val expense = repository.insert(TransactionEntity(amountCents = 10000, type = "EXPENSE", category = "FOOD", source = "MANUAL", timestamp = 1))
        val refund = addReturn(expense)
        val evidence = repository.insert(TransactionEntity(amountCents = 3000, type = "INCOME", category = "OTHER",
            source = "BANK_PDF", timestamp = 3000, relationKind = "REFUND_PENDING"))
        assertTrue(repository.linkDuplicate(refund, evidence))
        assertEquals(expense, db.transactionDao().getById(evidence)!!.relatedTransactionId!!)
        assertTrue(repository.unlinkDuplicate(refund))
        assertEquals(expense, db.transactionDao().getById(refund)!!.relatedTransactionId!!)
        assertEquals("REFUND", db.transactionDao().getById(refund)!!.relationKind)
        assertNull(db.transactionDao().getById(evidence)!!.relatedTransactionId)
        assertEquals("REFUND_PENDING", db.transactionDao().getById(evidence)!!.relationKind)
        assertTrue(repository.confirmIndependent(refund))
        assertEquals(3000L, db.transactionDao().returnedAmount(expense))
    }
    @Test fun failedLinkAndFailedUndoRollBackRowsAndProvenanceTogether() = runBlocking {
        val (primary, secondary) = pair()
        val refund = addReturn(secondary)
        val before = db.transactionDao().getAllRaw()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_link BEFORE UPDATE ON transactions WHEN NEW.id = $refund BEGIN SELECT RAISE(ABORT, 'fixture link failure'); END")
        assertTrue(runCatching { repository.linkDuplicate(primary, secondary) }.isFailure)
        assertEquals(before, db.transactionDao().getAllRaw())
        assertTrue(db.ledgerMetaDao().allDuplicateChanges().isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_link")
        assertTrue(repository.linkDuplicate(primary, secondary))
        val linked = db.transactionDao().getAllRaw()
        val history = db.ledgerMetaDao().allDuplicateChanges()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_unlink BEFORE UPDATE ON transactions WHEN NEW.id = $refund BEGIN SELECT RAISE(ABORT, 'fixture unlink failure'); END")
        assertTrue(runCatching { repository.unlinkDuplicate(secondary) }.isFailure)
        assertEquals(linked, db.transactionDao().getAllRaw())
        assertEquals(history, db.ledgerMetaDao().allDuplicateChanges())
    }
    @Test fun concurrentUndoHasOneWinnerAndRetainsTheRefundOwner() = runBlocking {
        val (primary, secondary) = pair()
        val refund = addReturn(secondary)
        assertTrue(repository.linkDuplicate(primary, secondary))
        val outcomes = coroutineScope { (1..8).map { async(Dispatchers.IO) { repository.unlinkDuplicate(secondary) } }.map { it.await() } }
        assertEquals(1, outcomes.count { it })
        assertEquals(secondary, db.transactionDao().getById(refund)!!.relatedTransactionId!!)
        assertTrue(db.ledgerMetaDao().allDuplicateChanges().isEmpty())
    }
    @Test fun backupRestoreRetainsUndoProvenanceAndCanUndoAfterRepositoryRecreation() = runBlocking {
        val (primary, secondary) = pair()
        val refund = addReturn(secondary)
        assertTrue(repository.linkDuplicate(primary, secondary))
        val context = RuntimeEnvironment.getApplication()
        val backup = LedgerBackup(context, db, db.accountDao(), db.transactionDao(), db.ledgerMetaDao(), BudgetPreferences(context, db))
        val file = java.io.File.createTempFile("linked-fixture", ".sbk", context.cacheDir)
        try {
            val expectedHistory = db.ledgerMetaDao().allDuplicateChanges()
            backup.export(Uri.fromFile(file), "fixture-backup-password")
            assertTrue(repository.unlinkDuplicate(secondary))
            backup.restore(Uri.fromFile(file), "fixture-backup-password")
            assertEquals(expectedHistory, db.ledgerMetaDao().allDuplicateChanges())
            assertTrue(newRepository().unlinkDuplicate(secondary))
            assertEquals(secondary, db.transactionDao().getById(refund)!!.relatedTransactionId!!)
        } finally { file.delete() }
    }
    @Test fun restoringOldDeletedRefundCannotAttachToSecondaryExpense() = runBlocking {
        val (primary, secondary) = pair()
        val refund = addReturn(secondary)
        assertEquals(1, repository.delete(db.transactionDao().getById(refund)!!))
        assertTrue(repository.linkDuplicate(primary, secondary))
        assertFalse(repository.restore(refund))
        assertTrue(repository.unlinkDuplicate(secondary))
        assertTrue(repository.confirmIndependent(secondary))
        assertTrue(repository.restore(refund))
    }

    @Test fun mixedImportDoesNotAdoptDeletedRowsIntoTheNewBatch() = runBlocking {
        val initial = repository.importBills(listOf(imported()), batch()).batchId!!
        val old = db.transactionDao().getAllRaw().single()
        repository.delete(old)
        val tombstone = db.transactionDao().getById(old.id)
        val fresh = imported().copy(importKey = "fixture-new", amountCents = 2000)
        val result = repository.importBills(listOf(imported(), fresh), batch().copy(originalRows = 2, parsedRows = 2, amountCents = 3000))
        assertEquals(1, result.added)
        assertEquals(1, result.duplicates)
        assertEquals(tombstone, db.transactionDao().getById(old.id))
        assertEquals(initial, db.transactionDao().getById(old.id)!!.importBatchId!!)
        assertEquals(listOf("fixture-new"), db.transactionDao().byBatch(result.batchId!!).map { it.importKey })
        assertEquals(1, repository.undoImport(result.batchId))
        assertEquals(tombstone, db.transactionDao().getById(old.id))
        assertEquals(1, repository.restoreImport(result.batchId))
        assertEquals(tombstone, db.transactionDao().getById(old.id))
    }
    @Test fun journalSurvivesClosingAndReopeningTheDatabaseFile() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "duplicate-undo-reopen-fixture.db"
        context.deleteDatabase(name)
        db.close()
        db = Room.databaseBuilder(context, BookkeepingDatabase::class.java, name).allowMainThreadQueries().build()
        repository = newRepository()
        try {
            val (primary, secondary) = pair()
            val refund = addReturn(secondary)
            assertTrue(repository.linkDuplicate(primary, secondary))
            val history = db.ledgerMetaDao().allDuplicateChanges()
            db.close()
            db = Room.databaseBuilder(context, BookkeepingDatabase::class.java, name).allowMainThreadQueries().build()
            repository = newRepository()
            assertEquals(history, db.ledgerMetaDao().allDuplicateChanges())
            assertTrue(repository.unlinkDuplicate(secondary))
            assertEquals(secondary, db.transactionDao().getById(refund)!!.relatedTransactionId!!)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun restoringDeletedRefundCannotAttachToAnExpenseEditedIntoIncome() = runBlocking {
        val expense = repository.insert(TransactionEntity(amountCents = 10000, type = "EXPENSE", category = "FOOD", source = "MANUAL", timestamp = 1))
        val refund = addReturn(expense)
        repository.delete(db.transactionDao().getById(refund)!!)
        assertTrue(repository.edit(expense, 10000, TransactionType.INCOME, TransactionCategory.OTHER, "", "", null))
        assertFalse(repository.restore(refund))
        assertNotNull(db.transactionDao().getById(refund)!!.deletedAt)
    }

    @Test fun restoreFailurePreservesCurrentRefundEditsAndJournalInOneTransaction() = runBlocking {
        val (primary, secondary) = pair()
        val refund = addReturn(secondary)
        assertTrue(repository.linkDuplicate(primary, secondary))
        val context = RuntimeEnvironment.getApplication()
        val backup = LedgerBackup(context, db, db.accountDao(), db.transactionDao(), db.ledgerMetaDao(), BudgetPreferences(context, db))
        val file = java.io.File.createTempFile("undo-restore-failure", ".sbk", context.cacheDir)
        try {
            backup.export(Uri.fromFile(file), "fixture-backup-password")
            assertTrue(repository.edit(refund, 4000, TransactionType.INCOME, TransactionCategory.SHOPPING, "", "later edit", null))
            val beforeRows = db.transactionDao().getAllRaw()
            val beforeHistory = db.ledgerMetaDao().allDuplicateChanges()
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_history_restore BEFORE INSERT ON duplicate_link_changes BEGIN SELECT RAISE(ABORT, 'fixture history failure'); END")
            assertTrue(runCatching { backup.restore(Uri.fromFile(file), "fixture-backup-password") }.isFailure)
            assertEquals(beforeRows, db.transactionDao().getAllRaw())
            assertEquals(beforeHistory, db.ledgerMetaDao().allDuplicateChanges())
            assertFalse(db.access.restoring.value)
        } finally { file.delete() }
    }

    @Test fun laterEvidenceProtectsAdoptedPrimaryMetadataUntilItIsUnlinkedFirst() = runBlocking {
        val expense = repository.insert(TransactionEntity(amountCents = 10000, type = "EXPENSE", category = "FOOD", source = "MANUAL", timestamp = 1))
        val refund = addReturn(expense)
        val primary = repository.insert(TransactionEntity(amountCents = 3000, type = "INCOME", category = "OTHER",
            source = "BANK_PDF", timestamp = 3000, relationKind = "REFUND_PENDING"))
        assertTrue(repository.linkDuplicate(refund, primary))
        val later = repository.insert(TransactionEntity(amountCents = 3000, type = "INCOME", category = "OTHER",
            source = "ALIPAY", timestamp = 3000, relationKind = "REFUND_PENDING"))
        assertTrue(repository.linkDuplicate(primary, later))
        val before = db.transactionDao().getAllRaw()
        assertFalse(repository.unlinkDuplicate(refund))
        assertEquals(before, db.transactionDao().getAllRaw())
        // Soft deletion is reversible, so it must not bypass dependency protection.
        assertEquals(1, repository.delete(db.transactionDao().getById(later)!!))
        assertFalse(repository.unlinkDuplicate(refund))
        assertTrue(repository.restore(later))
        assertTrue(repository.unlinkDuplicate(later))
        assertTrue(repository.unlinkDuplicate(refund))
        assertEquals("REFUND_PENDING", db.transactionDao().getById(primary)!!.relationKind)
        assertEquals(expense, db.transactionDao().getById(refund)!!.relatedTransactionId!!)
    }
    @Test fun softDeletedRefundEvidenceStillProtectsItsEarlierOwnershipChange() = runBlocking {
        val (primary, secondary) = pair()
        val refund = addReturn(secondary)
        assertTrue(repository.linkDuplicate(primary, secondary))
        val evidence = db.transactionDao().insert(db.transactionDao().getById(refund)!!.copy(id = 0, source = "ALIPAY"))
        assertTrue(repository.linkDuplicate(refund, evidence))
        assertEquals(refund, db.transactionDao().getById(evidence)!!.duplicateOfId!!)
        assertEquals(1, repository.delete(db.transactionDao().getById(evidence)!!))
        assertFalse(repository.unlinkDuplicate(secondary))
        assertEquals(primary, db.transactionDao().getById(refund)!!.relatedTransactionId!!)
    }

}

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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ReversibilityBackupCompatibilityTest {
    private lateinit var db: BookkeepingDatabase
    private lateinit var repository: TransactionRepository
    private lateinit var backup: LedgerBackup
    private val password = "synthetic-backup-password"
    @Before fun open() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, BookkeepingDatabase::class.java).allowMainThreadQueries().build()
        repository = TransactionRepository(db.transactionDao(), db.accountDao(), db.ledgerMetaDao(), db)
        backup = LedgerBackup(context, db, db.accountDao(), db.transactionDao(), db.ledgerMetaDao(), BudgetPreferences(context, db))
    }
    @After fun close() { db.close() }
    private fun legacyJson(format: Int, version: Int): JSONObject {
        fun row(id: Long, type: String, source: String, cents: Long = 10000) = JSONObject()
            .put("id", id).put("amountCents", cents).put("type", type).put("source", source)
            .put("category", "FOOD").put("merchant", "fixture").put("note", "")
            .put("timestamp", 1000 + id).put("balanceAccountId", 1).put("balanceTrackingVersion", 1)
            .put("balanceIgnored", false).put("parserVersion", 1)
        return JSONObject().put("format", format).put("databaseVersion", version)
            .put("accounts", JSONArray().put(JSONObject().put("id", 1).put("name", "fixture")
                .put("kind", "BANK").put("balanceCents", 100000).put("updatedAt", 1)))
            .put("transactions", JSONArray()
                .put(row(1, "EXPENSE", "ALIPAY").put("duplicateOfId", 2))
                .put(row(2, "EXPENSE", "MANUAL"))
                .put(row(3, "INCOME", "MANUAL", 3000).put("relatedTransactionId", 2).put("relationKind", "REFUND")))
            .put("batches", JSONArray()).put("identities", JSONArray()).put("checkpoints", JSONArray())
            .put("pending", JSONArray()).put("budget", JSONObject().put("monthlyTotalCents", 50000).put("categories", JSONObject()))
    }
    /** Fixture envelope matches the released v6 implementation; no production data is used. */
    private fun encrypted(json: JSONObject): java.io.File {
        val salt = ByteArray(16) { (it + 1).toByte() }
        val iv = ByteArray(12) { (it + 20).toByte() }
        val spec = PBEKeySpec(password.toCharArray(), salt, 310_000, 256)
        val key = try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") }
            finally { spec.clearPassword() }
        val plain = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(json.toString().toByteArray(Charsets.UTF_8)) } }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return java.io.File.createTempFile("legacy-backup-fixture", ".sbk", RuntimeEnvironment.getApplication().cacheDir).apply {
            outputStream().use { it.write("SBK1".toByteArray()); it.write(salt); it.write(iv); it.write(cipher.doFinal(plain)) }
        }
    }
    @Test fun releasedBackupFormatsRestoreWithoutInventingHistoricalProvenance() = runBlocking {
        for ((format, version) in listOf(1 to 5, 2 to 6)) {
            val file = encrypted(legacyJson(format, version))
            try {
                assertEquals(3, backup.restore(Uri.fromFile(file), password).transactions)
                assertTrue(db.ledgerMetaDao().allDuplicateChanges().isEmpty())
                assertEquals(2L, db.transactionDao().getById(3)!!.relatedTransactionId!!)
                assertEquals(7000L, accountingPeriod(db.transactionDao().getAllRaw(), 0, 10000).single().amountCents)
                val before = db.transactionDao().getAllRaw()
                assertFalse(repository.unlinkDuplicate(1))
                assertEquals(before, db.transactionDao().getAllRaw())
            } finally { file.delete() }
        }
    }
    @Test fun newBackupMissingProvenancePartitionIsRejectedBeforeAnyWrite() = runBlocking {
        val id = repository.insert(TransactionEntity(amountCents = 1234, type = "EXPENSE", category = "FOOD", source = "MANUAL", timestamp = 1000))
        val before = db.transactionDao().getById(id)
        val file = encrypted(legacyJson(3, 7))
        try {
            assertTrue(runCatching { backup.restore(Uri.fromFile(file), password) }.isFailure)
            assertEquals(before, db.transactionDao().getById(id))
            assertEquals(1, db.transactionDao().getAllRaw().size)
        } finally { file.delete() }
    }
    @Test fun restoringOldBackupClearsNewJournalEvenWhenTransactionIdsAreReused() = runBlocking {
        val primary = repository.insert(TransactionEntity(amountCents = 10000, type = "EXPENSE", category = "FOOD", source = "MANUAL", timestamp = 1))
        val secondary = repository.insert(TransactionEntity(amountCents = 10000, type = "EXPENSE", category = "FOOD", source = "ALIPAY", timestamp = 2))
        assertTrue(repository.confirmIndependent(secondary))
        assertTrue(repository.recordReturn(secondary, "REFUND", 3000, null, "fixture", 3))
        assertTrue(repository.linkDuplicate(primary, secondary))
        assertTrue(db.ledgerMetaDao().allDuplicateChanges().isNotEmpty())
        val file = encrypted(legacyJson(2, 6))
        try {
            backup.restore(Uri.fromFile(file), password)
            assertTrue(db.ledgerMetaDao().allDuplicateChanges().isEmpty())
            assertEquals(2L, db.transactionDao().getById(1)!!.duplicateOfId!!)
            assertFalse(repository.unlinkDuplicate(1))
        } finally { file.delete() }
    }

}

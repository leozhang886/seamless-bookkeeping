package com.bookkeeping.app.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.bookkeeping.app.data.budget.BudgetPreferences
import com.bookkeeping.app.data.budget.BudgetSettings
import com.bookkeeping.app.data.local.BookkeepingDatabase
import com.bookkeeping.app.data.local.dao.AccountDao
import com.bookkeeping.app.data.local.dao.LedgerMetaDao
import com.bookkeeping.app.data.local.dao.TransactionDao
import com.bookkeeping.app.data.local.entity.AccountEntity
import com.bookkeeping.app.data.local.entity.BalanceCheckpointEntity
import com.bookkeeping.app.data.local.entity.ImportBatchEntity
import com.bookkeeping.app.data.local.entity.ImportIdentityEntity
import com.bookkeeping.app.data.local.entity.PendingNotificationEntity
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** A versioned, authenticated local backup. Plaintext is never written to shared storage. */
@Singleton
class LedgerBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: BookkeepingDatabase,
    private val accounts: AccountDao,
    private val transactions: TransactionDao,
    private val meta: LedgerMetaDao,
    private val budgets: BudgetPreferences,
) {
    data class Result(val accounts: Int, val transactions: Int)

    private data class Snapshot(
        val accounts: List<AccountEntity>,
        val transactions: List<TransactionEntity>,
        val batches: List<ImportBatchEntity>,
        val identities: List<ImportIdentityEntity>,
        val checkpoints: List<BalanceCheckpointEntity>,
        val pending: List<PendingNotificationEntity>,
        val budget: BudgetSettings,
    )

    suspend fun export(uri: Uri, password: String): Result = withContext(Dispatchers.IO) {
        require(password.length >= 12) { "备份口令至少 12 个字符" }
        val snapshot = database.withTransaction {
            Snapshot(accounts.getAllRaw(), transactions.getAllRaw(), meta.allBatches(),
                meta.allIdentities(), meta.allCheckpoints(), meta.allPending(), budgets.observe().first())
        }
        val plain = encode(snapshot)
        require(plain.size <= MAX_PLAIN_BYTES) { "账本过大，暂无法生成备份" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        }
        val compressed = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(plain) }
        }.toByteArray()
        val encrypted = cipher.doFinal(compressed)
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(MAGIC)
            out.write(salt)
            out.write(iv)
            out.write(encrypted)
            out.flush()
        } ?: error("无法写入备份文件")
        // Read the provider's saved bytes back; a successful write alone is not a verified backup.
        val checked = read(uri, password)
        check(checked.accounts.size == snapshot.accounts.size &&
            checked.transactions.size == snapshot.transactions.size) { "备份校验失败，请重新导出" }
        Result(snapshot.accounts.size, snapshot.transactions.size)
    }

    suspend fun restore(uri: Uri, password: String): Result = withContext(Dispatchers.IO) {
        val snapshot = read(uri, password) // Authentication and validation finish before any write.
        val oldBudget = budgets.observe().first()
        try { database.withTransaction {
            meta.clearPending()
            meta.clearIdentities()
            meta.clearBatches()
            meta.clearCheckpoints()
            transactions.clearAll()
            accounts.clearAll()
            snapshot.accounts.forEach { accounts.insert(it) }
            snapshot.transactions.forEach { transactions.insert(it) }
            snapshot.batches.forEach { meta.restoreBatch(it) }
            snapshot.identities.forEach { meta.restoreIdentity(it) }
            snapshot.checkpoints.forEach { meta.restoreCheckpoint(it) }
            snapshot.pending.forEach { meta.restorePending(it) }
            // DataStore is separate storage; if its update throws, Room rolls back.
            budgets.replace(snapshot.budget)
        } } catch (error: Exception) {
            runCatching { budgets.replace(oldBudget) }
            throw error
        }
        Result(snapshot.accounts.size, snapshot.transactions.size)
    }

    private fun read(uri: Uri, password: String): Snapshot {
        require(password.isNotBlank()) { "请输入备份口令" }
        val bytes = context.contentResolver.openInputStream(uri)?.use { limited(it, MAX_FILE_BYTES) }
            ?: error("无法打开备份文件")
        require(bytes.size > 32 && bytes.copyOfRange(0, 4).contentEquals(MAGIC)) { "不是受支持的账本备份" }
        val salt = bytes.copyOfRange(4, 20)
        val iv = bytes.copyOfRange(20, 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        }
        val compressed = try { cipher.doFinal(bytes, 32, bytes.size - 32) }
            catch (_: Exception) { throw IllegalArgumentException("口令错误或备份文件已损坏") }
        val plain = GZIPInputStream(ByteArrayInputStream(compressed)).use { limited(it, MAX_PLAIN_BYTES) }
        return decode(plain)
    }

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, 310_000, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }

    private fun encode(s: Snapshot): ByteArray {
        val root = JSONObject().put("format", 1).put("databaseVersion", 5)
        root.put("accounts", JSONArray().also { a -> s.accounts.forEach { x -> a.put(JSONObject()
            .put("id", x.id).put("name", x.name).put("kind", x.kind)
            .put("balanceCents", x.balanceCents).put("updatedAt", x.updatedAt)
            .put("archivedAt", x.archivedAt)) } })
        root.put("transactions", JSONArray().also { a -> s.transactions.forEach { x -> a.put(JSONObject()
            .put("id", x.id).put("amountCents", x.amountCents).put("type", x.type)
            .put("category", x.category).put("merchant", x.merchant).put("note", x.note)
            .put("source", x.source).put("account", x.account).put("timestamp", x.timestamp)
            .put("importKey", x.importKey).put("bankFlow", x.bankFlow)
            .put("balanceAccountId", x.balanceAccountId).put("balanceTrackingVersion", x.balanceTrackingVersion)
            .put("balanceIgnored", x.balanceIgnored).put("importBatchId", x.importBatchId)
            .put("duplicateOfId", x.duplicateOfId).put("relatedTransactionId", x.relatedTransactionId)
            .put("relationKind", x.relationKind).put("deletedAt", x.deletedAt)
            .put("parserVersion", x.parserVersion).put("sourceRow", x.sourceRow)
            .put("sourceOrderId", x.sourceOrderId)) } })
        root.put("batches", JSONArray().also { a -> s.batches.forEach { x -> a.put(JSONObject()
            .put("id", x.id).put("fileName", x.fileName).put("source", x.source)
            .put("importedAt", x.importedAt).put("originalRows", x.originalRows)
            .put("parsedRows", x.parsedRows).put("rejectedRows", x.rejectedRows)
            .put("amountCents", x.amountCents).put("fingerprint", x.fingerprint)
            .put("undoneAt", x.undoneAt)) } })
        root.put("identities", JSONArray().also { a -> s.identities.forEach { x -> a.put(JSONObject()
            .put("importKey", x.importKey).put("transactionId", x.transactionId)) } })
        root.put("checkpoints", JSONArray().also { a -> s.checkpoints.forEach { x -> a.put(JSONObject()
            .put("id", x.id).put("accountId", x.accountId).put("balanceCents", x.balanceCents)
            .put("occurredAt", x.occurredAt).put("reason", x.reason)) } })
        root.put("pending", JSONArray().also { a -> s.pending.forEach { x -> a.put(JSONObject()
            .put("id", x.id).put("fingerprint", x.fingerprint).put("sourcePackage", x.sourcePackage)
            .put("rawText", x.rawText).put("rawTimestamp", x.rawTimestamp)
            .put("amountCents", x.amountCents).put("type", x.type).put("category", x.category)
            .put("merchant", x.merchant).put("source", x.source).put("confidence", x.confidence)
            .put("status", x.status)) } })
        root.put("budget", JSONObject().put("monthlyTotalCents", s.budget.monthlyTotalCents)
            .put("categories", JSONObject().also { o -> s.budget.perCategory.forEach { (k, v) -> o.put(k.name, v) } }))
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    private fun decode(bytes: ByteArray): Snapshot {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        require(root.getInt("format") == 1 && root.getInt("databaseVersion") <= 5) { "备份版本不受支持" }
        fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
        val accountList = root.getJSONArray("accounts").objects().map { x -> AccountEntity(
            id = x.getLong("id"), name = x.getString("name"), kind = x.getString("kind"),
            balanceCents = x.getLong("balanceCents"), updatedAt = x.getLong("updatedAt"),
            archivedAt = x.nullLong("archivedAt")) }
        val transactionList = root.getJSONArray("transactions").objects().map { x -> TransactionEntity(
            id = x.getLong("id"), amountCents = x.getLong("amountCents"), type = x.getString("type"),
            category = x.getString("category"), merchant = x.getString("merchant"),
            note = x.getString("note"), source = x.getString("source"), account = x.nullString("account"),
            timestamp = x.getLong("timestamp"), importKey = x.nullString("importKey"),
            bankFlow = x.nullString("bankFlow"), balanceAccountId = x.nullLong("balanceAccountId"),
            balanceTrackingVersion = x.nullInt("balanceTrackingVersion"),
            balanceIgnored = x.getBoolean("balanceIgnored"), importBatchId = x.nullLong("importBatchId"),
            duplicateOfId = x.nullLong("duplicateOfId"), relatedTransactionId = x.nullLong("relatedTransactionId"),
            relationKind = x.nullString("relationKind"), deletedAt = x.nullLong("deletedAt"),
            parserVersion = x.getInt("parserVersion"), sourceRow = x.nullInt("sourceRow"),
            sourceOrderId = x.nullString("sourceOrderId")) }
        val batches = root.getJSONArray("batches").objects().map { x -> ImportBatchEntity(
            id = x.getLong("id"), fileName = x.getString("fileName"), source = x.getString("source"),
            importedAt = x.getLong("importedAt"), originalRows = x.getInt("originalRows"),
            parsedRows = x.getInt("parsedRows"), rejectedRows = x.getInt("rejectedRows"),
            amountCents = x.getLong("amountCents"), fingerprint = x.getString("fingerprint"),
            undoneAt = x.nullLong("undoneAt")) }
        val identities = root.getJSONArray("identities").objects().map { x -> ImportIdentityEntity(
            importKey = x.getString("importKey"), transactionId = x.getLong("transactionId")) }
        val checkpoints = root.getJSONArray("checkpoints").objects().map { x -> BalanceCheckpointEntity(
            id = x.getLong("id"), accountId = x.getLong("accountId"),
            balanceCents = x.getLong("balanceCents"), occurredAt = x.getLong("occurredAt"),
            reason = x.getString("reason")) }
        val pending = root.getJSONArray("pending").objects().map { x -> PendingNotificationEntity(
            id = x.getLong("id"), fingerprint = x.getString("fingerprint"),
            sourcePackage = x.getString("sourcePackage"), rawText = x.getString("rawText"),
            rawTimestamp = x.getLong("rawTimestamp"), amountCents = x.getLong("amountCents"),
            type = x.getString("type"), category = x.getString("category"), merchant = x.getString("merchant"),
            source = x.getString("source"), confidence = x.getInt("confidence"), status = x.getString("status")) }
        val budgetJson = root.getJSONObject("budget")
        val categories = budgetJson.getJSONObject("categories")
        val budget = BudgetSettings(budgetJson.getLong("monthlyTotalCents"),
            categories.keys().asSequence().mapNotNull { name ->
                runCatching { TransactionCategory.valueOf(name) }.getOrNull()?.let { it to categories.getLong(name) }
            }.toMap())
        require(accountList.size <= 100_000 && transactionList.size <= 500_000) { "备份记录数超出上限" }
        require(accountList.map { it.id }.toSet().size == accountList.size &&
            transactionList.map { it.id }.toSet().size == transactionList.size &&
            transactionList.all { it.id > 0 && it.amountCents > 0 } &&
            accountList.all { it.id > 0 }) { "备份记录不完整" }
        val accountIds = accountList.map { it.id }.toSet()
        val transactionIds = transactionList.map { it.id }.toSet()
        val batchIds = batches.map { it.id }.toSet()
        require(batchIds.size == batches.size &&
            identities.map { it.importKey }.toSet().size == identities.size &&
            pending.map { it.fingerprint }.toSet().size == pending.size &&
            transactionList.all { row ->
                (row.balanceAccountId == null || row.balanceAccountId in accountIds) &&
                (row.relatedTransactionId == null || row.relatedTransactionId in transactionIds) &&
                (row.duplicateOfId == null || row.duplicateOfId in transactionIds) &&
                (row.importBatchId == null || row.importBatchId in batchIds)
            } && checkpoints.all { it.accountId in accountIds } &&
            identities.all { it.transactionId in transactionIds }) { "备份关联关系不完整" }
        return Snapshot(accountList, transactionList, batches, identities, checkpoints, pending, budget)
    }

    private fun JSONObject.nullLong(key: String): Long? = if (isNull(key)) null else getLong(key)
    private fun JSONObject.nullInt(key: String): Int? = if (isNull(key)) null else getInt(key)
    private fun JSONObject.nullString(key: String): String? = if (isNull(key)) null else getString(key)

    private fun limited(input: java.io.InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            require(out.size() + read <= limit) { "备份文件过大" }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    companion object {
        private val MAGIC = byteArrayOf('S'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte())
        private const val MAX_FILE_BYTES = 128_000_000
        private const val MAX_PLAIN_BYTES = 200_000_000
    }
}

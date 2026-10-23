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
        budgets.ensureMigrated()
        val snapshot = database.withTransaction {
            Snapshot(accounts.getAllRaw(), transactions.getAllRaw(), meta.allBatches(),
                meta.allIdentities(), meta.allCheckpoints(), meta.allPending(), budgets.snapshotInTransaction())
        }
        val compressed = encode(snapshot)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        }
        val encrypted = cipher.doFinal(compressed)
        val temporary = java.io.File.createTempFile("ledger-export-", ".sbk", context.cacheDir)
        try {
            temporary.outputStream().use { out -> out.write(MAGIC); out.write(salt); out.write(iv); out.write(encrypted) }
            check(read(Uri.fromFile(temporary), password) == snapshot) { "备份校验失败" }
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                temporary.inputStream().use { it.copyTo(out) }; out.flush()
            } ?: error("无法写入备份文件")
            check(read(uri, password) == snapshot) { "备份回读校验失败，请重新导出" }
        } finally { temporary.delete() }
        Result(snapshot.accounts.size, snapshot.transactions.size)
    }

    suspend fun restore(uri: Uri, password: String): Result = withContext(Dispatchers.IO) {
        val snapshot = read(uri, password) // Authentication and validation finish before any write.
        budgets.ensureMigrated()
        database.access.replace {
            // Keep an authenticated recovery copy before replacing any rows.
            val recovery = java.io.File(context.filesDir, "recovery").apply { mkdirs() }
            val file = java.io.File(recovery, "before-restore-${System.currentTimeMillis()}.sbk")
            export(Uri.fromFile(file), password)
            database.withTransaction {
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
            budgets.replaceInTransaction(snapshot.budget)
            }
        }
        Result(snapshot.accounts.size, snapshot.transactions.size)
    }

    suspend fun validate(uri: Uri, password: String): Result = withContext(Dispatchers.IO) {
        val snapshot = read(uri, password)
        Result(snapshot.accounts.size, snapshot.transactions.size)
    }
    fun recoveryFiles(): List<java.io.File> = java.io.File(context.filesDir, "recovery")
        .listFiles()?.filter { it.extension == "sbk" }?.sortedByDescending { it.lastModified() }.orEmpty()

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
        return GZIPInputStream(ByteArrayInputStream(compressed)).use { decode(it) }
    }

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, 310_000, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }

    private fun encode(s: Snapshot): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            var written = 0L
            val bounded = object : java.io.OutputStream() {
                override fun write(value: Int) { require(++written <= MAX_PLAIN_BYTES) { "账本超过备份大小上限" }; gzip.write(value) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    written += length; require(written <= MAX_PLAIN_BYTES) { "账本超过备份大小上限" }; gzip.write(bytes, offset, length)
                }
            }
            val writer = java.io.OutputStreamWriter(bounded, Charsets.UTF_8)
            writer.write("{\"format\":2,\"databaseVersion\":6")
            fun <T> rows(name: String, values: List<T>, encodeRow: (T) -> JSONObject) {
                writer.write(",\"$name\":[")
                values.forEachIndexed { index, value ->
                    if (index > 0) writer.write(",")
                    writer.write(encodeRow(value).toString())
                }
                writer.write("]")
            }
            rows("accounts", s.accounts) { x -> JSONObject()
            .put("id", x.id).put("name", x.name).put("kind", x.kind)
            .put("balanceCents", x.balanceCents).put("updatedAt", x.updatedAt)
            .put("archivedAt", x.archivedAt) }
            rows("transactions", s.transactions) { x -> JSONObject()
            .put("id", x.id).put("amountCents", x.amountCents).put("type", x.type)
            .put("category", x.category).put("merchant", x.merchant).put("note", x.note)
            .put("source", x.source).put("account", x.account).put("timestamp", x.timestamp)
            .put("importKey", x.importKey).put("bankFlow", x.bankFlow)
            .put("balanceAccountId", x.balanceAccountId).put("balanceTrackingVersion", x.balanceTrackingVersion)
            .put("balanceIgnored", x.balanceIgnored).put("importBatchId", x.importBatchId)
            .put("duplicateOfId", x.duplicateOfId).put("relatedTransactionId", x.relatedTransactionId)
            .put("relationKind", x.relationKind).put("deletedAt", x.deletedAt)
            .put("parserVersion", x.parserVersion).put("sourceRow", x.sourceRow)
            .put("sourceOrderId", x.sourceOrderId).put("createdAt", x.createdAt).put("modifiedAt", x.modifiedAt)
            .put("sourcePaymentMethod", x.sourcePaymentMethod).put("sourceAccountKind", x.sourceAccountKind)
            .put("sourceBalanceCents", x.sourceBalanceCents).put("sourceIdentity", x.sourceIdentity)
            .put("rawData", x.rawData).put("reviewReason", x.reviewReason)
            .put("timePrecision", x.timePrecision).put("sourcePage", x.sourcePage).put("clientRequestId", x.clientRequestId) }
            rows("batches", s.batches) { x -> JSONObject()
            .put("id", x.id).put("fileName", x.fileName).put("source", x.source)
            .put("importedAt", x.importedAt).put("originalRows", x.originalRows)
            .put("parsedRows", x.parsedRows).put("rejectedRows", x.rejectedRows)
            .put("amountCents", x.amountCents).put("fingerprint", x.fingerprint)
            .put("undoneAt", x.undoneAt) }
            rows("identities", s.identities) { x -> JSONObject()
            .put("importKey", x.importKey).put("transactionId", x.transactionId) }
            rows("checkpoints", s.checkpoints) { x -> JSONObject()
            .put("id", x.id).put("accountId", x.accountId).put("balanceCents", x.balanceCents)
            .put("occurredAt", x.occurredAt).put("reason", x.reason) }
            rows("pending", s.pending) { x -> JSONObject()
            .put("id", x.id).put("fingerprint", x.fingerprint).put("sourcePackage", x.sourcePackage)
            .put("rawText", x.rawText).put("rawTimestamp", x.rawTimestamp)
            .put("amountCents", x.amountCents).put("type", x.type).put("category", x.category)
            .put("merchant", x.merchant).put("source", x.source).put("confidence", x.confidence)
            .put("status", x.status).put("parserVersion", x.parserVersion).put("sourceAccount", x.sourceAccount)
            .put("sourceAccountKind", x.sourceAccountKind).put("bankFlow", x.bankFlow)
            .put("relationKind", x.relationKind).put("receivedAt", x.receivedAt).put("eventKey", x.eventKey)
            .put("transactionId", x.transactionId) }
            writer.write(",\"budget\":")
            writer.write(JSONObject().put("monthlyTotalCents", s.budget.monthlyTotalCents)
                .put("categories", JSONObject().also { o -> s.budget.perCategory.forEach { (k, v) -> o.put(k.name, v) } }).toString())
            writer.write("}"); writer.flush()
        }
        return output.toByteArray()
    }

    private fun decode(input: java.io.InputStream): Snapshot {
        var readBytes = 0L
        val bounded = object : java.io.FilterInputStream(input) {
            override fun read(): Int = super.read().also { if (it >= 0) { readBytes++; checkLimit() } }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also {
                if (it > 0) { readBytes += it; checkLimit() }
            }
            fun checkLimit() { require(readBytes <= MAX_PLAIN_BYTES) { "备份解压后过大" } }
        }
        val reader = android.util.JsonReader(java.io.InputStreamReader(bounded, Charsets.UTF_8))
        fun objectRow(depth: Int = 0): JSONObject {
            require(depth <= 3) { "备份结构过深" }
            val value = JSONObject(); val names = mutableSetOf<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(names.add(name) && names.size <= 64) { "备份字段重复或过多" }
                val field: Any = when (reader.peek()) {
                    android.util.JsonToken.BEGIN_OBJECT -> objectRow(depth + 1)
                    android.util.JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
                    android.util.JsonToken.BOOLEAN -> reader.nextBoolean()
                    android.util.JsonToken.NUMBER -> reader.nextString().also { require(Regex("-?[0-9]+").matches(it)) { "备份数字格式错误" } }
                    android.util.JsonToken.STRING -> reader.nextString().also { require(it.length <= 16000) { "备份字段过长" } }
                    else -> error("备份结构不受支持")
                }
                value.put(name, field)
            }
            reader.endObject(); return value
        }
        fun <T> rows(limit: Int = 500_000, parse: (JSONObject) -> T): List<T> {
            val list = ArrayList<T>(); reader.beginArray()
            while (reader.hasNext()) { require(list.size < limit) { "备份记录数超出上限" }; list += parse(objectRow()) }
            reader.endArray(); return list
        }
        var accountList: List<AccountEntity> = emptyList()
        var transactionList: List<TransactionEntity> = emptyList()
        var batches: List<ImportBatchEntity> = emptyList()
        var identities: List<ImportIdentityEntity> = emptyList()
        var checkpoints: List<BalanceCheckpointEntity> = emptyList()
        var pending: List<PendingNotificationEntity> = emptyList()
        var budgetJson = JSONObject()
        var format = 0; var version = 0
        val seen = mutableSetOf<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName(); require(seen.add(name)) { "备份分区重复" }
            when (name) {
                "format" -> format = reader.nextInt()
                "databaseVersion" -> version = reader.nextInt()
                "accounts" -> accountList = rows { x -> AccountEntity(
            id = x.strictLong("id"), name = x.getString("name"), kind = x.getString("kind"),
            balanceCents = x.strictLong("balanceCents"), updatedAt = x.strictLong("updatedAt"),
            archivedAt = x.nullLong("archivedAt")) }
                "transactions" -> transactionList = rows { x -> TransactionEntity(
            id = x.strictLong("id"), amountCents = x.strictLong("amountCents"), type = x.getString("type"),
            category = x.getString("category"), merchant = x.getString("merchant"),
            note = x.getString("note"), source = x.getString("source"), account = x.nullString("account"),
            timestamp = x.strictLong("timestamp"), importKey = x.nullString("importKey"),
            bankFlow = x.nullString("bankFlow"), balanceAccountId = x.nullLong("balanceAccountId"),
            balanceTrackingVersion = x.nullInt("balanceTrackingVersion"),
            balanceIgnored = x.getBoolean("balanceIgnored"), importBatchId = x.nullLong("importBatchId"),
            duplicateOfId = x.nullLong("duplicateOfId"), relatedTransactionId = x.nullLong("relatedTransactionId"),
            relationKind = x.nullString("relationKind"), deletedAt = x.nullLong("deletedAt"),
            parserVersion = x.getInt("parserVersion"), sourceRow = x.nullInt("sourceRow"),
            sourceOrderId = x.nullString("sourceOrderId"), createdAt = x.nullLong("createdAt"), modifiedAt = x.nullLong("modifiedAt"),
            sourcePaymentMethod = x.nullString("sourcePaymentMethod"), sourceAccountKind = x.nullString("sourceAccountKind"),
            sourceBalanceCents = x.nullLong("sourceBalanceCents"), sourceIdentity = x.nullString("sourceIdentity"),
            rawData = x.nullString("rawData"), reviewReason = x.nullString("reviewReason"),
            timePrecision = x.optString("timePrecision", if (x.getString("source") == "BANK_PDF") "DAY" else "INSTANT"),
            sourcePage = x.nullInt("sourcePage"), clientRequestId = x.nullString("clientRequestId")) }
                "batches" -> batches = rows { x -> ImportBatchEntity(
            id = x.strictLong("id"), fileName = x.getString("fileName"), source = x.getString("source"),
            importedAt = x.strictLong("importedAt"), originalRows = x.getInt("originalRows"),
            parsedRows = x.getInt("parsedRows"), rejectedRows = x.getInt("rejectedRows"),
            amountCents = x.strictLong("amountCents"), fingerprint = x.getString("fingerprint"),
            undoneAt = x.nullLong("undoneAt")) }
                "identities" -> identities = rows { x -> ImportIdentityEntity(
            importKey = x.getString("importKey"), transactionId = x.strictLong("transactionId")) }
                "checkpoints" -> checkpoints = rows { x -> BalanceCheckpointEntity(
            id = x.strictLong("id"), accountId = x.strictLong("accountId"),
            balanceCents = x.strictLong("balanceCents"), occurredAt = x.strictLong("occurredAt"),
            reason = x.getString("reason")) }
                "pending" -> pending = rows { x -> PendingNotificationEntity(
            id = x.strictLong("id"), fingerprint = x.getString("fingerprint"),
            sourcePackage = x.getString("sourcePackage"), rawText = x.getString("rawText"),
            rawTimestamp = x.strictLong("rawTimestamp"), amountCents = x.strictLong("amountCents"),
            type = x.getString("type"), category = x.getString("category"), merchant = x.getString("merchant"),
            source = x.getString("source"), confidence = x.getInt("confidence"), status = x.getString("status"),
            parserVersion = x.optInt("parserVersion", 1), sourceAccount = x.nullString("sourceAccount"),
            sourceAccountKind = x.nullString("sourceAccountKind"), bankFlow = x.nullString("bankFlow"),
            relationKind = x.nullString("relationKind"), receivedAt = x.nullLong("receivedAt"),
            eventKey = x.nullString("eventKey"), transactionId = x.nullLong("transactionId")) }
                "budget" -> budgetJson = objectRow()
                else -> error("备份包含未知分区")
            }
        }
        reader.endObject()
        require(reader.peek() == android.util.JsonToken.END_DOCUMENT) { "备份末尾有多余内容" }
        require(format in 1..2 && version in 1..6 && seen.containsAll(setOf("accounts", "transactions", "batches", "identities", "checkpoints", "pending", "budget"))) { "备份版本不受支持或分区不完整" }
        val categories = budgetJson.getJSONObject("categories")
        val budget = BudgetSettings(budgetJson.strictLong("monthlyTotalCents"),
            categories.keys().asSequence().mapNotNull { name ->
                runCatching { TransactionCategory.valueOf(name) }.getOrNull()?.let { it to categories.strictLong(name) }
            }.toMap())
        require(accountList.size <= 100_000 && transactionList.size <= 500_000) { "备份记录数超出上限" }
        require(accountList.map { it.id }.toSet().size == accountList.size &&
            transactionList.map { it.id }.toSet().size == transactionList.size &&
            transactionList.all { it.id > 0 && it.amountCents > 0 && it.type in setOf("EXPENSE", "INCOME", "TRANSFER") &&
                it.duplicateOfId != it.id && it.relatedTransactionId != it.id && it.bankFlow in setOf(null, "IN", "OUT") } &&
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
        require(checkpoints.map { it.id }.toSet().size == checkpoints.size && pending.map { it.id }.toSet().size == pending.size &&
            transactionList.mapNotNull { it.clientRequestId }.let { it.size == it.toSet().size } &&
            pending.all { it.transactionId == null || it.transactionId in transactionIds } &&
            budget.monthlyTotalCents >= 0 && budget.perCategory.values.all { it >= 0 }) { "备份包含重复标识或无效预算" }
        val byId = transactionList.associateBy { it.id }
        require(transactionList.all { row -> row.duplicateOfId?.let { primaryId ->
            val primary = byId.getValue(primaryId)
            primary.duplicateOfId == null && (row.deletedAt != null || primary.deletedAt == null)
        } ?: true }) { "备份存在循环或无效的重复关联" }
        val returned = transactionList.filter { it.deletedAt == null && it.duplicateOfId == null &&
            it.relationKind in setOf("REFUND", "REIMBURSEMENT") }
        require(returned.all { row -> byId[row.relatedTransactionId]?.let {
            it.type == "EXPENSE" && it.deletedAt == null && it.duplicateOfId == null && row.type == "INCOME"
        } == true }) { "备份的退款原交易无效" }
        require(returned.groupBy { it.relatedTransactionId }.all { (id, rows) ->
            rows.fold(0L) { sum, row -> Math.addExact(sum, row.amountCents) } <= byId.getValue(id!!).amountCents
        }) { "备份的退款金额超过原支出" }
        return Snapshot(accountList, transactionList, batches, identities, checkpoints, pending, budget)
    }

    private fun JSONObject.strictLong(name: String): Long {
        val value = get(name).toString()
        require(Regex("-?[0-9]+").matches(value)) { "备份整数金额或标识格式错误" }
        return value.toLongOrNull() ?: error("备份数值超出范围")
    }

    private fun JSONObject.nullLong(key: String): Long? = if (!has(key) || isNull(key)) null else strictLong(key)
    private fun JSONObject.nullInt(key: String): Int? = if (!has(key) || isNull(key)) null else getInt(key)
    private fun JSONObject.nullString(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)

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

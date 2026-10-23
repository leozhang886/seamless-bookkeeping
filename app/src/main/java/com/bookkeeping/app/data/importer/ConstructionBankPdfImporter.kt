package com.bookkeeping.app.data.importer

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.notification.CategoryClassifier
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** Reads the text table in China Construction Bank's personal account statement. */
object ConstructionBankPdfImporter {
    internal data class Glyph(val x: Float, val y: Float, val text: String)

    private class GlyphStripper : PDFTextStripper() {
        val glyphs = mutableListOf<Glyph>()

        override fun processTextPosition(text: TextPosition) {
            val value = text.unicode ?: return
            require(glyphs.size < 200_000) { "PDF 单页内容过于复杂" }
            if (value.isNotBlank()) glyphs += Glyph(text.xDirAdj, text.yDirAdj, value)
        }
    }

    fun parse(input: InputStream): List<TransactionEntity> = parseReport(input).let { report ->
        require(report.blockingErrors.isEmpty()) { report.blockingErrors.joinToString("；") }
        report.records
    }

    fun parseReport(input: InputStream): BillParseReport {
        val bytes = input.readLimited(12_000_000)
        PDDocument.load(bytes).use { document ->
            if (document.isEncrypted) throw IllegalArgumentException("请先解锁 PDF 账单")
            if (document.numberOfPages !in 1..200) throw IllegalArgumentException("PDF 页数不受支持")
            val result = mutableListOf<TransactionEntity>()
            val rejected = mutableListOf<BillRowIssue>()
            val blockers = mutableListOf<String>()
            var originalRows = 0
            var reportedCount: Int? = null
            var cardTail = ""
            for (page in 1..document.numberOfPages) {
                if (abs(document.getPage(page - 1).mediaBox.width - 595f) > 15f) {
                    throw IllegalArgumentException("目前只支持建设银行导出的 A4 交易明细 PDF")
                }
                val stripper = GlyphStripper().apply { startPage = page; endPage = page }
                stripper.getText(document)
                val glyphs = stripper.glyphs
                if (page == 1) {
                    val title = glyphs.filter { it.y in 15f..45f }.sortedBy { it.x }.joinToString("") { it.text }
                    if (!title.contains("建设银行") || !title.contains("交易明细")) {
                        throw IllegalArgumentException("目前只支持建设银行个人账户交易明细的文字版 PDF")
                    }
                    val cardLine = glyphs.filter { it.y in 45f..70f }.sortedBy { it.x }.joinToString("") { it.text }
                    cardTail = Regex("\\d{12,19}").find(cardLine)?.value?.takeLast(4).orEmpty()
                }
                val pageResult = readPage(glyphs, cardTail, page)
                result += pageResult.records
                rejected += pageResult.rejected
                originalRows += pageResult.originalRows
                if (pageResult.originalRows == 0) blockers += "第${page}页没有定位到交易行，请核对版式或空白页"
                val pageText = glyphs.sortedWith(compareBy<Glyph> { it.y }.thenBy { it.x }).joinToString("") { it.text }
                Regex("(?:总笔数[:：]?|共)(\\d+)笔?").find(pageText)?.groupValues?.get(1)?.toIntOrNull()?.let {
                    reportedCount = it
                }
            }
            if (result.isEmpty()) throw IllegalArgumentException("PDF 中没有找到可导入的交易；扫描图片版暂不支持")
            if (rejected.isNotEmpty()) blockers += "PDF 有${rejected.size}行无法核验，未允许部分写入，请核对原文件"
            reportedCount?.let { if (it != originalRows) blockers += "PDF 标示${it}笔，实际定位${originalRows}笔，笔数不一致" }
            if (!balancesContinuous(result)) blockers += "交易后余额无法连续核验，请检查是否缺页、漏行或版式不受支持"
            return BillParseReport(TransactionSource.BANK_PDF.name, result, originalRows, 0, rejected, blockers)
        }
    }

    internal fun readPage(glyphs: List<Glyph>, cardTail: String, page: Int): BillParseReport {
        // Sequence and date columns independently locate candidate rows. A broken date
        // remains an error instead of disappearing from the "original row" count.
        val dates = glyphs.filter { (it.x in 100f..150f || it.x in 5f..47f) &&
            it.y in 120f..725f && it.text.all(Char::isDigit) }
            .sortedWith(compareBy<Glyph> { it.y }.thenBy { it.x })
        val lines = mutableListOf<MutableList<Glyph>>()
        for (glyph in dates) {
            if (lines.isEmpty() || abs(lines.last().first().y - glyph.y) > 2.5f) lines.add(mutableListOf())
            lines.last().add(glyph)
        }
        val records = mutableListOf<TransactionEntity>()
        val rejected = mutableListOf<BillRowIssue>()
        lines.forEachIndexed { index, line ->
            val y = line.first().y
            val columns = MutableList(7) { "" }
            val boundaries = listOf(0f, 48f, 100f, 150f, 225f, 300f, 435f, 610f)
            glyphs.asSequence().filter { abs(it.y - y) <= 9f && it.x in 0f..610f }
                .sortedWith(compareBy<Glyph> { it.y }.thenBy { it.x })
                .forEach { glyph ->
                    val index = (0..6).firstOrNull { glyph.x >= boundaries[it] && glyph.x < boundaries[it + 1] }
                    if (index != null) columns[index] += glyph.text
                }
            val parsed = runCatching {
                require(Regex("20\\d{6}").matches(columns[2])) { "日期列无法核对" }
                parseColumns(columns, cardTail).copy(sourcePage = page, sourceRow = columns[0].toIntOrNull() ?: index + 1)
            }
            parsed.onSuccess(records::add).onFailure {
                rejected += BillRowIssue(index + 1, "第${page}页第${index + 1}行：${it.message ?: "格式不匹配"}")
            }
        }
        return BillParseReport(TransactionSource.BANK_PDF.name, records, lines.size, 0, rejected)
    }

    internal fun balancesContinuous(rows: List<TransactionEntity>): Boolean {
        if (rows.size < 2) return true
        fun consistent(ordered: List<TransactionEntity>) = ordered.zipWithNext().all { (previous, current) ->
            val before = previous.sourceBalanceCents ?: return@all false
            val after = current.sourceBalanceCents ?: return@all false
            val signed = if (current.bankFlow == "OUT") -current.amountCents else current.amountCents
            runCatching { Math.addExact(before, signed) == after }.getOrDefault(false)
        }
        return consistent(rows) || consistent(rows.asReversed())
    }

    internal fun parseColumns(columns: List<String>, cardTail: String): TransactionEntity {
        require(columns.size == 7) { "PDF 表格列数不正确" }
        val summary = columns[1].trim()
        val date = columns[2].trim()
        val signedAmount = columns[3].replace(",", "").trim().toBigDecimalOrNull()
            ?: throw IllegalArgumentException("PDF 中有无法识别的交易金额")
        if (signedAmount.signum() == 0) throw IllegalArgumentException("PDF 中有零金额交易，需人工核对")
        val amount = runCatching { signedAmount.abs().movePointRight(2).setScale(0).longValueExact() }
            .getOrElse { throw IllegalArgumentException("PDF 中有无法识别的交易金额") }
        val formatter = SimpleDateFormat("yyyyMMdd", Locale.CHINA).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }
        val timestamp = formatter.parse(date)?.time ?: throw IllegalArgumentException("PDF 中有无法识别的交易日期")
        val counterparty = columns[6].substringAfter('/', columns[6]).replace(Regex("\\d{6,}"), "****").trim()
        val merchant = counterparty.ifBlank { summary }.take(80)
        val location = columns[5].replace(Regex("\\d{6,}"), "****").trim()
        val note = listOf(summary, location, "原账单仅含日期").filter { it.isNotBlank() }.joinToString(" / ").take(200)
        val fee = listOf("手续费", "利息", "服务费").any(summary::contains)
        val transfer = !fee && listOf("基金申购", "基金赎回", "余额充值", "零钱充值", "零钱提现", "还款", "ATM存款", "数字人民币兑回")
            .any(summary::contains)
        val type = when {
            transfer -> TransactionType.TRANSFER
            signedAmount.signum() > 0 -> TransactionType.INCOME
            else -> TransactionType.EXPENSE
        }
        val relationKind = when {
            type == TransactionType.INCOME && listOf("退款", "退回", "冲正").any(summary::contains) -> "REFUND_PENDING"
            type == TransactionType.INCOME && summary.contains("报销") -> "REIMBURSEMENT_PENDING"
            else -> null
        }
        val classified = CategoryClassifier.classify(merchant, note)
        val category = if (transfer) TransactionCategory.TRANSFER else classified.takeUnless {
            it == TransactionCategory.TRANSFER } ?: TransactionCategory.OTHER
        val balance = columns[4].replace(",", "").trim().toBigDecimalOrNull()
            ?.let { runCatching { it.movePointRight(2).setScale(0).longValueExact() }.getOrNull() }
            ?: throw IllegalArgumentException("PDF 中交易后余额无法核验")
        val identity = listOf("CCB", cardTail, columns[0], date, columns[1], columns[3], columns[4], columns[5], columns[6]).joinToString("|")
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return TransactionEntity(
            amountCents = amount,
            type = type.name,
            category = category.name,
            merchant = merchant,
            note = note,
            source = TransactionSource.BANK_PDF.name,
            account = if (cardTail.isBlank()) "建设银行" else "建设银行（尾号$cardTail）",
            timestamp = timestamp,
            importKey = key,
            bankFlow = if (signedAmount.signum() > 0) "IN" else "OUT",
            relationKind = relationKind,
            sourcePaymentMethod = "建设银行${cardTail}", sourceAccountKind = "BANK",
            sourceBalanceCents = balance, timePrecision = "DAY", parserVersion = 2,
            sourceIdentity = MessageDigest.getInstance("SHA-256").digest(
                listOf("CCB", cardTail, date, summary, signedAmount.stripTrailingZeros().toPlainString(),
                    balance.toString(), counterparty, location).joinToString("|").toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) },
            rawData = columns.joinToString("\u001F").replace(Regex("\\d{6,}"), "****").take(1800),
            reviewReason = if (!transfer && classified != TransactionCategory.SALARY &&
                listOf("汇兑", "跨行转出", "无卡自助交易").any(summary::contains))
                "请确认银行流入/流出是否为本人账户之间转账" else null,
        )
    }

    private fun InputStream.readLimited(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw IllegalArgumentException("PDF 账单文件过大")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

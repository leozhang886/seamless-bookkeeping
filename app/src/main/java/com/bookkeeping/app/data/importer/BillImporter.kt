package com.bookkeeping.app.data.importer

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.notification.CategoryClassifier
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Reads exported WeChat/Alipay bills on-device. No file is uploaded or retained. */
object BillImporter {
    fun parse(input: InputStream, fileName: String): List<TransactionEntity> {
        val rows = when {
            fileName.endsWith(".csv", true) -> parseCsv(decode(input.readLimited(12_000_000)))
            fileName.endsWith(".xlsx", true) -> parseXlsx(input)
            else -> throw IllegalArgumentException("请选择微信或支付宝导出的 CSV / XLSX 文件")
        }
        val headerIndex = rows.indexOfFirst { row ->
            row.any { it.trim().removePrefix("\uFEFF") == "交易时间" } &&
                row.any { it.trim().startsWith("收/支") } &&
                row.any { it.trim().contains("金额") }
        }
        if (headerIndex < 0) throw IllegalArgumentException("找不到交易时间、收/支和金额表头")
        val header = rows[headerIndex].map { it.trim().removePrefix("\uFEFF") }
        val preview = rows.take(headerIndex + 1).flatten().joinToString(" ") + fileName
        val source = when {
            preview.contains("微信") || header.any { it == "支付方式" } -> TransactionSource.WECHAT
            preview.contains("支付宝") || header.any { it.contains("收/付款方式") } -> TransactionSource.ALIPAY
            else -> throw IllegalArgumentException("无法辨认账单来源，请使用微信或支付宝原始导出文件")
        }
        fun column(vararg names: String): Int = header.indexOfFirst { cell -> names.any { cell == it || cell.startsWith(it) } }
        val timeCol = column("交易时间")
        val directionCol = column("收/支")
        val amountCol = column("金额")
        val merchantCol = column("交易对方", "对方")
        val productCol = column("商品说明", "商品")
        val noteCol = column("备注")
        val orderCol = column("交易单号", "交易订单号")
        val stateCol = column("当前状态", "交易状态")
        val methodCol = column("支付方式", "收/付款方式")
        val account = if (source == TransactionSource.WECHAT) "微信" else "支付宝"
        return rows.drop(headerIndex + 1).mapNotNull { row ->
            fun cell(index: Int) = row.getOrNull(index)?.trim().orEmpty()
            val timestamp = parseTime(cell(timeCol)) ?: return@mapNotNull null
            val amount = parseCents(cell(amountCol)) ?: return@mapNotNull null
            val state = cell(stateCol)
            if (listOf("关闭", "失败", "撤销", "取消", "未付款").any { state.contains(it) }) return@mapNotNull null
            val product = cell(productCol)
            val merchant = cell(merchantCol).ifBlank { product.ifBlank { "未知" } }
            if (merchant.contains("合计") || merchant.contains("总计")) return@mapNotNull null
            val method = cell(methodCol)
            val rawNote = listOf(product, cell(noteCol), method).filter { it.isNotBlank() }.joinToString(" / ").take(200)
            val walletAccount = identifiedWalletAccount(source, method)
            val accountMarker = when {
                walletAccount != null -> "[付款账户:$walletAccount]"
                method.isNotBlank() -> "[付款账户:其他方式]"
                else -> null
            }
            val note = if (accountMarker == null) rawNote else
                listOf(rawNote.take(170), accountMarker).filter { it.isNotBlank() }.joinToString(" / ")
            val direction = cell(directionCol)
            val transferText = "$merchant $product $rawNote"
            val transferHint = listOf("零钱充值", "零钱提现", "余额宝转入", "余额宝转出", "信用卡还款")
                .any { transferText.contains(it) }
            val type = when {
                transferHint -> TransactionType.TRANSFER
                direction.contains("收入") -> TransactionType.INCOME
                direction.contains("支出") -> TransactionType.EXPENSE
                else -> return@mapNotNull null
            }
            val category = if (type == TransactionType.TRANSFER) TransactionCategory.TRANSFER
                else CategoryClassifier.classify(merchant, rawNote)
            val order = cell(orderCol)
            val identity = if (order.isNotBlank()) "$source|$order" else
                "$source|$timestamp|$amount|$type|$merchant|$rawNote"
            TransactionEntity(
                amountCents = amount,
                type = type.name,
                category = category.name,
                merchant = merchant.take(80),
                note = note,
                source = source.name,
                account = account,
                timestamp = timestamp,
                importKey = sha256(identity),
                bankFlow = if (type == TransactionType.TRANSFER) when {
                    direction.contains("收入") -> "IN"
                    direction.contains("支出") -> "OUT"
                    else -> null
                } else null,
            )
        }
    }

    private fun identifiedWalletAccount(source: TransactionSource, method: String): String? {
        val exact = method.replace(" ", "").trim()
        return when (source) {
            TransactionSource.ALIPAY -> if (exact in setOf("余额", "支付宝余额", "余额支付")) "支付宝余额" else null
            TransactionSource.WECHAT -> if (exact in setOf("零钱", "微信零钱", "零钱支付")) "微信零钱" else null
            else -> null
        }
    }

    private fun parseCents(value: String): Long? {
        val cleaned = value.replace("¥", "").replace("￥", "").replace("元", "")
            .replace(",", "").trim()
        val amount = cleaned.toBigDecimalOrNull() ?: return null
        if (amount.signum() <= 0) return null
        return runCatching { amount.movePointRight(2).setScale(0).longValueExact() }.getOrNull()
    }

    private fun parseTime(value: String): Long? {
        val text = value.trim()
        for (pattern in listOf("yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm")) {
            val format = SimpleDateFormat(pattern, Locale.CHINA).apply { isLenient = false }
            runCatching { format.parse(text) }.getOrNull()?.let { return it.time }
        }
        val serial = text.toDoubleOrNull() ?: return null
        if (serial < 20_000 || serial > 100_000) return null
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
            set(1899, Calendar.DECEMBER, 30, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        calendar.add(Calendar.DAY_OF_YEAR, serial.toInt())
        calendar.add(Calendar.MILLISECOND, ((serial % 1) * 86_400_000).toInt())
        return calendar.timeInMillis
    }

    private fun decode(bytes: ByteArray): String {
        val raw = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) bytes.copyOfRange(3, bytes.size) else bytes
        return runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
        }.getOrElse { String(raw, Charset.forName("GB18030")) }
    }

    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            when (val c = text[i]) {
                '"' -> if (quoted && i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else quoted = !quoted
                ',' -> if (quoted) cell.append(c) else { row.add(cell.toString()); cell.setLength(0) }
                '\n' -> if (quoted) cell.append(c) else { row.add(cell.toString()); cell.setLength(0); rows.add(row.toList()); row.clear() }
                '\r' -> if (quoted) cell.append(c)
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows
    }

    private fun parseXlsx(input: InputStream): List<List<String>> {
        var shared = emptyList<String>()
        var sheet: ByteArray? = null
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when (entry.name) {
                    "xl/sharedStrings.xml" -> shared = xml(zip.readLimited(8_000_000)).getElementsByTagName("si")
                        .let { nodes -> (0 until nodes.length).map { index ->
                            val texts = (nodes.item(index) as Element).getElementsByTagName("t")
                            (0 until texts.length).joinToString("") { texts.item(it).textContent }
                        } }
                    "xl/worksheets/sheet1.xml" -> sheet = zip.readLimited(12_000_000)
                }
                zip.closeEntry()
            }
        }
        val doc = xml(sheet ?: throw IllegalArgumentException("XLSX 中没有第一页账单"))
        val nodes = doc.getElementsByTagName("row")
        return (0 until nodes.length).map { index ->
            val cells = (nodes.item(index) as Element).getElementsByTagName("c")
            val row = mutableListOf<String>()
            for (j in 0 until cells.length) {
                val cell = cells.item(j) as Element
                val ref = cell.getAttribute("r").takeWhile { it.isLetter() }
                val position = ref.fold(0) { acc, ch -> acc * 26 + (ch.uppercaseChar() - 'A' + 1) } - 1
                if (position !in 0..100) continue
                while (row.size <= position) row.add("")
                val raw = if (cell.getAttribute("t") == "inlineStr") cell.getElementsByTagName("t").item(0)?.textContent.orEmpty()
                    else cell.getElementsByTagName("v").item(0)?.textContent.orEmpty()
                row[position] = if (cell.getAttribute("t") == "s") shared.getOrNull(raw.toIntOrNull() ?: -1).orEmpty() else raw
            }
            row
        }
    }

    private fun xml(bytes: ByteArray): org.w3c.dom.Document {
        // Android's XML provider does not support every JAXP feature. Reject DTDs
        // before parsing, then enable additional protections where available.
        val source = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        if (source.contains("\u0000") || source.contains("<!DOCTYPE", ignoreCase = true) ||
            source.contains("<!ENTITY", ignoreCase = true)) {
            throw IllegalArgumentException("账单包含不支持的 XML 声明")
        }
        val factory = DocumentBuilderFactory.newInstance()
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun InputStream.readLimited(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw IllegalArgumentException("账单文件过大")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

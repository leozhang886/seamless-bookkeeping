package com.bookkeeping.app

import com.bookkeeping.app.data.importer.BillImporter
import com.bookkeeping.app.data.local.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BillImporterTest {
    @Test fun promotionalRedPacketIsHeldForReviewInsteadOfIncome() {
        val csv = """支付宝账单
交易时间,交易对方,商品说明,收/支,金额(元),收/付款方式,交易状态,交易订单号
2026-10-19 11:00:00,淘宝闪购,淘宝闪购红包,收入,20.00,支付宝余额,交易成功,ALI-GIFT-1
""".trimIndent()
        val report = BillImporter.parseReport(ByteArrayInputStream(csv.toByteArray()), "支付宝账单.csv")
        assertEquals(0, report.records.size)
        assertEquals(1, report.rejected.size)
    }

    @Test fun neutralWechatWithdrawalDebitsWalletButNotDestinationBank() {
        val csv = """微信支付账单
交易时间,交易类型,交易对方,收/支,金额(元),支付方式,当前状态,交易单号
2026-10-19 11:00:00,零钱提现,微信支付,/,10.00,建设银行储蓄卡(4562),提现已到账,WX-WD-1
""".trimIndent()
        val row = BillImporter.parseReport(ByteArrayInputStream(csv.toByteArray()), "微信账单.csv").records.single()
        assertEquals(TransactionType.TRANSFER.name, row.type)
        assertEquals("OUT", row.bankFlow)
        assertTrue(row.note.contains("[付款账户:微信零钱]"))
        assertTrue(!row.note.contains("[付款账户:建设银行]"))
    }

    @Test fun previewReportsRejectedRowsInsteadOfDroppingThemSilently() {
        val csv = """微信支付账单
交易时间,交易对方,收/支,金额(元),支付方式,当前状态,交易单号
2026-10-15 12:00:00,早餐,支出,12.00,零钱,支付成功,WX100
2026-10-15 13:00:00,未知,支出,坏金额,零钱,支付成功,WX101
2026-10-15 14:00:00,取消,支出,5.00,零钱,交易关闭,WX102
""".trimIndent()
        val report = BillImporter.parseReport(ByteArrayInputStream(csv.toByteArray()), "微信账单.csv")
        assertEquals(3, report.originalRows)
        assertEquals(1, report.records.size)
        assertEquals(1, report.rejected.size)
        assertEquals(1, report.ignoredRows)
        assertEquals(1200L, report.amountCents)
        assertEquals(3, report.records.single().sourceRow)
    }

    @Test fun wechatCsvKeepsOriginalFieldsAndSkipsFailedRows() {
        val csv = """微信支付账单
交易时间,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,备注
2026-10-15 12:34:56,"便利店,上海",饮料,支出,¥12.34,零钱,支付成功,WX001,"早饭,饮料"
2026-10-15 13:00:00,测试商户,商品,支出,¥1.00,零钱,交易关闭,WX002,
""".trimIndent()
        val bills = BillImporter.parse(ByteArrayInputStream(csv.toByteArray()), "微信账单.csv")
        assertEquals(1, bills.size)
        assertEquals(1234L, bills[0].amountCents)
        assertEquals("便利店,上海", bills[0].merchant)
        assertEquals(TransactionType.EXPENSE.name, bills[0].type)
        assertTrue(bills[0].note.contains("[付款账户:微信零钱]"))
        assertEquals(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).parse("2026-10-15 12:34:56")!!.time, bills[0].timestamp)
        assertNotNull(bills[0].importKey)
    }

    @Test fun alipayGb18030CsvDetectsTransfer() {
        val csv = "交易时间,交易对方,商品说明,收/支,金额(元),收/付款方式,交易状态,交易订单号\n" +
            "2026-10-15 10:00:00,余额宝,余额宝转入,不计收支,100.00,招商银行卡,交易成功,ALI001\n"
        val bills = BillImporter.parse(ByteArrayInputStream(csv.toByteArray(Charset.forName("GB18030"))), "支付宝账单.csv")
        assertEquals(1, bills.size)
        assertEquals(TransactionType.TRANSFER.name, bills[0].type)
        assertTrue(!bills[0].note.contains("[付款账户:支付宝余额]"))
    }

    @Test fun xlsxInlineStringsAreReadWithoutExtraLibrary() {
        val sheet = """<worksheet><sheetData>
          <row r="1"><c r="A1" t="inlineStr"><is><t>交易时间</t></is></c><c r="B1" t="inlineStr"><is><t>交易对方</t></is></c><c r="C1" t="inlineStr"><is><t>收/支</t></is></c><c r="D1" t="inlineStr"><is><t>金额(元)</t></is></c><c r="E1" t="inlineStr"><is><t>支付方式</t></is></c></row>
          <row r="2"><c r="A2" t="inlineStr"><is><t>2026-10-15 11:00:00</t></is></c><c r="B2" t="inlineStr"><is><t>商店</t></is></c><c r="C2" t="inlineStr"><is><t>支出</t></is></c><c r="D2"><v>8.50</v></c><c r="E2" t="inlineStr"><is><t>零钱</t></is></c></row>
        </sheetData></worksheet>"""
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sheet.toByteArray())
            zip.closeEntry()
        }
        val bills = BillImporter.parse(ByteArrayInputStream(buffer.toByteArray()), "账单.xlsx")
        assertEquals(1, bills.size)
        assertEquals(850L, bills[0].amountCents)
    }

    @Test fun xlsxWithDoctypeIsRejected() {
        val sheet = """<?xml version="1.0"?><!DOCTYPE worksheet [<!ENTITY hidden "bad">]><worksheet/>"""
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sheet.toByteArray())
            zip.closeEntry()
        }
        val failure = runCatching { BillImporter.parse(ByteArrayInputStream(buffer.toByteArray()), "账单.xlsx") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}

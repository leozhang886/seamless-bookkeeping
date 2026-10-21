package com.bookkeeping.app.ui.screen.transactions

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.notification.CategoryClassifier
import java.math.BigDecimal

internal data class VoiceTransactionDraft(
    val amountText: String?,
    val type: TransactionType,
    val category: TransactionCategory,
    val note: String,
)

/** Only proposes form values. Transfers and fund purchases need their own accounting route. */
internal fun parseVoiceTransaction(spoken: String): VoiceTransactionDraft? {
    val text = spoken.trim()
    if (text.isBlank() || listOf("转账", "转入", "转出", "汇款", "基金申购", "买基金", "购买基金", "充值", "提现", "还款")
            .any(text::contains)) return null

    val type = if (listOf("工资", "薪水", "奖金", "收入", "收到", "到账", "入账", "退款", "退回", "报销")
            .any(text::contains)) TransactionType.INCOME else TransactionType.EXPENSE
    val category = when {
        listOf("午饭", "早餐", "晚饭", "吃饭", "外卖", "奶茶", "咖啡", "买菜").any(text::contains) -> TransactionCategory.FOOD
        listOf("打车", "坐车", "公交", "地铁").any(text::contains) -> TransactionCategory.TRANSPORT
        else -> CategoryClassifier.classify("", text)
    }
    return VoiceTransactionDraft(findSpokenAmount(text), type, category, text)
}

private val currencyAmount = Regex("([0-9]+(?:\\.[0-9]{1,2})?|[零〇一二两三四五六七八九十百千万]+)\\s*(元|块|角|毛|分)")
private val bareAmount = Regex("(?<![0-9])[0-9]+(?:\\.[0-9]{1,2})?(?![0-9])")
private val fractionalTail = Regex("^\\s*([0-9]|[零〇一二两三四五六七八九])\\s*(?:角|毛)?(?:\\s*([0-9]|[零〇一二两三四五六七八九])\\s*分)?")

private fun findSpokenAmount(text: String): String? {
    val matches = currencyAmount.findAll(text).toList()
    val match = matches.firstOrNull()
    if (match != null) {
        val whole = parseNumber(match.groupValues[1]) ?: return null
        val unit = match.groupValues[2]
        val tail = text.substring(match.range.last + 1)
        val fraction = if (unit == "元" || unit == "块") fractionalTail.find(tail) else null
        val fractionText = fraction?.value.orEmpty()
        val fractionIsExplicit = fractionText.contains("角") || fractionText.contains("毛") || fractionText.contains("分")
        val fractionIsFinal = tail.substring(fractionText.length).firstOrNull()?.let { it in "，。,.！!、 " } ?: true
        val useFraction = fractionText.isNotBlank() && (fractionIsExplicit || fractionIsFinal)
        val consumedEnd = match.range.last + 1 + if (useFraction) fractionText.length else 0
        // Several independent prices (e.g. original price and actual payment) are ambiguous.
        if (matches.drop(1).any { it.range.first >= consumedEnd }) return null
        val amount = when (unit) {
            "角", "毛" -> whole.movePointLeft(1)
            "分" -> whole.movePointLeft(2)
            else -> {
                if (useFraction && fraction != null) {
                    val tenths = parseNumber(fraction.groupValues[1]) ?: return null
                    val hundredths = fraction.groupValues[2].takeIf { it.isNotBlank() }?.let(::parseNumber) ?: BigDecimal.ZERO
                    whole + tenths.movePointLeft(1) + hundredths.movePointLeft(2)
                } else whole
            }
        }
        return amount.takeIf { it > BigDecimal.ZERO && it.scale() <= 2 }?.stripTrailingZeros()?.toPlainString()
    }
    // A single bare number is useful for "打车18"; several numbers are ambiguous.
    val numbers = bareAmount.findAll(text).toList()
    return numbers.singleOrNull()?.value?.takeIf { parseAmountCents(it) != null }
}

private fun parseNumber(value: String): BigDecimal? {
    value.toBigDecimalOrNull()?.let { return it }
    val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2,
        '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
    val units = mapOf('十' to 10, '百' to 100, '千' to 1000)
    var total = 0L
    var section = 0L
    var digit = 0L
    for (char in value) {
        when {
            char in digits -> digit = digits.getValue(char).toLong()
            char in units -> {
                section += (if (digit == 0L) 1L else digit) * units.getValue(char)
                digit = 0
            }
            char == '万' -> {
                total += (section + digit) * 10000
                section = 0
                digit = 0
            }
            else -> return null
        }
    }
    // "两百五" and "一万二" omit a unit and can mean different amounts.
    if (value.lastOrNull() in digits && value.any { it == '百' || it == '千' || it == '万' } &&
        !value.dropLast(1).endsWith("零") && !value.dropLast(1).endsWith("〇")) return null
    return BigDecimal.valueOf(total + section + digit)
}

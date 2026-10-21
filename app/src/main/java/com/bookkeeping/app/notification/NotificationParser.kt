package com.bookkeeping.app.notification

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import java.math.BigDecimal

// 解析支付宝 / 微信通知文本，提取金额、收支方向、商户名。
//
// 真实通知样本（调研自 v2ex / AutoAccountingOrg issues / CFANZ + 用户实测）：
//   - 支付宝个人收款：  title="支付宝支付"   text="成功收款 1.00 元。享免费提现等更多专属服务..."
//   - 支付宝付款成功：  title="付款成功"    text="付款成功￥3.00 (AB)湖北武汉黄陂汉口北服装城美宜佳"
//   - 支付宝通用支出：  text="你有一笔 88.00 元的支出，点此查看详情"
//   - 支付宝退款：     title="支付宝"     text="收到退款 X.XX 元"
//   - 微信主动付款：   title="微信支付"   text="向 麦当劳 付款 12.30 元"
//   - 微信收到转账：   title="微信支付"   text="向你付款 100.00 元"  (别人付给你)
//   - 微信收转账：     text="收到 张三 的转账 100.00 元"
//
// ⚠️ 局限 1：微信扫码收款从 2020 后不再发系统通知（改 app 内提示）。
// ⚠️ 局限 2：支付宝转账入账（"某某给你转了一笔钱，点击查看详细信息"）通知文本不含金额，
//            extractAmount 会返回 null，整条通知被丢弃。
// 两个场景都需要 AccessibilityService 兜底（task #11）。
object NotificationParser {

    private const val PKG_ALIPAY = "com.eg.android.AlipayGphone"
    private const val PKG_WECHAT = "com.tencent.mm"

    // 金额提取：只匹配带 ￥/¥ 前缀或后缀"元"的数字，避免误匹配日期等
    private val AMOUNT_REGEX_SYMBOL = Regex("""[¥￥]\s*([0-9]+(?:\.[0-9]{1,2})?)""")
    private val AMOUNT_REGEX_YUAN = Regex("""([0-9]+(?:\.[0-9]{1,2})?)\s*元""")

    // 收入关键词
    private val INCOME_KEYWORDS = listOf(
        "成功收款", "收款成功", "已收款", "您已收款",
        "收款￥", "收到转账", "转账到账",
        "向你付款", "向您付款",  // 别人付给你（微信）
        "退款成功", "已退款", "收到退款",
    )

    // 支出关键词
    private val EXPENSE_KEYWORDS = listOf(
        "付款成功", "成功付款", "已付款", "您已付款",
        "支付成功", "已支付", "您已支付",
        "扣款", "扣费", "已扣款",
        "消费",
        "支出", "的支出",  // 支付宝："你有一笔 X 元的支出"
    )

    // 优惠红包、券和活动额度不是账户中的现金。模糊通知不自动记账。
    private val PROMOTION_KEYWORDS = listOf(
        "送你", "送您", "赠送", "领取", "领红包", "抢红包", "优惠券", "红包券",
        "满减", "立减", "券包", "限时优惠", "活动红包", "购物红包", "闪购红包",
    )
    private val FAILED_KEYWORDS = listOf("付款失败", "支付失败", "交易关闭", "订单取消", "未付款")

    // "向 商户名 付款" 模式（区分于"向你/您 付款"）= 支出
    private val PAY_TO_MERCHANT_REGEX = Regex("""向\s*(?!你|您)(\S+?)\s*付款""")

    // 商户提取模式（按优先级试，命中即用）
    private val MERCHANT_PATTERNS = listOf(
        Regex("""向\s*(?!你|您)(\S+?)\s*付款"""),  // 微信：向 麦当劳 付款（排除"向你/您"）
        Regex("""付款给\s*(\S+?)(?:[\s，,。.！!]|$)"""),
        Regex("""付给\s*(\S+?)(?:[\s，,。.！!]|$)"""),
        Regex("""收到\s*(\S+?)\s*的转账"""),     // 微信：收到 张三 的转账
        Regex("""来自\s*(\S+?)(?:[\s，,。.！!]|$)"""),
        Regex("""转账给\s*(\S+?)(?:[\s，,。.！!]|$)"""),
        // 支付宝付款成功后面跟商户："付款成功￥3.00 (AB)湖北武汉..."
        Regex("""[¥￥][0-9.]+\s*[-—]?\s*(\S{2,})"""),
    )

    fun parse(
        packageName: String,
        title: String?,
        text: String?,
        timestampMs: Long,
    ): TransactionEntity? {
        if (packageName == BankAppNotificationParser.CCB_PACKAGE)
            return BankAppNotificationParser.parse(title, text, timestampMs)
        val source = when (packageName) {
            PKG_ALIPAY -> TransactionSource.ALIPAY
            PKG_WECHAT -> TransactionSource.WECHAT
            else -> return null
        }
        return parseCore(
            full = "${title.orEmpty()} ${text.orEmpty()}".trim(),
            source = source,
            timestampMs = timestampMs,
        )
    }

    private fun parseCore(
        full: String,
        source: TransactionSource,
        timestampMs: Long,
    ): TransactionEntity? {
        if (full.isBlank()) return null
        if (FAILED_KEYWORDS.any(full::contains)) return null
        if (PROMOTION_KEYWORDS.any(full::contains)) return null
        // 单说“收到红包”“返现”“到账”可能指优惠资格，不能证明钱已经入账。
        if ((full.contains("红包") || full.contains("返现")) &&
            INCOME_KEYWORDS.none(full::contains) && EXPENSE_KEYWORDS.none(full::contains)) return null

        // 1. 提金额
        val amountCents = extractAmountCents(full) ?: return null

        // 2. 判收支方向。优先级：
        //    a. INCOME 关键词（包括"向你付款"这种明确信号）
        //    b. EXPENSE 关键词
        //    c. "向 商户 付款"模式（排除"向你/您"）
        //    d. 没有明确交易动作就跳过
        val type = when {
            INCOME_KEYWORDS.any { full.contains(it) } -> TransactionType.INCOME
            EXPENSE_KEYWORDS.any { full.contains(it) } -> TransactionType.EXPENSE
            PAY_TO_MERCHANT_REGEX.containsMatchIn(full) -> TransactionType.EXPENSE
            else -> return null
        }

        // 3. 提商户名 + 自动分类
        val merchant = extractMerchant(full)
        val category = CategoryClassifier.classify(merchant, full)

        return TransactionEntity(
            amountCents = amountCents,
            type = type.name,
            category = category.name,
            merchant = merchant,
            note = "[自动抓取] " + full.take(80),
            source = source.name,
            account = if (source == TransactionSource.ALIPAY) "支付宝" else "微信",
            timestamp = timestampMs,
        )
    }

    private fun extractAmountCents(text: String): Long? {
        val value = AMOUNT_REGEX_SYMBOL.find(text)?.groupValues?.get(1)
            ?: AMOUNT_REGEX_YUAN.find(text)?.groupValues?.get(1)
            ?: return null
        return runCatching {
            val cents = BigDecimal(value).movePointRight(2).setScale(0).longValueExact()
            cents.takeIf { it > 0 }
        }.getOrNull()
    }

    private fun extractMerchant(text: String): String {
        for (pattern in MERCHANT_PATTERNS) {
            val m = pattern.find(text)
            if (m != null) {
                val merchant = m.groupValues[1].trim()
                if (merchant.length in 2..40 && !merchant.matches(Regex("""[0-9.]+"""))) {
                    return merchant
                }
            }
        }
        return ""
    }
}

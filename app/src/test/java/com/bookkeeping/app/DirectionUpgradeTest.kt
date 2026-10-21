package com.bookkeeping.app

import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.repository.directionUpgrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectionUpgradeTest {
    @Test fun reimportAddsOnlyMissingDirection() {
        val old = TransactionEntity(id = 7, amountCents = 28331, type = "TRANSFER",
            category = "TRANSFER", merchant = "旧记录", source = "BANK_PDF", timestamp = 1L,
            importKey = "same-row")
        val reread = old.copy(id = 0, bankFlow = "OUT", merchant = "新解析文本")
        val updated = directionUpgrade(old, reread)!!
        assertEquals(old.id, updated.id)
        assertEquals(old.amountCents, updated.amountCents)
        assertEquals(old.merchant, updated.merchant)
        assertEquals("OUT", updated.bankFlow)
        assertNull(directionUpgrade(updated, reread))
        assertNull(directionUpgrade(old, reread.copy(importKey = "different-row")))
    }
}

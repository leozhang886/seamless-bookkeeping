package com.bookkeeping.app

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createComposeRule
import com.bookkeeping.app.ui.screen.transactions.AddTransactionDialog
import com.bookkeeping.app.ui.theme.BookkeepingAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UiFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun amountEntrySavesExactCentsAndDisablesRepeatedSubmit() {
        var savedAmount = 0L
        var saves = 0
        compose.setContent {
            BookkeepingAppTheme {
                AddTransactionDialog(accounts = emptyList(), onDismiss = {},
                    onConfirm = { amount, _, _, _, _, _, _, _ ->
                        savedAmount = amount
                        saves++
                    })
            }
        }
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("12.34")
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("保存").assertIsNotEnabled()
        assertEquals(1_234L, savedAmount)
        assertEquals(1, saves)
    }
}

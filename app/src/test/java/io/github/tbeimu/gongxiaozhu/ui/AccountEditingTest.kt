package io.github.tbeimu.gongxiaozhu.ui

import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import org.junit.Assert.*
import org.junit.Test

class AccountEditingTest {
    private val original = AccountDraft("fake-existing-id", "模拟备注", "demo-student", "demo-password")

    @Test fun unchangedExistingDraftAndRevertedEditsCannotSave() {
        assertFalse(accountDraftChanged(original, original.copy()))
        assertFalse(accountDraftCanSave(original, original.copy()))
        val changed = original.copy(label = "另一备注")
        assertTrue(accountDraftChanged(original, changed))
        assertTrue(accountDraftCanSave(original, changed))
        val reverted = changed.copy(label = original.label)
        assertFalse(accountDraftChanged(original, reverted))
        assertFalse(accountDraftCanSave(original, reverted))
    }

    @Test fun labelAndUsernameOuterWhitespaceDoesNotCreateAnEdit() {
        val padded = original.copy(label = "  模拟备注  ", username = "  demo-student  ")
        assertFalse(accountDraftChanged(original, padded))
        assertFalse(accountDraftCanSave(original, padded))
    }

    @Test fun clearingAnExistingLabelIsARealChange() {
        for (label in listOf("", "   ")) {
            val cleared = original.copy(label = label)
            assertTrue(accountDraftChanged(original, cleared))
            assertTrue(accountDraftCanSave(original, cleared))
        }
    }

    @Test fun passwordWhitespaceIsSignificantAndNeverTrimmedForChangeDetection() {
        for (password in listOf(" demo-password", "demo-password ", " demo-password ", " ")) {
            val changed = original.copy(password = password)
            assertTrue(accountDraftChanged(original, changed))
            assertTrue(accountDraftCanSave(original, changed))
        }
        assertFalse(accountDraftCanSave(original, original.copy(password = "")))
    }

    @Test fun saveRequiresMatchingIdentityAndCompleteCredentialsForNewDrafts() {
        assertFalse(accountDraftCanSave(original, original.copy(id = "another-id", label = "另一备注")))
        assertFalse(accountDraftCanSave(original, original.copy(username = "   ")))
        val empty = AccountDraft(label = "", username = "", password = "")
        assertFalse(accountDraftCanSave(empty, empty))
        assertFalse(accountDraftCanSave(empty, empty.copy(username = "demo-new-student")))
        assertTrue(accountDraftCanSave(empty, empty.copy(username = "demo-new-student", password = "demo-new-password")))
    }
}

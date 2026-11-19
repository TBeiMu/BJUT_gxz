package io.github.tbeimu.gongxiaozhu.ui

import io.github.tbeimu.gongxiaozhu.data.AccountDraft

/** Matches account storage normalization; clearing an existing label remains a real edit. */
internal fun accountDraftChanged(original: AccountDraft, draft: AccountDraft): Boolean =
    original.label.trim() != draft.label.trim() || original.username.trim() != draft.username.trim() ||
        original.password != draft.password

internal fun accountDraftCanSave(original: AccountDraft, draft: AccountDraft): Boolean =
    original.id == draft.id && draft.username.isNotBlank() && draft.password.isNotEmpty() &&
        (original.id == null || accountDraftChanged(original, draft))

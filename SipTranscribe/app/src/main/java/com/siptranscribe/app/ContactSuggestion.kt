package com.siptranscribe.app

/**
 * A name that the conversation analyser inferred for a caller, queued for
 * caregiver review. Never surfaced to the elderly user — only the
 * caregiver-facing [ContactSuggestionsActivity] lists these.
 *
 * `callCount` / `totalDurationSeconds` accumulate across multiple calls
 * from the same number, so the caregiver can see e.g. "this number has
 * called 3 times claiming to be Maria, total 8 min" before deciding.
 *
 * Once status is [Status.DISMISSED] the suggestion stays sticky — we
 * won't re-surface that name for that number unless the caregiver
 * explicitly clears it from the store.
 */
data class ContactSuggestion(
    val phoneNumber: String,
    val suggestedName: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val callCount: Int,
    val totalDurationSeconds: Int,
    val status: Status
) {
    enum class Status { PENDING, ACCEPTED, DISMISSED }
}

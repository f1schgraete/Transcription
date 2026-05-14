package com.siptranscribe.app

/**
 * Persisted-after-the-call payload that belongs to a [CallRecord].
 *
 * Linked to the record by `callId`. The transcript is kept as a list of
 * (label, text) tuples — same shape `CallActivity` renders during a call —
 * so a future "replay" UI can render speaker-coloured paragraphs without
 * having to re-parse a flat string.
 *
 * Stored encrypted-at-rest by [CallArchiveStore]; never written in clear.
 */
data class CallArchive(
    val callId: Long,
    val transcriptTurns: List<TranscriptTurn>,
    val summaryText: String?
) {
    data class TranscriptTurn(val speakerLabel: String, val text: String)
}

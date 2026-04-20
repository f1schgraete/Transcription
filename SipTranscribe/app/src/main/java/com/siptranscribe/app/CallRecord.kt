package com.siptranscribe.app

data class CallRecord(
    val id: Long,
    val direction: Direction,
    val callerName: String,
    val callerNumber: String,
    val startTime: Long,
    val durationSeconds: Int,
    val answered: Boolean
) {
    enum class Direction { INCOMING, OUTGOING }
}

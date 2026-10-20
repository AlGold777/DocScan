package app.docscan.android

/** Receipt uses elapsedRealtimeNanos; sensor time has no assumed relationship to it. */
enum class DocumentSensorClock { REALTIME, UNKNOWN }

data class DocumentFrameTiming(
    val sensorTimestampNs: Long?,
    val receivedTimestampNs: Long,
    val sensorClock: DocumentSensorClock = DocumentSensorClock.UNKNOWN,
) {
    init { require(receivedTimestampNs >= 0) }

    fun sensorAgeNs(nowElapsedRealtimeNs: Long): Long? {
        val sensor = sensorTimestampNs ?: return null
        if (sensorClock != DocumentSensorClock.REALTIME || sensor < 0 || nowElapsedRealtimeNs < sensor) return null
        return nowElapsedRealtimeNs - sensor
    }

    fun processingAgeNs(nowElapsedRealtimeNs: Long): Long? =
        if (nowElapsedRealtimeNs >= receivedTimestampNs) nowElapsedRealtimeNs - receivedTimestampNs else null
}

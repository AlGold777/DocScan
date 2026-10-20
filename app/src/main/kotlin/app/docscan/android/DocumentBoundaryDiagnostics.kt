package app.docscan.android

import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import org.json.JSONArray

/** Explicit debug opt-in only. No pixels; bounded, nonblocking producer; all disk work on one writer. */
class DocumentBoundaryDiagnostics(private val output:File) : AutoCloseable {
    private sealed interface Event
    private val queue=ArrayBlockingQueue<Event>(128)
    private val dropped=AtomicLong()
    private val writer=Executors.newSingleThreadExecutor()
    @Volatile private var running=true
    @Volatile var writeFailed=false
        private set
    init {
        writer.execute {
            try {
                output.parentFile?.mkdirs()
                output.bufferedWriter().use { out ->
                    out.appendLine("{\"schemaVersion\":1,\"event\":\"start\",\"sensorMapping\":\"UNKNOWN\",\"presentTimestampNs\":null}")
                    while(running || queue.isNotEmpty()) {
                        val event=queue.poll(100,TimeUnit.MILLISECONDS) ?: continue
                        out.appendLine(when(event) {
                            is Snapshot -> serialize(event)
                            is Marker -> JSONObject().put("event",event.phase)
                                .put("publicationId",event.publicationId).put("generationId",event.generationId)
                                .put("timestampNs",event.timestampNs)
                                .put("screenQuad",event.screenPoints?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
                                .put("presentTimestampNs",JSONObject.NULL).toString()
                        })
                    }
                    out.appendLine(JSONObject().put("event","end").put("droppedEvents",dropped.get())
                        .put("writeFailed",false).toString())
                }
            } catch(_:Exception) { writeFailed=true; dropped.addAndGet(queue.size.toLong()); queue.clear() }
        }
    }
    private data class Snapshot(val identity:BoundaryFrameId,val timing:DocumentFrameTiming,
        val result:DocumentBoundaryResult,val state:LiveDetectionState):Event
    private data class Marker(val phase:String,val publicationId:Long,val generationId:Long,
        val timestampNs:Long,val screenPoints:FloatArray?):Event

    /** UI submission/draw are measured separately. Neither is display presentation. */
    fun recordMarker(phase:String,publicationId:Long,generationId:Long,timestampNs:Long,
                     screenPoints:FloatArray?=null) {
        require(phase in setOf("UI_APPLY", "DRAW"))
        require(timestampNs >= 0)
        require(screenPoints == null || screenPoints.size == 8 && screenPoints.all { it.isFinite() })
        if(!running || writeFailed) { dropped.incrementAndGet(); return }
        if(!queue.offer(Marker(phase,publicationId,generationId,timestampNs,screenPoints?.clone())))
            dropped.incrementAndGet()
    }
    fun record(input:DocumentBoundaryInput,result:DocumentBoundaryResult,state:LiveDetectionState) {
        if(!running || writeFailed) { dropped.incrementAndGet(); return }
        val snapshot=Snapshot(input.identity,input.timing,result,
            state.copy(quadInBufferPixels=state.quadInBufferPixels?.clone()))
        if(!queue.offer(snapshot)) dropped.incrementAndGet()
    }
    private fun serialize(snapshot:Snapshot):String {
        val identity=snapshot.identity; val timing=snapshot.timing
        val result=snapshot.result; val state=snapshot.state
        val json=JSONObject().put("event","result").put("frameId",identity.frameId)
            .put("generationId",identity.generationId).put("geometryId",identity.geometryId)
            .put("sensorNs",timing.sensorTimestampNs ?: JSONObject.NULL)
            .put("receivedNs",timing.receivedTimestampNs)
            .put("preparedNs",timing.preparedTimestampNs ?: JSONObject.NULL)
            .put("processedMs",if(state.publicationId > 0 && state.generationId == identity.generationId)
                state.processedTimestampMonotonicMs else JSONObject.NULL)
            .put("detectorStartNs",result.detectorStartedNs).put("detectorEndNs",result.detectorFinishedNs)
            .put("detector",result.detectorId).put("config",result.configVersion)
            .put("outcome",result.outcome.name).put("reason",result.reason ?: JSONObject.NULL)
            .put("status",state.status.name).put("publicationId",state.publicationId)
            .put("lastDetectionMs",if(state.quadInBufferPixels != null)
                state.lastDetectionTimestampMonotonicMs else JSONObject.NULL)
            .put("geometryMs",if(state.quadInBufferPixels != null)
                state.frameTimestampMonotonicMs else JSONObject.NULL)
            .put("tracked",state.isTrackedGeometry).put("trackId",state.trackId ?: JSONObject.NULL)
            .put("deadlineMs",state.visualDeadlineMonotonicMs)
            .put("displayBufferQuad",state.quadInBufferPixels?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
            .put("candidates",JSONArray(result.candidates.map { h -> JSONObject()
                .put("candidateId",h.candidateId).put("rawScore",h.rawScore)
                .put("presence",h.documentPresence ?: JSONObject.NULL)
                .put("rawQuad",JSONArray(h.rawQuad.toFloatArray().toList()))
                .put("refinedQuad",JSONArray(h.quad.toFloatArray().toList()))
                .put("visibility",h.visibility.name).put("refinement",h.refinement.name) }))
        return json.toString()
    }
    val droppedEvents:Long get()=dropped.get()
    override fun close() { running=false; writer.shutdown() }
}

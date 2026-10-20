package app.docscan.android

import android.os.SystemClock

/** One analyzer owner. Detector execution and tracker mutation are serialized, never queued independently. */
class DocumentBoundaryPipeline(
    private val coordinator:DocumentLiveDetectionCoordinator,
    private val detector:DocumentBoundaryDetector=ClassicalDocumentBoundaryDetector(),
    private val trackingEnabled:Boolean=false,
    private val diagnostics:DocumentBoundaryDiagnostics?=null,
    private val frameArchive:DocumentFrameArchive?=null,
    private val clockMs:()->Long = { SystemClock.elapsedRealtime() },
) : AutoCloseable {
    private val tracker=DocumentBoundaryTracker()
    private var generation:Long?=null
    private var lastDetectorMs:Long?=null
    private var closed=false
    fun process(input:DocumentBoundaryInput):LiveDetectionState {
        check(!closed)
        frameArchive?.record(input)
        val id=input.identity
        val received=input.timing.receivedTimestampNs/1_000_000
        if(generation!=id.generationId) { tracker.reset(); lastDetectorMs=null; generation=id.generationId }
        val runDetector=lastDetectorMs?.let { received-it>=100 } ?: true
        val gray=if(trackingEnabled) tracker.gray(input.rgba) else null
        try {
            val flow=if(gray!=null) tracker.track(gray,input.spec,received) else null
            if(!runDetector) {
                return if(flow!=null) coordinator.onFrameTracked(id.generationId,received,flow.quad.toFloatArray().also {
                    for(i in it.indices) it[i]+=0.5f
                },input.spec)
                    else coordinator.getPublishedState()
            }
            lastDetectorMs=received
            val result=detector.detect(input)
            val candidates=DocumentBoundarySelection.visible(result,input.spec.cropRect)
            val candidate=if(result.outcome==BoundaryOutcome.FOUND) candidates.firstOrNull() else null
            val state=when {
                result.outcome==BoundaryOutcome.ERROR -> {
                    tracker.reset()
                    coordinator.onFrameUnavailable(id.generationId,received,LiveDetectionStatus.ERROR)
                }
                result.outcome==BoundaryOutcome.PARTIAL -> {
                    tracker.reset()
                    coordinator.onFrameUnavailable(id.generationId,received,LiveDetectionStatus.PARTIAL)
                }
                candidate!=null -> {
                    val points=candidate.quad.toFloatArray()
                    // Native detectors describe pixel centres; CameraX matrices take continuous
                    // buffer-boundary coordinates. Convert exactly once at this boundary.
                    for(i in points.indices) points[i]+=0.5f
                    val accepted=coordinator.onFrameDetected(id.generationId,received,points,candidate.rawScore,
                        input.spec,processedTimestampMonotonicMs=clockMs())
                    if(gray!=null) {
                        // A first B candidate held behind confirmed A must not seed flow that
                        // would silently replace A on the very next non-detector frame.
                        if(accepted.status==LiveDetectionStatus.CONFIRMED &&
                            accepted.frameTimestampMonotonicMs==received)
                            tracker.seed(gray,candidate.quad,input.spec,received)
                        else tracker.reset()
                    }
                    accepted
                }
                flow!=null -> coordinator.onFrameTracked(id.generationId,received,flow.quad.toFloatArray().also {
                    for(i in it.indices) it[i]+=0.5f
                },input.spec)
                else -> coordinator.onFrameMissing(id.generationId,received)
            }
            diagnostics?.record(input,result,state)
            return state
        } finally { gray?.release() }
    }
    override fun close() { closed=true; tracker.close(); detector.close(); diagnostics?.close(); frameArchive?.close() }
}

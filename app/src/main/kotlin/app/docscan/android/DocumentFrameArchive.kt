package app.docscan.android

import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.imgcodecs.Imgcodecs
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/** Local explicit debug recording: <=450 bounded images, queue two, no borrowed frame outlives record(). */
class DocumentFrameArchive(private val directory:File) : AutoCloseable {
    private data class Frame(val identity:BoundaryFrameId,val timing:DocumentFrameTiming,
        val spec:AnalysisBufferSpec,val owned:Mat,val transform:DoubleArray)
    private val queue=ArrayBlockingQueue<Frame>(2)
    private val writer=Executors.newSingleThreadExecutor()
    private val dropped=AtomicLong()
    private var accepted=0
    @Volatile private var running=true
    @Volatile private var failed=false
    init { writer.execute {
        try {
            directory.mkdirs()
            while(running || queue.isNotEmpty()) {
                val frame=queue.poll(100,TimeUnit.MILLISECONDS) ?: continue
                val bgr=Mat()
                try {
                    val name="frame-${frame.identity.frameId}"
                    Imgproc.cvtColor(frame.owned,bgr,Imgproc.COLOR_RGBA2BGR)
                    check(Imgcodecs.imwrite(File(directory,"$name.jpg").absolutePath,bgr))
                    File(directory,"$name.json").writeText(JSONObject().put("frameId",frame.identity.frameId)
                        .put("generationId",frame.identity.generationId).put("geometryId",frame.identity.geometryId)
                        .put("receivedNs",frame.timing.receivedTimestampNs)
                        .put("sensorNs",frame.timing.sensorTimestampNs ?: JSONObject.NULL)
                        .put("sensorClock",frame.timing.sensorClock.name).put("originalWidth",frame.spec.bufferWidth)
                        .put("originalHeight",frame.spec.bufferHeight)
                        .put("cropRect",JSONArray(listOf(frame.spec.cropRect.left,frame.spec.cropRect.top,
                            frame.spec.cropRect.right,frame.spec.cropRect.bottom)))
                        .put("rotationDegrees",frame.spec.rotationDegrees)
                        .put("originalCentresToImageCentres",JSONArray(frame.transform.toList())).toString()+"\n")
                } finally { bgr.release(); frame.owned.release() }
            }
            File(directory,"recording-summary.json").writeText(JSONObject().put("acceptedFrames",accepted)
                .put("droppedFrames",dropped.get()).put("limitFrames",450).put("source","DEBUG_ANALYSIS")
                .put("presentationTimeAvailable",false).toString()+"\n")
        } catch(_:Exception) { failed=true }
        finally { while(true) { val frame=queue.poll() ?: break; frame.owned.release() } }
    } }
    fun record(input:DocumentBoundaryInput) {
        if(!running || failed || accepted>=450) return
        if(queue.remainingCapacity()==0) { dropped.incrementAndGet(); return }
        val owned=Mat()
        try {
            val scale=min(1.0,720.0/maxOf(input.rgba.cols(),input.rgba.rows()))
            Imgproc.resize(input.rgba,owned,Size(input.rgba.cols()*scale,input.rgba.rows()*scale),0.0,0.0,Imgproc.INTER_AREA)
            val transform=BoundaryImageTransform.resizeCentres(input.spec.bufferWidth,input.spec.bufferHeight,
                owned.cols(),owned.rows()).coefficients()
            if(queue.offer(Frame(input.identity,input.timing,input.spec,owned,transform))) { accepted++; return }
            dropped.incrementAndGet()
        } catch(failure:Throwable) { owned.release(); throw failure }
        owned.release()
    }
    override fun close() { running=false; writer.shutdown() }
}

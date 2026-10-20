package app.docscan.android

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import android.os.SystemClock
import kotlin.math.*

/** Original remains the default; A is a separately versioned bounded experiment. */
class ClassicalDocumentBoundaryDetector(private val enhanced: Boolean = false) : DocumentBoundaryDetector {
    override val detectorId = if(enhanced) "opencv-A" else "opencv-original"
    override val configVersion = if(enhanced) "A-normal-huber-1" else "original-0.4.279"
    private var closed = false

    override fun detect(input: DocumentBoundaryInput): DocumentBoundaryResult {
        check(!closed)
        val started=SystemClock.elapsedRealtimeNanos()
        fun result(outcome:BoundaryOutcome,candidates:List<DocumentHypothesis>,reason:String?=null)=
            DocumentBoundaryResult(input.identity,detectorId,configVersion,outcome,candidates,started,
                SystemClock.elapsedRealtimeNanos(),reason)
        DocumentCropProcessor.ensureOpenCvLoaded()
        val r=input.spec.cropRect
        val roi=Mat(input.rgba,Rect(r.left,r.top,r.width,r.height))
        try {
            if(!enhanced) {
                val detection=DocumentCropProcessor.detectCornersFromRgba(roi, rejectClipped=true)
                    ?: return result(BoundaryOutcome.NO_DOCUMENT,emptyList())
                val p=detection.bufferPoints.clone()
                for(i in 0..3) { p[i*2]+=r.left; p[i*2+1]+=r.top }
                val q=try { BoundaryQuad.from(p) } catch(_:IllegalArgumentException) {
                    return result(BoundaryOutcome.NO_DOCUMENT,emptyList(),"INVALID_ORIGINAL_QUAD")
                }
                return result(BoundaryOutcome.FOUND,listOf(DocumentHypothesis(0,q,detection.score,
                    visibility=BoundaryVisibility.FULL,source=detectorId)))
            }
            val scale=min(1.0,720.0/max(roi.cols(),roi.rows()))
            val work=Mat(); val gray=Mat(); val blur=Mat(); val edges=Mat(); val paper=Mat()
            try {
                Imgproc.resize(roi,work,Size(roi.cols()*scale,roi.rows()*scale),0.0,0.0,Imgproc.INTER_AREA)
                Imgproc.cvtColor(work,gray,Imgproc.COLOR_RGBA2GRAY)
                Imgproc.GaussianBlur(gray,blur,Size(5.0,5.0),0.0)
                Imgproc.Canny(blur,edges,35.0,105.0,3,true)
                DocumentCropProcessor.buildPaperMask(work,paper)
                val raw=ArrayList<List<Point>>()
                for(mask in listOf(edges,paper)) {
                    val copy=mask.clone(); val hierarchy=Mat(); val contours=ArrayList<MatOfPoint>()
                    try {
                        Imgproc.findContours(copy,contours,hierarchy,Imgproc.RETR_LIST,Imgproc.CHAIN_APPROX_SIMPLE)
                        for(contour in contours.sortedByDescending { Imgproc.contourArea(it) }.take(20)) {
                            if(Imgproc.contourArea(contour)<gray.total()*0.015) continue
                            val hull=DocumentCropProcessor.hullOf(contour) ?: continue
                            try {
                                val q=DocumentCropProcessor.approxQuad(hull,Imgproc.arcLength(hull,true)) ?: continue
                                DocumentCropProcessor.orderCorners(q)?.let { raw.add(it) }
                            } finally { hull.release() }
                        }
                    } finally { copy.release(); hierarchy.release(); contours.forEach { it.release() } }
                }
                raw.addAll(lineCandidates(edges))
                val seen=HashSet<List<Int>>()
                val hypotheses=ArrayList<DocumentHypothesis>()
                // Cost is bounded independently of contour/line complexity.
                for(points in raw.take(160)) {
                    val key=points.flatMap { listOf((it.x/3).roundToInt(),(it.y/3).roundToInt()) }
                    if(!seen.add(key)) continue
                    val q=try { BoundaryQuad(BoundaryPoint(points[0].x,points[0].y),
                        BoundaryPoint(points[1].x,points[1].y),BoundaryPoint(points[2].x,points[2].y),
                        BoundaryPoint(points[3].x,points[3].y)) } catch(_:IllegalArgumentException) { continue }
                    if(!q.inside(BufferRect(0,0,gray.cols(),gray.rows()))) continue
                    val area=abs(points.indices.sumOf { i -> points[i].x*points[(i+1)%4].y-points[(i+1)%4].x*points[i].y })/2
                    if(area<gray.total()*0.015 || area>gray.total()*0.99) continue
                    val fit=DocumentBoundaryRefiner.refine(gray,q)
                    val cover=fit.edges.map { it.coverage ?: 0.0 }
                    if(cover.min()<0.40 || fit.edges.any { (it.occupiedBins ?: 0)<5 }) continue
                    val score=0.50*cover.min()+0.40*cover.average()+0.10*min(1.0,area/(gray.total()*0.2))
                    val inverseResize=BoundaryImageTransform.resizeCentres(roi.cols(),roi.rows(),work.cols(),work.rows()).inverse()
                    val full=fit.quad.points.map { inverseResize.map(it).let { p -> BoundaryPoint(r.left+p.x,r.top+p.y) } }
                    val fullQuad=BoundaryQuad(full[0],full[1],full[2],full[3])
                    val normalized=CameraGeometryTransform.analysisBufferQuadToNormalizedViewPort(fullQuad.toFloatArray(),input.spec)
                    if(hypotheses.any { QuadGeometryUtils.checkAgreement(
                        CameraGeometryTransform.analysisBufferQuadToNormalizedViewPort(it.quad.toFloatArray(),input.spec),normalized,
                        maxRelativeShiftTolerance=0.005,minIouTolerance=0.98).agrees }) continue
                    val originalFull=q.points.map { inverseResize.map(it).let { p -> BoundaryPoint(r.left+p.x,r.top+p.y) } }
                    val rawQuad=BoundaryQuad(originalFull[0],originalFull[1],originalFull[2],originalFull[3])
                    val evidence=fit.edges.map { it.copy(residualPx=it.residualPx?.div(scale)) }
                    hypotheses.add(DocumentHypothesis(hypotheses.size,fullQuad,score,edges=evidence,rawQuad=rawQuad,
                        visibility=BoundaryVisibility.FULL,source=detectorId,refinement=fit.status))
                }
                val sorted=hypotheses.sortedByDescending { it.rawScore }.take(8)
                if(sorted.isEmpty()) return result(BoundaryOutcome.NO_DOCUMENT,emptyList(),"INSUFFICIENT_SIDE_SUPPORT")
                val margin=sorted.getOrNull(1)?.let { sorted[0].rawScore-it.rawScore }
                val ranked=sorted.mapIndexed { index,h -> h.copy(candidateId=index,rankMargin=if(index==0) margin else null) }
                return result(if(margin!=null && margin<0.04) BoundaryOutcome.AMBIGUOUS else BoundaryOutcome.FOUND,ranked)
            } finally { work.release(); gray.release(); blur.release(); edges.release(); paper.release() }
        } catch(failure:Exception) {
            return result(BoundaryOutcome.ERROR,emptyList(),failure.javaClass.simpleName)
        } finally { roi.release() }
    }

    /** Direction-independent line-pair combinations, capped before expensive refinement. */
    private fun lineCandidates(edges:Mat):List<List<Point>> {
        data class Line(val a:Double,val b:Double,val c:Double,val length:Double)
        val lines=Mat()
        try {
            Imgproc.HoughLinesP(edges,lines,1.0,Math.PI/180,35,30.0,10.0)
            val segments=(0 until min(lines.rows(),80)).mapNotNull { i ->
                val p=lines.get(i,0) ?: return@mapNotNull null
                val dx=p[2]-p[0]; val dy=p[3]-p[1]; val length=hypot(dx,dy)
                if(length<30) null else Line(-dy/length,dx/length,(dy*p[0]-dx*p[1])/length,length)
            }.sortedByDescending { it.length }.take(12)
            fun intersect(a:Line,b:Line):Point? {
                val det=a.a*b.b-b.a*a.b
                if(abs(det)<0.25) return null
                return Point((a.b*b.c-b.b*a.c)/det,(b.a*a.c-a.a*b.c)/det)
            }
            val pairs=segments.indices.flatMap { i -> (i+1 until segments.size).map { j -> i to j } }
                .filter { (i,j) -> abs(segments[i].a*segments[j].a+segments[i].b*segments[j].b)>0.85 }
                .take(24)
            val output=ArrayList<List<Point>>()
            for(u in pairs.indices) for(v in u+1 until pairs.size) {
                val (a,b)=pairs[u]; val (c,d)=pairs[v]
                if(setOf(a,b,c,d).size!=4) continue
                val p=listOf(intersect(segments[a],segments[c]),intersect(segments[a],segments[d]),
                    intersect(segments[b],segments[d]),intersect(segments[b],segments[c]))
                if(p.any { it==null }) continue
                val q=DocumentCropProcessor.orderCorners(p.filterNotNull().toTypedArray()) ?: continue
                output.add(q)
                if(output.size==80) return output
            }
            return output
        } finally { lines.release() }
    }
    override fun close() { closed=true }
}

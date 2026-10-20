package app.docscan.android

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import org.opencv.calib3d.Calib3d
import kotlin.math.*

/** Single analyzer owner; at most one owned previous gray frame, no retained CameraX buffers. */
class DocumentBoundaryTracker : AutoCloseable {
    data class Result(val quad:BoundaryQuad,val inliers:Int,val coverage:Double,val forwardBackwardPx:Double)
    private var previous:Mat?=null
    private var points:List<Point> = emptyList()
    private var quad:BoundaryQuad?=null
    private var spec:AnalysisBufferSpec?=null
    private var timestampMs=0L
    private var resize=BoundaryImageTransform.identity()

    fun gray(rgba:Mat):Mat {
        val resized=Mat(); val gray=Mat()
        try {
            val scale=min(1.0,720.0/max(rgba.cols(),rgba.rows()))
            Imgproc.resize(rgba,resized,Size(rgba.cols()*scale,rgba.rows()*scale),0.0,0.0,Imgproc.INTER_AREA)
            Imgproc.cvtColor(resized,gray,Imgproc.COLOR_RGBA2GRAY)
            return gray
        } catch(failure:Throwable) { gray.release(); throw failure }
        finally { resized.release() }
    }
    fun seed(gray:Mat,bufferQuad:BoundaryQuad,geometry:AnalysisBufferSpec,nowMs:Long) {
        reset()
        resize=BoundaryImageTransform.resizeCentres(geometry.bufferWidth,geometry.bufferHeight,gray.cols(),gray.rows())
        val p=bufferQuad.points.map(resize::map)
        val q=BoundaryQuad(p[0],p[1],p[2],p[3]); val mask=Mat.zeros(gray.size(),CvType.CV_8UC1)
        val polygon=MatOfPoint(*p.map { Point(it.x,it.y) }.toTypedArray()); val corners=MatOfPoint()
        val kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,Size(9.0,9.0))
        try {
            Imgproc.fillConvexPoly(mask,polygon,Scalar(255.0))
            Imgproc.erode(mask,mask,kernel)
            Imgproc.goodFeaturesToTrack(gray,corners,80,0.01,7.0,mask,3,false,0.04)
            val candidates=corners.toList()
            if(candidates.size<12 || coverage(candidates,q)<0.15) return
            previous=gray.clone(); points=candidates; quad=q; spec=geometry; timestampMs=nowMs
        } finally { mask.release(); polygon.release(); corners.release(); kernel.release() }
    }
    fun track(gray:Mat,geometry:AnalysisBufferSpec,nowMs:Long):Result? {
        val old=previous ?: return null
        val oldQuad=quad ?: return null
        if(geometry!=spec || gray.size()!=old.size() || nowMs-timestampMs !in 1..199) { reset(); return null }
        val from=MatOfPoint2f(*points.toTypedArray()); val to=MatOfPoint2f(); val back=MatOfPoint2f()
        val status=MatOfByte(); val backwardStatus=MatOfByte(); val error=MatOfFloat(); val backwardError=MatOfFloat()
        val mask=Mat(); var homography:Mat?=null
        try {
            Video.calcOpticalFlowPyrLK(old,gray,from,to,status,error)
            Video.calcOpticalFlowPyrLK(gray,old,to,back,backwardStatus,backwardError)
            val next=to.toList(); val returned=back.toList(); val ok=status.toArray(); val bok=backwardStatus.toArray()
            val valid=points.indices.filter { i -> i<next.size && i<returned.size && i<ok.size && i<bok.size &&
                ok[i].toInt()!=0 && bok[i].toInt()!=0 &&
                hypot(returned[i].x-points[i].x,returned[i].y-points[i].y)<=1.5 &&
                next[i].x in 0.0..gray.cols().toDouble() && next[i].y in 0.0..gray.rows().toDouble() }
            if(valid.size<12 || valid.size.toDouble()/points.size<0.60) { reset(); return null }
            val source=MatOfPoint2f(*valid.map { points[it] }.toTypedArray())
            val target=MatOfPoint2f(*valid.map { next[it] }.toTypedArray())
            val h=try { Calib3d.findHomography(source,target,Calib3d.RANSAC,2.5,mask,1000,0.995) }
                finally { source.release(); target.release() }
            homography=h
            if(h.empty() || !Core.checkRange(h)) { reset(); return null }
            val inlierMask=ByteArray(valid.size); mask.get(0,0,inlierMask)
            val inliers=valid.indices.filter { inlierMask[it].toInt()!=0 }.map { valid[it] }
            val covered=coverage(inliers.map { points[it] },oldQuad)
            if(inliers.size<12 || inliers.size.toDouble()/valid.size<0.70 || covered<0.15) { reset(); return null }
            val originalCorners=MatOfPoint2f(*oldQuad.points.map { Point(it.x,it.y) }.toTypedArray())
            val moved=MatOfPoint2f()
            val proposed=try {
                Core.perspectiveTransform(originalCorners,moved,h)
                val p=moved.toArray()
                if(p.size!=4) { reset(); return null }
                BoundaryQuad(BoundaryPoint(p[0].x,p[0].y),BoundaryPoint(p[1].x,p[1].y),
                    BoundaryPoint(p[2].x,p[2].y),BoundaryPoint(p[3].x,p[3].y))
            } catch(_:IllegalArgumentException) { reset(); return null }
              finally { originalCorners.release(); moved.release() }
            if(!proposed.inside(BufferRect(0,0,gray.cols(),gray.rows()))) { reset(); return null }
            val ratio=area(proposed)/area(oldQuad)
            if(ratio !in 0.65..1.55) { reset(); return null }
            val reprojection=MatOfPoint2f(); val origin=MatOfPoint2f(*inliers.map { points[it] }.toTypedArray())
            val rms=try {
                Core.perspectiveTransform(origin,reprojection,h)
                sqrt(reprojection.toArray().mapIndexed { i,p ->
                    val actual=next[inliers[i]]; (p.x-actual.x).pow(2)+(p.y-actual.y).pow(2) }.average())
            } finally { reprojection.release(); origin.release() }
            if(!rms.isFinite() || rms>2.0) { reset(); return null }
            val fb=inliers.map { hypot(returned[it].x-points[it].x,returned[it].y-points[it].y) }.average()
            val buffer=proposed.points.map(resize.inverse()::map)
            val full=BoundaryQuad(buffer[0],buffer[1],buffer[2],buffer[3])
            if(!full.inside(geometry.cropRect)) { reset(); return null }
            old.release(); previous=gray.clone(); points=inliers.map { next[it] }; quad=proposed; timestampMs=nowMs
            return Result(full,inliers.size,covered,fb)
        } finally { from.release(); to.release(); back.release(); status.release(); backwardStatus.release()
            error.release(); backwardError.release(); mask.release(); homography?.release() }
    }
    private fun area(q:BoundaryQuad):Double = abs(q.points.indices.sumOf { i ->
        val a=q.points[i]; val b=q.points[(i+1)%4]; a.x*b.y-b.x*a.y })/2
    private fun coverage(p:List<Point>,q:BoundaryQuad):Double {
        if(p.size<3) return 0.0
        val cx=q.points.map { it.x }.average(); val cy=q.points.map { it.y }.average()
        if(p.map { (if(it.x>=cx)1 else 0)+(if(it.y>=cy)2 else 0) }.distinct().size<3) return 0.0
        val pointsMat=MatOfPoint(*p.toTypedArray()); val indices=MatOfInt()
        return try {
            Imgproc.convexHull(pointsMat,indices)
            val hull=MatOfPoint(*indices.toArray().map { p[it] }.toTypedArray())
            try { Imgproc.contourArea(hull)/area(q) } finally { hull.release() }
        } finally { pointsMat.release(); indices.release() }
    }
    fun reset() { previous?.release(); previous=null; points=emptyList(); quad=null; spec=null; timestampMs=0 }
    override fun close()=reset()
}

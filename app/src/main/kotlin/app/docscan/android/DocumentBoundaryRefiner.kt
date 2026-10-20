package app.docscan.android

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/** Bounded normal-gradient sampling and Huber fitting. Coordinates remain in the input gray image. */
object DocumentBoundaryRefiner {
    const val VERSION = "normal-huber-v1"
    private data class Fit(val a: Double, val b: Double, val c: Double, val evidence: BoundaryEdgeEvidence)
    data class Result(val quad: BoundaryQuad, val edges: List<BoundaryEdgeEvidence>, val status: BoundaryRefinement)

    fun refine(gray: Mat, quad: BoundaryQuad, radius: Int = 6): Result {
        require(gray.type() == CvType.CV_8UC1 && radius in 1..16)
        val width = gray.cols(); val height = gray.rows()
        val pixels = ByteArray(Math.multiplyExact(width,height)); gray.get(0,0,pixels)
        fun intensity(x: Int, y: Int): Int? = if (x in 0 until width && y in 0 until height)
            pixels[y*width+x].toInt() and 255 else null
        fun sample(p: List<BoundaryPoint>, fit: Boolean): List<Fit> = (0..3).map { side ->
            val a=p[side]; val b=p[(side+1)%4]; val length=hypot(b.x-a.x,b.y-a.y)
            val nx=-(b.y-a.y)/length; val ny=(b.x-a.x)/length
            val support=ArrayList<Point>(); val bins=HashSet<Int>()
            for (i in 0 until 64) {
                val t=0.05+0.90*i/63.0; val x=a.x+(b.x-a.x)*t; val y=a.y+(b.y-a.y)*t
                var best=11.0; var point: Point?=null
                for (offset in -(if(fit) radius else 2)..(if(fit) radius else 2)) {
                    val sx=x+nx*offset; val sy=y+ny*offset
                    val lo=intensity((sx-nx).roundToInt(),(sy-ny).roundToInt()) ?: continue
                    val hi=intensity((sx+nx).roundToInt(),(sy+ny).roundToInt()) ?: continue
                    val gradient=abs(hi-lo).toDouble()-abs(offset)*0.5
                    if(gradient>best) { best=gradient; point=Point(sx,sy) }
                }
                if(point!=null) { support.add(point); bins.add(i/8) }
            }
            val fallback=Fit(nx,ny,-nx*a.x-ny*a.y,
                BoundaryEdgeEvidence(support.size/64.0,bins.size,null))
            if (!fit || support.size < 24 || bins.size < 6) fallback else {
                val points=MatOfPoint2f(*support.toTypedArray()); val line=Mat()
                try {
                    Imgproc.fitLine(points,line,Imgproc.DIST_HUBER,0.0,0.01,0.01)
                    val vx=line.get(0,0)[0]; val vy=line.get(1,0)[0]
                    val x=line.get(2,0)[0]; val y=line.get(3,0)[0]
                    val la=-vy; val lb=vx; val lc=vy*x-vx*y
                    val residual=sqrt(support.sumOf { (la*it.x+lb*it.y+lc).pow(2) }/support.size)
                    if(!residual.isFinite() || residual>2.5) fallback else
                        Fit(la,lb,lc,BoundaryEdgeEvidence(support.size/64.0,bins.size,residual))
                } finally { points.release(); line.release() }
            }
        }
        val original=sample(quad.points,false)
        fun unchanged()=Result(quad,original.map { it.evidence },BoundaryRefinement.ORIGINAL)
        val lines=sample(quad.points,true)
        if(lines.any { it.evidence.residualPx == null }) return unchanged()
        val proposed=(0..3).map { i ->
            val a=lines[(i+3)%4]; val b=lines[i]; val det=a.a*b.b-b.a*a.b
            if(abs(det)<1e-6) return unchanged()
            BoundaryPoint((a.b*b.c-b.b*a.c)/det,(b.a*a.c-a.a*b.c)/det)
        }
        val candidate=try { BoundaryQuad(proposed[0],proposed[1],proposed[2],proposed[3]) }
            catch(_:IllegalArgumentException) { return unchanged() }
        val shortSide=quad.points.indices.minOf { i ->
            hypot(quad.points[i].x-quad.points[(i+1)%4].x,quad.points[i].y-quad.points[(i+1)%4].y)
        }
        val maxShift=min(radius*2.0,shortSide*0.08)
        if(!candidate.inside(BufferRect(0,0,width,height)) || proposed.indices.any { i ->
            hypot(proposed[i].x-quad.points[i].x,proposed[i].y-quad.points[i].y)>maxShift
        }) return unchanged()
        val after=sample(candidate.points,false).map { it.evidence }
        if(after.indices.any { (after[it].coverage ?: 0.0)+0.02 < (original[it].evidence.coverage ?: 0.0) })
            return unchanged()
        return Result(candidate,lines.mapIndexed { i, f -> f.evidence.copy(coverage=after[i].coverage,
            occupiedBins=after[i].occupiedBins) },BoundaryRefinement.REFINED)
    }
}

package app.docscan.android

import kotlin.math.abs
import kotlin.math.min

/** Immutable projective transform; no clamp. Factories state centre vs boundary conventions. */
class BoundaryImageTransform private constructor(private val m:DoubleArray) {
    init { require(m.size==9 && m.all { it.isFinite() }) }
    fun map(point:BoundaryPoint):BoundaryPoint {
        val w=m[6]*point.x+m[7]*point.y+m[8]
        require(w.isFinite() && abs(w)>1e-12)
        return BoundaryPoint((m[0]*point.x+m[1]*point.y+m[2])/w,
            (m[3]*point.x+m[4]*point.y+m[5])/w).also { require(it.x.isFinite() && it.y.isFinite()) }
    }
    fun then(next:BoundaryImageTransform):BoundaryImageTransform = BoundaryImageTransform(DoubleArray(9) { i ->
        val row=i/3; val col=i%3; (0..2).sumOf { k -> next.m[row*3+k]*m[k*3+col] }
    })
    fun inverse():BoundaryImageTransform {
        val a=m[0]; val b=m[1]; val c=m[2]; val d=m[3]; val e=m[4]; val f=m[5]; val g=m[6]; val h=m[7]; val i=m[8]
        val cof=doubleArrayOf(e*i-f*h,c*h-b*i,b*f-c*e,f*g-d*i,a*i-c*g,c*d-a*f,d*h-e*g,b*g-a*h,a*e-b*d)
        val det=a*cof[0]+b*cof[3]+c*cof[6]
        require(det.isFinite() && abs(det)>1e-12)
        return BoundaryImageTransform(DoubleArray(9) { cof[it]/det })
    }
    fun coefficients():DoubleArray=m.clone()
    companion object {
        fun identity()=BoundaryImageTransform(doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0))
        fun translation(x:Double,y:Double)=BoundaryImageTransform(doubleArrayOf(1.0,0.0,x,0.0,1.0,y,0.0,0.0,1.0))
        fun scale(x:Double,y:Double):BoundaryImageTransform {
            require(x>0 && y>0)
            return BoundaryImageTransform(doubleArrayOf(x,0.0,0.0,0.0,y,0.0,0.0,0.0,1.0))
        }
        /** Pixel-centre resize, inverse of OpenCV sampling (up to rasterization). */
        fun resizeCentres(width:Int,height:Int,newWidth:Int,newHeight:Int):BoundaryImageTransform {
            require(width>0 && height>0 && newWidth>0 && newHeight>0)
            return translation(0.5,0.5).then(scale(newWidth.toDouble()/width,newHeight.toDouble()/height))
                .then(translation(-0.5,-0.5))
        }
        /** Continuous boundary-space letterbox; pad returned by this transform must be removed on inverse. */
        fun letterbox(width:Int,height:Int,targetWidth:Int,targetHeight:Int):BoundaryImageTransform {
            require(width>0 && height>0 && targetWidth>0 && targetHeight>0)
            val s=min(targetWidth.toDouble()/width,targetHeight.toDouble()/height)
            return scale(s,s).then(translation((targetWidth-width*s)/2,(targetHeight-height*s)/2))
        }
        /** Continuous boundary-space EXIF orientation 1..8; image decoder already applies this for JPEG. */
        fun exif(orientation:Int,width:Int,height:Int):BoundaryImageTransform {
            require(width>0 && height>0)
            val w=width.toDouble(); val h=height.toDouble()
            val m=when(orientation) {
                1 -> doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0)
                2 -> doubleArrayOf(-1.0,0.0,w,0.0,1.0,0.0)
                3 -> doubleArrayOf(-1.0,0.0,w,0.0,-1.0,h)
                4 -> doubleArrayOf(1.0,0.0,0.0,0.0,-1.0,h)
                5 -> doubleArrayOf(0.0,1.0,0.0,1.0,0.0,0.0)
                6 -> doubleArrayOf(0.0,-1.0,h,1.0,0.0,0.0)
                7 -> doubleArrayOf(0.0,-1.0,h,-1.0,0.0,w)
                8 -> doubleArrayOf(0.0,1.0,0.0,-1.0,0.0,w)
                else -> throw IllegalArgumentException("Unknown EXIF orientation")
            }
            return BoundaryImageTransform(m+doubleArrayOf(0.0,0.0,1.0))
        }
    }
}

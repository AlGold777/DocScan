package app.docscan.android

import kotlin.math.*

enum class DocumentQuadFilterMode { LEGACY_EMA, TIME_EMA, ONE_EURO }

/** Runnable comparison filters; input pixels are isotropic. No prediction until preview age is measured. */
class DocumentQuadFilter(private val mode:DocumentQuadFilterMode) {
    private var filtered:FloatArray?=null
    private var measured:FloatArray?=null
    private var derivative=FloatArray(8)
    private var timestampMs:Long?=null
    fun reset() { filtered=null; measured=null; timestampMs=null; derivative=FloatArray(8) }
    fun update(quad:BoundaryQuad,nowMs:Long):BoundaryQuad {
        val p=quad.toFloatArray(); val old=filtered; val previous=measured; val time=timestampMs
        if(old==null || previous==null || time==null || nowMs<=time || nowMs-time>300) {
            filtered=p; measured=p.clone(); timestampMs=nowMs; return quad
        }
        val shift=(0..3).minByOrNull { offset -> (0..3).sumOf { i ->
            val j=(i+offset)%4; hypot((previous[i*2]-p[j*2]).toDouble(),(previous[i*2+1]-p[j*2+1]).toDouble()) } } ?: 0
        val current=FloatArray(8) { i -> p[((i/2+shift)%4)*2+i%2] }
        val dt=(nowMs-time)/1000.0
        fun alpha(cutoff:Double)=1.0/(1.0+1.0/(2*PI*cutoff*dt))
        val speed=current.indices.map { i ->
            val v=(current[i]-previous[i])/dt
            derivative[i]=(derivative[i]+alpha(1.0)*(v-derivative[i])).toFloat()
            abs(derivative[i].toDouble())
        }.max()
        val diagonal=max(hypot((current[4]-current[0]).toDouble(),(current[5]-current[1]).toDouble()),1.0)
        val displacement=current.indices.maxOf { abs(current[it]-previous[it]).toDouble() }/diagonal
        val a=when(mode) {
            DocumentQuadFilterMode.LEGACY_EMA -> when { displacement<0.015 -> 0.18; displacement<0.04 -> 0.30; else -> 0.50 }
            DocumentQuadFilterMode.TIME_EMA -> 1-exp(-dt/0.12)
            DocumentQuadFilterMode.ONE_EURO -> alpha(1.0+0.02*speed)
        }
        val proposed=FloatArray(8) { i -> (old[i]+a*(current[i]-old[i])).toFloat() }
        val valid=try { BoundaryQuad.from(proposed) } catch(_:IllegalArgumentException) { quad }
        filtered=valid.toFloatArray(); measured=current; timestampMs=nowMs
        return valid
    }
}

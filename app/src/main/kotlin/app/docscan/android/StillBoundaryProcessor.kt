package app.docscan.android

import app.docscan.core.edit.CropQuadrilateral
import app.docscan.core.edit.NormalizedCropPoint
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File

/** JPEG-only coarse-to-fine refinement; caller holds 32MiB admission, original is read-only. */
object StillBoundaryProcessor {
    const val WORKING_SET_BYTES=32L*1024*1024
    const val VERSION="still-bounded-huber-v1"
    fun refine(file:File,coarse:CropQuadrilateral):CropQuadrilateral {
        val bitmap=ProductImageDecoder.decodePrecisely(file,2L*1024*1024,maxSide=1600)
        val rgba=Mat(); val gray=Mat()
        try {
            DocumentCropProcessor.ensureOpenCvLoaded()
            Utils.bitmapToMat(bitmap,rgba)
            Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY)
            val p=coarse.asList().map { BoundaryPoint(it.x*(bitmap.width-1),it.y*(bitmap.height-1)) }
            val fit=DocumentBoundaryRefiner.refine(gray,BoundaryQuad(p[0],p[1],p[2],p[3]),radius=10)
            val n=fit.quad.points.map { NormalizedCropPoint(it.x/(bitmap.width-1),it.y/(bitmap.height-1)) }
            return CropQuadrilateral(n[0],n[1],n[2],n[3])
        } finally { gray.release(); rgba.release(); bitmap.recycle() }
    }
}

package app.docscan.android

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** User-invoked supplemental check; it does not certify a device or replace Gate Q. */
class ProductSelfTestActivity : AppCompatActivity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var runButton: Button
    private lateinit var shareButton: Button
    private var reportFile: File? = null
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.self_test_title)
            textSize = 22f
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = getString(R.string.self_test_supplemental_notice)
            textSize = 15f
            setPadding(0, dp(8), 0, dp(12))
        }, matchWrap())
        status = TextView(this).apply {
            text = getString(R.string.self_test_ready)
            textSize = 15f
        }
        val scroll = ScrollView(this).apply { addView(status) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        runButton = Button(this).apply {
            text = getString(R.string.self_test_run)
            setOnClickListener { runSelfTest() }
        }
        shareButton = Button(this).apply {
            text = getString(R.string.self_test_share)
            isEnabled = false
            setOnClickListener { shareReport() }
        }
        root.addView(runButton, matchWrap())
        root.addView(shareButton, matchWrap())
        setContentView(root)
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun runSelfTest() {
        if (running) return
        val realStorage = (application as DocScanApplication).productStorage
        running = true
        runButton.isEnabled = false
        shareButton.isEnabled = false
        status.text = getString(R.string.self_test_running)
        worker.execute {
            try {
                val result = ProductSelfTestRunner(applicationContext, realStorage).run()
                reportFile = result.reportFile
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    running = false
                    runButton.isEnabled = true
                    shareButton.isEnabled = result.reportFile.isFile
                    status.text = getString(
                        if (result.passed) R.string.self_test_passed else R.string.self_test_failed,
                        result.reportFile.name,
                    ) + "\n\n" + result.reportJson
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    running = false
                    runButton.isEnabled = true
                    shareButton.isEnabled = reportFile?.isFile == true
                    status.text = getString(
                        R.string.self_test_failed,
                        failure.message ?: failure.javaClass.simpleName,
                    )
                }
            }
        }
    }

    private fun shareReport() {
        val file = reportFile?.takeIf(File::isFile) ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.selftest.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newUri(contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.self_test_share_chooser)))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}

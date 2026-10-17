package app.docscan.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import app.docscan.core.model.ExportAttemptId
import app.docscan.core.model.ImageVersionId
import app.docscan.core.model.PageId
import app.docscan.core.model.RecognitionId
import app.docscan.core.store.BeginExportAttemptCommand
import app.docscan.core.store.CaptureCommand
import app.docscan.core.store.ProviderQualificationCommand
import app.docscan.core.store.RecognitionCandidate
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Instant
import java.util.UUID

internal data class SelfTestCheck(
    val id: String,
    val status: String,
    val durationMillis: Long,
    val diagnostic: String? = null,
)

internal data class ProductSelfTestReport(
    val runId: String,
    val packageName: String,
    val appVersion: String,
    val versionCode: Long,
    val startedAtUtc: String,
    val completedAtUtc: String?,
    val reportStatus: String,
    val androidRelease: String,
    val sdkInt: Int,
    val deviceModel: String,
    val buildFingerprint: String,
    val checks: List<SelfTestCheck>,
) {
    fun toJson(): String {
        require(runId.isNotBlank() && packageName.isNotBlank() && appVersion.isNotBlank())
        require(reportStatus in setOf("RUNNING", "PASS", "FAIL"))
        require(checks.all { it.id.isNotBlank() && it.status in setOf("PASS", "FAIL") && it.durationMillis >= 0L })
        require((reportStatus == "RUNNING") == completedAtUtc.isNullOrBlank())
        require(reportStatus != "PASS" || (checks.isNotEmpty() && checks.all { it.status == "PASS" }))
        require(reportStatus != "FAIL" || checks.any { it.status == "FAIL" })
        val items = JSONArray()
        checks.forEach { check ->
            items.put(JSONObject()
                .put("id", check.id)
                .put("status", check.status)
                .put("durationMillis", check.durationMillis)
                .put("diagnostic", check.diagnostic?.let(::redactDiagnostic)))
        }
        return JSONObject()
            .put("schemaVersion", 1)
            .put("reportType", "SUPPLEMENTAL_SELF_TEST")
            .put("runId", runId)
            .put("packageName", packageName)
            .put("appVersion", appVersion)
            .put("versionCode", versionCode)
            .put("startedAtUtc", startedAtUtc)
            .put("completedAtUtc", completedAtUtc)
            .put("reportStatus", reportStatus)
            .put("device", JSONObject()
                .put("androidRelease", androidRelease)
                .put("sdkInt", sdkInt)
                .put("model", deviceModel)
                .put("buildFingerprint", buildFingerprint))
            .put("checks", items)
            .toString(2)
    }

    companion object {
        private fun redactDiagnostic(raw: String): String = raw
            .replace(Regex("(?i)(content://)[^\\s]+")) { "${it.groupValues[1]}<redacted>" }
            .replace(Regex("(?i)(/data/(?:user|user_de|data)/)[^\\s]+")) { "${it.groupValues[1]}<redacted>" }
            .take(600)
    }
}

internal data class ProductSelfTestExecution(val reportFile: File, val reportJson: String, val passed: Boolean)

/** Supplemental local product check. It never replaces physical qualification or performs provider I/O. */
internal class ProductSelfTestRunner(
    context: Context,
    private val realStorage: ProductStorage,
    private val failureInjector: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext

    fun run(): ProductSelfTestExecution {
        val runId = UUID.randomUUID().toString()
        val databaseName = "docscan-self-test-$runId.db"
        val artifactDirectory = "docscan-self-test-artifacts-$runId"
        val candidateDirectory = "docscan-self-test-candidates-$runId"
        val realProjectionBefore = realStorage.workspaceProjection()
        val reportDirectory = File(appContext.filesDir, "docscan-self-test/reports")
        check(reportDirectory.isDirectory || reportDirectory.mkdirs()) { "Report directory cannot be created" }
        val reportFile = File(reportDirectory, "self-test-$runId.json")
        val checks = mutableListOf<SelfTestCheck>()
        val started = Instant.now().toString()
        var status = "RUNNING"
        var storage: ProductStorage? = null
        var exportArtifactId: app.docscan.core.model.ExportArtifactId? = null
        var recognitionText: String? = null
        val appInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= 28) appInfo.longVersionCode else appInfo.versionCode.toLong()

        fun persist() {
            val report = ProductSelfTestReport(
                runId = runId,
                packageName = appContext.packageName,
                appVersion = appInfo.versionName.orEmpty(),
                versionCode = versionCode,
                startedAtUtc = started,
                completedAtUtc = if (status == "RUNNING") null else Instant.now().toString(),
                reportStatus = status,
                androidRelease = Build.VERSION.RELEASE.orEmpty(),
                sdkInt = Build.VERSION.SDK_INT,
                deviceModel = Build.MODEL.orEmpty(),
                buildFingerprint = Build.FINGERPRINT.orEmpty(),
                checks = checks.toList(),
            )
            val content = report.toJson()
            val temporary = File(reportDirectory, ".${reportFile.name}.writing")
            FileOutputStream(temporary).use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), reportFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }

        fun checkStep(id: String, block: () -> Unit) {
            val began = SystemClock.elapsedRealtime()
            try {
                failureInjector(id)
                block()
                checks += SelfTestCheck(id, "PASS", SystemClock.elapsedRealtime() - began)
                persist()
            } catch (failure: Exception) {
                checks += SelfTestCheck(
                    id,
                    "FAIL",
                    SystemClock.elapsedRealtime() - began,
                    "${failure.javaClass.simpleName}: ${failure.message ?: "no diagnostic"}",
                )
                status = "FAIL"
                persist()
                throw SelfTestStepFailure(failure)
            }
        }

        persist()
        try {
            checkStep("SANDBOX_CAPTURE_AND_OCR") {
                storage = ProductStorage(
                    appContext,
                    databaseName,
                    artifactDirectory,
                    candidateDirectory,
                    cleanupSavedPdfEditCacheOnStartup = false,
                )
                val sandbox = requireNotNull(storage)
                val imageBytes = createTextFixture()
                val artifact = sandbox.stageCapturedJpeg(ByteArrayInputStream(imageBytes))
                val pageId = PageId.new()
                val imageVersionId = ImageVersionId.new()
                sandbox.commitCapture(
                    CaptureCommand(
                        workspaceId = sandbox.workspaceId,
                        pageId = pageId,
                        imageVersionId = imageVersionId,
                        artifact = artifact,
                        selected = true,
                        makeActive = true,
                        coordinateSpace = "JPEG_PIXEL_EDGE_ORIGIN_TOP_LEFT",
                        processingDesiredState = OCR_OPERATION_KEY,
                        sourceProvenance = "supplemental-self-test",
                    ),
                )
                val page = sandbox.visiblePages().single()
                check(page.pageId == pageId && page.artifactState == "COMMITTED") {
                    "Sandbox capture did not commit exactly one durable Page"
                }
                val ocr = TesseractOcrAdapter(appContext)
                val recognized = try {
                    ocr.recognizeDetailed(sandbox.artifactPath(page.artifactRelativePath).toFile())
                } finally {
                    ocr.close()
                }
                val normalized = recognized.text.uppercase().replace(Regex("[^A-Z0-9]"), "")
                check(normalized.contains("DOCSCAN")) { "Offline OCR did not find the self-test marker" }
                recognitionText = recognized.text
                sandbox.commitRecognition(
                    RecognitionCandidate(
                        recognitionId = RecognitionId.new(),
                        workspaceId = sandbox.workspaceId,
                        pageId = page.pageId,
                        imageVersionId = page.imageVersionId,
                        contentRevision = page.contentRevision,
                        desiredOperationKey = OCR_OPERATION_KEY,
                        recognizedText = recognized.text,
                        geometry = recognized.geometry,
                        layout = recognized.layout,
                    ),
                )
                check(sandbox.visiblePages().single().recognizedText == recognized.text) {
                    "Sandbox Recognition did not persist against its current image version"
                }
            }

            checkStep("SEARCHABLE_PDF_LOCAL_ROUND_TRIP") {
                val sandbox = requireNotNull(storage)
                val save = sandbox.freezeSelectedPagesForSave()
                check(save.pages.size == 1 && !save.pages.single().recognizedText.isNullOrBlank()) {
                    "Sandbox SaveManifest did not freeze the OCR page"
                }
                val outputSet = sandbox.composeFrozenSaveOutputSet(
                    save,
                    PdfOutputSettings(595f, 842f, 0.85f, 200),
                    85,
                )
                check(outputSet.pages.size == 1 && outputSet.pdfFile.isFile) {
                    "Sandbox PDF composer did not produce its page and PDF"
                }
                PDDocument.load(outputSet.pdfFile).use { pdf ->
                    check(pdf.numberOfPages == 1) { "Sandbox PDF page count differs from the frozen input" }
                    val text = PDFTextStripper().getText(pdf).uppercase().replace(Regex("[^A-Z0-9]"), "")
                    check(text.contains("DOCSCAN")) { "Sandbox PDF text layer did not preserve the fixture marker" }
                }
                exportArtifactId = sandbox.commitLocalExportOutputSet(save, outputSet)
                val outputs = sandbox.committedExportOutputs(requireNotNull(exportArtifactId))
                val pdfOutput = outputs.singleOrNull { it.relativePath.endsWith(".pdf") }
                    ?: error("Sandbox export did not commit exactly one PDF output")
                check(sandbox.verifyExportOutput(pdfOutput)) { "Committed sandbox PDF failed checksum read-back" }
                check(!recognitionText.isNullOrBlank())
            }

            checkStep("INTERRUPTED_ATTEMPT_RECOVERY_SIMULATION") {
                val sandbox = requireNotNull(storage)
                val treeUri = "content://self-test.invalid/tree/sandbox"
                val qualificationId = "self-test-$runId"
                sandbox.recordDestinationGrant(treeUri)
                sandbox.recordProviderQualification(
                    ProviderQualificationCommand(
                        qualificationId,
                        "self-test-no-io",
                        "local-only",
                        "sdk-${Build.VERSION.SDK_INT}",
                        "self-test-only",
                        "supplemental-self-test-v1",
                        "{\"providerIo\":false}",
                    ),
                )
                val output = sandbox.committedExportOutputs(requireNotNull(exportArtifactId))
                    .single { it.relativePath.endsWith(".pdf") }
                val attemptId = ExportAttemptId.new()
                sandbox.beginExportAttempt(
                    BeginExportAttemptCommand(
                        exportAttemptId = attemptId,
                        exportArtifactId = requireNotNull(exportArtifactId),
                        outputArtifactId = output.artifactId,
                        destinationTreeUri = treeUri,
                        plannedDisplayName = "SelfTest.pdf",
                        mimeType = "application/pdf",
                        providerQualificationId = qualificationId,
                        adapterId = "self-test-no-io",
                        providerVersion = "local-only",
                        osVersion = "sdk-${Build.VERSION.SDK_INT}",
                        destinationAccessClass = "self-test-only",
                    ),
                )
                check(sandbox.exportAttempt(attemptId)?.state == "WRITING_EXTERNAL") {
                    "Sandbox attempt was not durably recorded before simulated interruption"
                }
                sandbox.close()
                storage = null
                val reopened = ProductStorage(
                    appContext,
                    databaseName,
                    artifactDirectory,
                    candidateDirectory,
                    cleanupSavedPdfEditCacheOnStartup = false,
                )
                storage = reopened
                check(reopened.exportAttempt(attemptId)?.state == "UNKNOWN_OUTCOME") {
                    "Reopening sandbox did not recover the interrupted attempt to UNKNOWN_OUTCOME"
                }
                check(reopened.exportAttempts(requireNotNull(exportArtifactId)).single().outputArtifactId == output.artifactId) {
                    "Recovered attempt lost its protected output identity"
                }
            }

            checkStep("REAL_WORKSPACE_UNCHANGED") {
                check(realStorage.workspaceProjection() == realProjectionBefore) {
                    "Real workspace projection changed while the isolated self-test ran"
                }
            }
        } catch (_: SelfTestStepFailure) {
            // The failing check and diagnostic have already been durably recorded.
        } catch (failure: Exception) {
            checks += SelfTestCheck(
                "SELF_TEST_RUNNER",
                "FAIL",
                0L,
                "${failure.javaClass.simpleName}: ${failure.message ?: "no diagnostic"}",
            )
            status = "FAIL"
            persist()
        } finally {
            try {
                storage?.close()
            } catch (failure: Exception) {
                checks += SelfTestCheck("SANDBOX_CLOSE", "FAIL", 0L, "${failure.javaClass.simpleName}: ${failure.message}")
                status = "FAIL"
            }
            storage = null
            try {
                cleanupSandbox(databaseName, artifactDirectory, candidateDirectory)
            } catch (failure: Exception) {
                checks += SelfTestCheck("SANDBOX_CLEANUP", "FAIL", 0L, "${failure.javaClass.simpleName}: ${failure.message}")
                status = "FAIL"
            }
            if (status == "RUNNING") {
                status = if (checks.isNotEmpty() && checks.all { it.status == "PASS" }) "PASS" else "FAIL"
            }
            persist()
        }
        val json = reportFile.readText(Charsets.UTF_8)
        return ProductSelfTestExecution(reportFile, json, status == "PASS")
    }

    private fun createTextFixture(): ByteArray {
        val bitmap = Bitmap.createBitmap(1800, 520, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 150f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText("DOCSCAN SELF TEST 123", 60f, 330f, paint)
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output)) {
                    "Synthetic OCR fixture could not be encoded"
                }
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun cleanupSandbox(databaseName: String, artifactDirectory: String, candidateDirectory: String) {
        check(databaseName.matches(Regex("docscan-self-test-[0-9a-f-]{36}\\.db")))
        listOf(artifactDirectory, candidateDirectory).forEach { name ->
            check(name.matches(Regex("docscan-self-test-(?:artifacts|candidates)-[0-9a-f-]{36}")))
            val target = File(appContext.noBackupFilesDir, name)
            val base = appContext.noBackupFilesDir.canonicalFile
            check(target.canonicalFile.parentFile == base) { "Self-test cleanup escaped the private sandbox root" }
            if (target.exists()) check(target.deleteRecursively()) { "Self-test sandbox output cannot be removed" }
        }
        val databaseFile = appContext.getDatabasePath(databaseName)
        if (databaseFile.exists()) {
            check(appContext.deleteDatabase(databaseName)) { "Self-test database cannot be removed" }
        }
    }

    private class SelfTestStepFailure(cause: Exception) : RuntimeException(cause)

    private companion object {
        const val OCR_OPERATION_KEY = "OCR_RUS_ENG_TESSERACT_V1"
    }
}

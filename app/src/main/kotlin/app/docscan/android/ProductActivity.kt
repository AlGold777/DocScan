package app.docscan.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.content.Context
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import app.docscan.core.edit.CropQuadrilateral
import app.docscan.core.model.ImageVersionId
import app.docscan.core.model.PageId
import app.docscan.core.model.RecognitionId
import app.docscan.core.model.ExportArtifactId
import app.docscan.core.model.ExportAttemptId
import app.docscan.core.recent.SavedPdfEditSession
import app.docscan.core.store.BeginExportAttemptCommand
import app.docscan.core.store.ExternalExportPackagePlan
import app.docscan.core.store.CaptureCommand
import app.docscan.core.store.RecognitionCandidate
import app.docscan.core.export.OutputBasenameSuggester
import app.docscan.core.export.OutputNameResolution
import com.google.android.material.snackbar.Snackbar
import app.docscan.core.edit.PageImageEditDraft
import app.docscan.core.edit.PageImageEditState
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Minimal product UI for persistent capture and page selection. */
class ProductActivity : AppCompatActivity() {
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val importExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val thumbnailExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cameraAnalysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    internal lateinit var workspaceThumbnailLoader: WorkspaceThumbnailLoader
    private lateinit var root: LinearLayout
    private lateinit var dialogHost: InsetDialogHost
    private lateinit var homeRail: RecyclerView
    private lateinit var homeThumbnailAdapter: WorkspaceHomeAdapter
    private lateinit var homeEmptyLabel: TextView
    private lateinit var workspaceMenu: LinearLayout
    private var workspaceMenuDialog: AlertDialog? = null
    private lateinit var versionLabel: TextView
    private lateinit var status: TextView
    private val errorLog: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    private lateinit var previewView: PreviewView
    private lateinit var documentOverlayView: DocumentOverlayView
    private lateinit var captureButton: Button
    private lateinit var permissionActionButton: Button
    private lateinit var documentModeButton: Button
    @Volatile private var isDocumentMode: Boolean = false
    private var imageAnalysis: ImageAnalysis? = null
    private var lastAnalyzedTimestamp: Long = 0L
    private val imageTransformFactory = ImageProxyTransformFactory().apply {
        isUsingRotationDegrees = false
        isUsingCropRect = false
    }
    private val liveDetectionCoordinator = DocumentLiveDetectionCoordinator()
    private lateinit var ocrButton: Button
    private lateinit var blackAndWhiteButton: Button
    @Volatile private var isBlackAndWhiteMode: Boolean = false
    private var autoOcrEnabled: Boolean = true
    private lateinit var importButton: Button
    private lateinit var destinationButton: Button
    private lateinit var ocrLangButton: Button
    private lateinit var recentDocumentsButton: Button
    private lateinit var saveButton: Button
    private lateinit var selectionButton: Button
    private lateinit var recoverExportsButton: Button
    private lateinit var resumeExportsButton: Button
    private lateinit var deleteSelectedButton: ImageButton
    private lateinit var floatingDeleteButton: ImageButton
    private lateinit var clearWorkspaceButton: Button
    private var pagesContainer: RecyclerView? = null
    private var workspacePageAdapter: WorkspacePageAdapter? = null
    private var workspaceEmptyLabel: TextView? = null
    internal val workspacePagesListForTest: RecyclerView? get() = pagesContainer
    private var lastWorkspaceProjection: app.docscan.core.store.WorkspaceProjection? = null
    private var imageCapture: ImageCapture? = null
    private var storage: ProductStorage? = null
    private var currentImportRequestId: String? = null
    @Volatile private var providerSaveReady = false
    private val externalWorkflowRunning = AtomicBoolean(false)
    private val destinationSetupRunning = AtomicBoolean(false)
    private var pendingMainSave = false
    private var destinationPickerPending = false
    private var destinationReadyAction: (() -> Unit)? = null
    private var destinationCancelledAction: (() -> Unit)? = null
    private var deferredDestinationResult: ActivityResult? = null
    private val savedPdfImportRunning = AtomicBoolean(false)
    private var cameraProvider: ProcessCameraProvider? = null
    private var retakeTarget: app.docscan.core.store.StoredPage? = null
    private var cameraStartupPending = false
    private var pageViewer: AlertDialog? = null
    private var pageViewerGeneration = 0
    private var pageViewerPages: List<app.docscan.core.store.StoredPage> = emptyList()
    private var pageViewerIndex = 0
    private var pageViewerImage: ImageView? = null
    private var pageViewerTitle: TextView? = null
    private var pageViewerText: TextView? = null
    private var pageViewerPrevious: Button? = null
    private var pageViewerNext: Button? = null
    private var pageViewerStrip: LinearLayout? = null
    private var pageViewerStripScroll: HorizontalScrollView? = null
    private var versionTapCount = 0
    private var versionTapLastElapsed = 0L
    @Volatile private var workspaceActionBlocked = false
    internal var uiHapticFeedback: (View) -> Unit = { it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }

    private val permissionRequest: ActivityResultLauncher<String> = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            permissionActionButton.visibility = View.GONE
            if (::documentModeButton.isInitialized) documentModeButton.visibility = View.VISIBLE
            startCamera()
        } else if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            status.text = getString(R.string.camera_permission_rationale)
            permissionActionButton.text = getString(R.string.retry_camera_permission)
            permissionActionButton.visibility = View.VISIBLE
            if (::documentModeButton.isInitialized) documentModeButton.visibility = View.GONE
            permissionActionButton.setOnClickListener { permissionRequest.launch(Manifest.permission.CAMERA) }
        } else {
            recordError(getString(R.string.camera_permission_denied))
            permissionActionButton.text = getString(R.string.open_app_settings)
            permissionActionButton.visibility = View.VISIBLE
            if (::documentModeButton.isInitialized) documentModeButton.visibility = View.GONE
            permissionActionButton.setOnClickListener {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                })
            }
        }
    }

    private val importRequest: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> importSelectedDocuments(uris) }

    private val destinationRequest: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleDestinationResult(result)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        workspaceThumbnailLoader = WorkspaceThumbnailLoader(thumbnailExecutor)
        currentImportRequestId = savedInstanceState?.getString(STATE_IMPORT_REQUEST_ID) ?: UUID.randomUUID().toString()
        pendingMainSave = savedInstanceState?.getBoolean(STATE_PENDING_MAIN_SAVE) ?: false
        destinationPickerPending = savedInstanceState?.getBoolean(STATE_DESTINATION_PICKER_PENDING) ?: false
        isDocumentMode = savedInstanceState?.getBoolean(STATE_DOCUMENT_MODE) ?: false
        isBlackAndWhiteMode = savedInstanceState?.getBoolean(STATE_BLACK_AND_WHITE_MODE) ?: false
        buildContent()
        status.text = getString(R.string.storage_initializing)
        io.execute {
            try {
                val initialized = (application as DocScanApplication).productStorage
                storage = initialized
                if (initialized.startupIntegrityBlocked) {
                    workspaceActionBlocked = true
                    runOnUiThread {
                        lockWorkspace(getString(
                            R.string.workspace_integrity_blocked,
                            initialized.startupIntegrityFindingCount,
                        ))
                    }
                    return@execute
                }
                val projection = initialized.workspaceProjection()
                updateRecentDocuments(initialized)
                runOnUiThread {
                    status.text = when {
                        initialized.failedCommittedSaveCandidateCleanupsAtStartup > 0 ->
                            getString(R.string.local_candidate_cleanup_pending)
                        initialized.abandonedLocalOutputCompositionsAtStartup > 0 ->
                            getString(R.string.local_save_recovered)
                        initialized.abandonedSavedPdfEditsAtStartup > 0 ->
                            getString(R.string.saved_pdf_edit_recovered)
                        else -> getString(R.string.camera_ready)
                    }
                    destinationButton.isEnabled = true
                    recoverExportsButton.isEnabled = true
                    renderPages(projection)
                    updateRecoveryActions(initialized)
                    refreshDestinationGrantStatus(initialized)
                    val deferredResult = deferredDestinationResult
                    deferredDestinationResult = null
                    if (deferredResult != null) handleDestinationResult(deferredResult)
                    else if (pendingMainSave && !destinationPickerPending) requestMainSave()
                    if (hasCameraPermission()) startCamera() else permissionRequest.launch(Manifest.permission.CAMERA)
                }
                importExecutor.execute {
                    try {
                        initialized.resumeIncompletePdfImports((application as DocScanApplication).heavyWorkScheduler) {
                                name, completed, total ->
                            runOnUiThread {
                                status.text = getString(R.string.resuming_pdf_import, name, completed, total)
                            }
                        }
                    } catch (failure: Exception) {
                        runOnUiThread {
                            if (!isFinishing) recordError(getString(
                                R.string.resume_pdf_import_failed,
                                failure.message ?: failure.javaClass.simpleName,
                            ))
                        }
                    }
                    val refreshed = runCatching { initialized.workspaceProjection() }.getOrNull() ?: return@execute
                    if (autoOcrEnabled) {
                        refreshed.pages.filter { it.recognizedText == null && it.artifactState == "COMMITTED" }
                            .forEach { scheduleOcr(initialized, it) }
                    }
                    runOnUiThread { if (!isFinishing) renderPages(refreshed) }
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    lockWorkspace(getString(R.string.storage_error, failure.message ?: failure.javaClass.simpleName))
                }
            }
        }
    }

    override fun onDestroy() {
        liveDetectionCoordinator.invalidate()
        if (::documentOverlayView.isInitialized) documentOverlayView.clear()
        pageViewer?.dismiss()
        if (::dialogHost.isInitialized) dialogHost.dismissAll()
        cameraProvider?.unbindAll()
        destinationReadyAction = null
        destinationCancelledAction = null
        io.shutdown()
        importExecutor.shutdown()
        cameraAnalysisExecutor.shutdownNow()
        workspaceThumbnailLoader.close()
        thumbnailExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onStop() {
        liveDetectionCoordinator.invalidate()
        if (::documentOverlayView.isInitialized) documentOverlayView.clear()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        currentImportRequestId?.let { outState.putString(STATE_IMPORT_REQUEST_ID, it) }
        outState.putBoolean(STATE_PENDING_MAIN_SAVE, pendingMainSave)
        outState.putBoolean(STATE_DESTINATION_PICKER_PENDING, destinationPickerPending)
        outState.putBoolean(STATE_DOCUMENT_MODE, isDocumentMode)
        outState.putBoolean(STATE_BLACK_AND_WHITE_MODE, isBlackAndWhiteMode)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (workspaceActionBlocked) return
        storage?.let(::refreshDestinationGrantStatus)
        if (storage != null && imageCapture == null && hasCameraPermission()) {
            permissionActionButton.visibility = View.GONE
            if (::documentModeButton.isInitialized) documentModeButton.visibility = View.VISIBLE
            startCamera()
        }
    }

    private fun modernPillDrawable(enabledColor: Int, disabledColor: Int, radiusDp: Int, strokeColor: Int? = null): StateListDrawable {
        val radiusPx = dp(radiusDp).toFloat()
        val enabledDrawable = GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(enabledColor)
        }
        val disabledDrawable = GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(disabledColor)
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_enabled), enabledDrawable)
            addState(intArrayOf(-android.R.attr.state_enabled), disabledDrawable)
        }
    }

    private fun modernButtonDrawable(
        normalBg: Int = 0xFF1E222A.toInt(),
        pressedBg: Int = 0xFF334155.toInt(),
        disabledBg: Int = 0xFF14171C.toInt(),
        strokeColor: Int = 0xFF374151.toInt(),
        radiusDp: Int = 12,
        strokeWidthDp: Int = 1,
    ): StateListDrawable {
        val radiusPx = dp(radiusDp).toFloat()
        val strokeWidthPx = dp(strokeWidthDp)
        val pressed = GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(pressedBg)
            setStroke(strokeWidthPx, strokeColor)
        }
        val normal = GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(normalBg)
            setStroke(strokeWidthPx, strokeColor)
        }
        val disabled = GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(disabledBg)
            setStroke(dp(1), 0xFF262B33.toInt())
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(-android.R.attr.state_enabled), disabled)
            addState(intArrayOf(android.R.attr.state_enabled), normal)
            addState(intArrayOf(), normal)
        }
    }

    private fun borderlessRippleDrawable(): android.graphics.drawable.Drawable? {
        val outValue = android.util.TypedValue()
        return if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)) {
            androidx.core.content.ContextCompat.getDrawable(this, outValue.resourceId)
        } else null
    }

    private fun updateOcrButtonAppearance() {
        if (!::ocrButton.isInitialized) return
        val active = autoOcrEnabled
        ocrButton.text = "[Aa]"
        ocrButton.setTextColor(if (active) Color.WHITE else 0xFF94A3B8.toInt())
        ocrButton.background = borderlessRippleDrawable()
        androidx.core.view.ViewCompat.setStateDescription(
            ocrButton,
            getString(if (active) R.string.ocr_state_enabled else R.string.ocr_state_disabled),
        )
    }

    private fun updateBlackAndWhiteButtonAppearance() {
        if (!::blackAndWhiteButton.isInitialized) return
        val active = isBlackAndWhiteMode
        blackAndWhiteButton.text = "◪"
        blackAndWhiteButton.setTextColor(if (active) Color.WHITE else 0xFF94A3B8.toInt())
        blackAndWhiteButton.background = borderlessRippleDrawable()
        androidx.core.view.ViewCompat.setStateDescription(
            blackAndWhiteButton,
            getString(if (active) R.string.bw_mode_state_enabled else R.string.bw_mode_state_disabled),
        )
        updateCameraPreviewFilter()
    }

    private fun updateCameraPreviewFilter() {
        if (!::previewView.isInitialized) return
        val grayFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        if (Build.VERSION.SDK_INT >= 31) {
            previewView.setRenderEffect(
                if (isBlackAndWhiteMode) {
                    RenderEffect.createColorFilterEffect(grayFilter)
                } else null
            )
        } else {
            val grayPaint = if (isBlackAndWhiteMode) Paint().apply { colorFilter = grayFilter } else null
            previewView.setLayerType(View.LAYER_TYPE_HARDWARE, grayPaint)
        }
    }

    private fun applyBlackAndWhiteFilter(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    private fun updateDocumentModeButtonAppearance() {
        if (!::documentModeButton.isInitialized) return
        documentModeButton.isAllCaps = false
        documentModeButton.text = getString(R.string.document_mode_name)
        documentModeButton.background = borderlessRippleDrawable()
        documentModeButton.isSelected = isDocumentMode
        documentModeButton.contentDescription = getString(R.string.document_mode_toggle_description)
        androidx.core.view.ViewCompat.setStateDescription(
            documentModeButton,
            getString(if (isDocumentMode) R.string.document_mode_state_enabled else R.string.document_mode_state_disabled),
        )
        if (isDocumentMode) {
            documentModeButton.setTextColor(0xFF4ADE80.toInt())
        } else {
            documentModeButton.setTextColor(0xFF94A3B8.toInt())
            if (::documentOverlayView.isInitialized) {
                documentOverlayView.clear()
            }
        }
    }

    private fun buildContent() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xff111316.toInt())
        }
        dialogHost = InsetDialogHost(root)
        status = TextView(this).apply {
            textSize = 15f
            tag = "docscan-status"
        }
        versionLabel = TextView(this).apply {
            val appVersion = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            text = getString(R.string.about_version, appVersion)
            contentDescription = getString(R.string.about_version_description, appVersion)
            tag = "docscan-version-label"
            minHeight = dp(48)
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener { onVersionLabelTapped() }
        }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    liveDetectionCoordinator.invalidate()
                    if (::documentOverlayView.isInitialized) documentOverlayView.clear()
                }
            }
        }
        val actionTextColor = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf(-android.R.attr.state_enabled)),
            intArrayOf(0xFFF8FAFC.toInt(), 0xFF64748B.toInt())
        )
        captureButton = Button(this).apply {
            tag = "docscan-capture-button"
            contentDescription = getString(R.string.capture_page)
            text = ""
            isEnabled = false
            val outer = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x22FFFFFF)
                setStroke(dp(4), Color.WHITE)
            }
            val inner = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            background = LayerDrawable(arrayOf(outer, inner)).apply {
                setLayerInset(1, dp(7), dp(7), dp(7), dp(7))
            }
            setOnClickListener { takePicture() }
        }
        ocrButton = Button(this).apply {
            tag = "docscan-ocr-toggle-button"
            text = "[Aa]"
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            contentDescription = getString(R.string.ocr_toggle_description)
            background = borderlessRippleDrawable()
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
            minWidth = 0
            minHeight = 0
            setOnClickListener {
                autoOcrEnabled = !autoOcrEnabled
                uiHapticFeedback(this)
                updateOcrButtonAppearance()
                if (autoOcrEnabled) {
                    val activeStorage = storage
                    if (activeStorage != null) {
                        io.execute {
                            val projection = runCatching { activeStorage.workspaceProjection() }.getOrNull()
                            if (projection != null) {
                                projection.pages.filter { it.recognizedText == null && it.artifactState == "COMMITTED" }
                                    .forEach { scheduleOcr(activeStorage, it) }
                            }
                        }
                    }
                }
            }
            setOnLongClickListener {
                showOcrLangSelectionDialog()
                true
            }
        }
        updateOcrButtonAppearance()
        blackAndWhiteButton = Button(this).apply {
            tag = "docscan-bw-toggle-button"
            text = "◪"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            contentDescription = getString(R.string.bw_mode_toggle_description)
            background = borderlessRippleDrawable()
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
            minWidth = 0
            minHeight = 0
            setOnClickListener {
                isBlackAndWhiteMode = !isBlackAndWhiteMode
                uiHapticFeedback(this)
                updateBlackAndWhiteButtonAppearance()
            }
        }
        updateBlackAndWhiteButtonAppearance()
        importButton = Button(this).apply {
            text = "+"
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            contentDescription = getString(R.string.import_images_or_pdf)
            tag = "docscan-import-button"
            background = modernButtonDrawable()
            setTextColor(actionTextColor)
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setOnClickListener {
                currentImportRequestId = UUID.randomUUID().toString()
                importRequest.launch(arrayOf("image/*", "application/pdf"))
            }
        }
        destinationButton = Button(this).apply {
            text = getString(R.string.choose_save_folder)
            contentDescription = getString(R.string.choose_save_folder)
            tag = "docscan-destination-button"
            isEnabled = false
            setOnClickListener {
                if (!destinationSetupRunning.get()) launchDestinationPicker()
            }
        }
        ocrLangButton = Button(this).apply {
            tag = "docscan-ocr-lang-button"
            setOnClickListener { showOcrLangSelectionDialog() }
        }
        updateOcrLangButtonText(ocrLangButton)
        recentDocumentsButton = Button(this).apply {
            tag = "docscan-recent-button"
            visibility = View.GONE
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = modernButtonDrawable(
                normalBg = 0xDD1E222A.toInt(),
                pressedBg = 0xFF334155.toInt(),
                strokeColor = 0xFF475569.toInt(),
                radiusDp = 12,
            )
            setTextColor(0xFFE2E8F0.toInt())
            setOnClickListener { showRecentDocuments() }
        }
        saveButton = Button(this).apply {
            contentDescription = "Save"
            text = "Save"
            tag = "docscan-save-button"
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isEnabled = false
            background = modernPillDrawable(
                enabledColor = 0xFF2563EB.toInt(),
                disabledColor = 0xFF1E293B.toInt(),
                radiusDp = 22,
                strokeColor = 0xFF334155.toInt(),
            )
            setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf(-android.R.attr.state_enabled)),
                    intArrayOf(Color.WHITE, 0xFF64748B.toInt())
                )
            )
            setOnClickListener { requestMainSave() }
        }
        selectionButton = Button(this).apply {
            text = "Select"
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            contentDescription = getString(R.string.select_all_pages)
            tag = "docscan-selection-button"
            isEnabled = false
            background = modernButtonDrawable()
            setTextColor(actionTextColor)
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setOnClickListener { toggleAllPageSelection() }
        }
        recoverExportsButton = Button(this).apply {
            text = getString(R.string.recheck_interrupted_exports)
            tag = "docscan-recover-exports-button"
            isEnabled = false
            setOnClickListener { recheckInterruptedExports() }
        }
        resumeExportsButton = Button(this).apply {
            text = getString(R.string.continue_incomplete_exports)
            tag = "docscan-resume-exports-button"
            isEnabled = false
            setOnClickListener { resumeIncompleteExports() }
        }
        deleteSelectedButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_delete_trash)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            contentDescription = getString(R.string.delete_selected_pages)
            tag = "docscan-delete-selected-button"
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF18181B.toInt())
                setStroke(dp(1), Color.WHITE)
            }
            elevation = dp(8).toFloat()
            setOnClickListener {
                uiHapticFeedback(this)
                deletePageBatch(clearAll = false)
                animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(150).withEndAction { visibility = View.GONE }.start()
            }
        }
        floatingDeleteButton = deleteSelectedButton
        clearWorkspaceButton = Button(this).apply {
            text = getString(R.string.clear_workspace)
            tag = "docscan-clear-workspace-button"
            isEnabled = false
            setOnClickListener {
                dialogHost.builder(this@ProductActivity)
                    .setTitle(R.string.clear_workspace_title)
                    .setMessage(R.string.clear_workspace_confirmation)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.clear_workspace) { _, _ -> deletePageBatch(clearAll = true) }
                    .show()
            }
        }
        permissionActionButton = Button(this).apply { visibility = View.GONE }
        documentModeButton = Button(this).apply {
            isAllCaps = false
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            visibility = if (hasCameraPermission()) View.VISIBLE else View.GONE
            background = borderlessRippleDrawable()
            setOnClickListener {
                isDocumentMode = !isDocumentMode
                updateDocumentModeButtonAppearance()
                uiHapticFeedback(this)
                liveDetectionCoordinator.invalidate()
                if (::documentOverlayView.isInitialized) {
                    documentOverlayView.clear()
                }
            }
        }
        updateDocumentModeButtonAppearance()
        workspaceMenu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "docscan-workspace-menu"
            visibility = View.GONE
        }
        val menuButton = TextView(this).apply {
            text = "⋮"
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = getString(R.string.workspace_menu)
            tag = "docscan-menu-button"
            background = borderlessRippleDrawable()
            setOnClickListener { showWorkspaceMenu() }
        }
        val titleView = TextView(this).apply {
            text = "DocScan"
            tag = "docscan-title"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.02f
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { onVersionLabelTapped() }
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(16), dp(4))
            addView(menuButton, LinearLayout.LayoutParams(dp(40), dp(44)).apply { marginEnd = dp(4) })
            addView(titleView, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(saveButton, LinearLayout.LayoutParams(dp(130), dp(44)))
        }
        val camera = android.widget.FrameLayout(this).apply {
            tag = "docscan-camera-area"
            addView(previewView, android.widget.FrameLayout.LayoutParams(-1, -1))
            documentOverlayView = DocumentOverlayView(this@ProductActivity)
            addView(documentOverlayView, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(top, android.widget.FrameLayout.LayoutParams(-1, dp(56), Gravity.TOP))
            status.apply {
                setTextColor(Color.WHITE)
                textSize = 12f
                maxLines = 3
                setPadding(dp(16), dp(8), dp(16), dp(8))
                visibility = View.GONE
            }
            addView(status, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(60) })
            val leftButtonsContainer = LinearLayout(this@ProductActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ocrButton, LinearLayout.LayoutParams(dp(40), dp(44)))
                addView(blackAndWhiteButton, LinearLayout.LayoutParams(dp(40), dp(44)).apply {
                    marginStart = dp(4)
                })
            }
            val shutterRow = android.widget.FrameLayout(this@ProductActivity).apply {
                addView(leftButtonsContainer, android.widget.FrameLayout.LayoutParams(-2, dp(48), Gravity.START or Gravity.CENTER_VERTICAL).apply {
                    leftMargin = dp(4)
                })
                addView(captureButton, android.widget.FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
                addView(permissionActionButton, android.widget.FrameLayout.LayoutParams(dp(104), dp(56), Gravity.END or Gravity.CENTER_VERTICAL))
                addView(documentModeButton, android.widget.FrameLayout.LayoutParams(dp(104), dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
            }
            addView(shutterRow, android.widget.FrameLayout.LayoutParams(-1, dp(88), Gravity.BOTTOM).apply { leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(8) })
        }
        root.addView(camera, LinearLayout.LayoutParams(-1, 0, 1f))
        homeThumbnailAdapter = WorkspaceHomeAdapter(
            activity = this,
            loader = workspaceThumbnailLoader,
            enabled = { !workspaceActionBlocked && !externalWorkflowRunning.get() },
            onPageClick = { page ->
                val current = homeThumbnailAdapter.currentPages()
                val index = current.indexOfFirst { it.pageId == page.pageId }
                if (index >= 0) showPageViewer(current, index)
            },
            onSelectionChanged = { page, selected -> if (canInteractWorkspace()) io.execute {
                runCatching {
                    storage?.setSelected(page.pageId, selected)
                    storage?.workspaceProjection()
                }.onSuccess { projection ->
                    if (projection != null) runOnUiThread { renderPages(projection) }
                }.onFailure { failure ->
                    runOnUiThread { recordError(getString(R.string.capture_error, failure.message.orEmpty())) }
                }
            } },
            onPageActive = { page -> if (canInteractWorkspace()) io.execute {
                runCatching {
                    storage?.setActivePage(page.pageId)
                    storage?.workspaceProjection()
                }.onSuccess { projection ->
                    if (projection != null) runOnUiThread { renderPages(projection) }
                }
            } },
            onDeletePage = { page -> deleteSinglePage(page.pageId) },
            onToggleAllSelection = { toggleAllPageSelection() },
        )
        homeRail = RecyclerView(this).apply {
            tag = "docscan-home-thumbnail-scroll"
            layoutManager = LinearLayoutManager(this@ProductActivity, RecyclerView.HORIZONTAL, false)
            adapter = homeThumbnailAdapter
            itemAnimator = null
            isNestedScrollingEnabled = false
            setPadding(dp(4), dp(2), dp(4), dp(2))
            clipToPadding = false
        }
        val touchHelper = androidx.recyclerview.widget.ItemTouchHelper(object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.LEFT or androidx.recyclerview.widget.ItemTouchHelper.RIGHT,
            androidx.recyclerview.widget.ItemTouchHelper.DOWN,
        ) {
            private var dragStarted = false
            private var dragMoved = false

            override fun isLongPressDragEnabled(): Boolean = true

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to) return false
                dragMoved = true
                homeThumbnailAdapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                if (direction == androidx.recyclerview.widget.ItemTouchHelper.DOWN) {
                    val pos = viewHolder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val page = homeThumbnailAdapter.getItem(pos)?.page ?: return
                        deleteSinglePage(page.pageId)
                    }
                }
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG) {
                    dragStarted = true
                    dragMoved = false
                    viewHolder?.itemView?.apply {
                        uiHapticFeedback(this)
                        animate().scaleX(1.1f).scaleY(1.1f).alpha(0.85f).setDuration(120).start()
                        elevation = dp(6).toFloat()
                    }
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.apply {
                    animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f).setDuration(120).start()
                    elevation = 0f
                }
                if (dragStarted && dragMoved) {
                    val projection = lastWorkspaceProjection
                    if (projection != null && canInteractWorkspace()) {
                        val newPages = homeThumbnailAdapter.currentPages()
                        val activeStorage = storage
                        if (activeStorage != null && newPages.isNotEmpty()) {
                            io.execute {
                                try {
                                    activeStorage.reorderPages(projection, newPages)
                                    val refreshed = activeStorage.workspaceProjection()
                                    runOnUiThread {
                                        uiHapticFeedback(root)
                                        renderPages(refreshed)
                                    }
                                } catch (failure: Exception) {
                                    runOnUiThread { recordError(getString(R.string.capture_error, failure.message.orEmpty())) }
                                }
                            }
                        }
                    }
                }
                dragStarted = false
                dragMoved = false
            }
        })
        touchHelper.attachToRecyclerView(homeRail)
        val railGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (!canInteractWorkspace()) return
                val projection = lastWorkspaceProjection ?: return
                if (projection.pages.isEmpty()) return
                uiHapticFeedback(floatingDeleteButton)
                floatingDeleteButton.visibility = View.VISIBLE
                floatingDeleteButton.scaleX = 0f
                floatingDeleteButton.scaleY = 0f
                floatingDeleteButton.alpha = 0f
                floatingDeleteButton.animate()
                    .scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.OvershootInterpolator())
                    .start()
            }
        })
        homeRail.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                railGestureDetector.onTouchEvent(e)
                return false
            }
        })
        val homeRailContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            importButton.apply {
                isAllCaps = false
                text = "+"
                textSize = 34f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = null
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 0)
            }
            val importContainer = LinearLayout(this@ProductActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(importButton, LinearLayout.LayoutParams(dp(44), dp(92)))
                val bottomSpacer = View(this@ProductActivity)
                addView(bottomSpacer, LinearLayout.LayoutParams(dp(44), dp(34)))
            }
            addView(importContainer, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginStart = dp(3)
                marginEnd = dp(2)
            })
            addView(homeRail, LinearLayout.LayoutParams(0, -1, 1f))
        }
        val homeRailFrame = android.widget.FrameLayout(this).apply {
            tag = "docscan-home-thumbnail-strip"
            addView(homeRailContent, android.widget.FrameLayout.LayoutParams(-1, -1))
            homeEmptyLabel = TextView(this@ProductActivity).apply {
                visibility = View.GONE
            }
            addView(homeEmptyLabel, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(floatingDeleteButton, android.widget.FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
                topMargin = dp(74)
            })
        }
        root.addView(homeRailFrame, LinearLayout.LayoutParams(-1, dp(134)))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
    }

    private fun recordError(message: String) {
        if (message.isNotBlank()) {
            errorLog.add(message)
        }
        status.text = message
    }

    private fun showErrorsModal() {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF1E222A.toInt())
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        synchronized(errorLog) {
            for (errorText in errorLog) {
                val item = TextView(this).apply {
                    text = errorText
                    textSize = 13f
                    setTextColor(0xFFFCA5A5.toInt())
                    setPadding(0, dp(4), 0, dp(4))
                }
                listLayout.addView(item)
            }
        }
        scroll.addView(listLayout, ViewGroup.LayoutParams(-1, -2))
        dialog.setContentView(scroll)
        dialog.window?.setBackgroundDrawable(
            GradientDrawable().apply {
                setColor(0xFF1E222A.toInt())
                cornerRadius = dp(16).toFloat()
            }
        )
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    @androidx.annotation.VisibleForTesting
    internal fun showWorkspaceMenu() {
        if (!canInteractWorkspace()) return
        if (workspaceMenuDialog?.isShowing == true) return
        buildWorkspaceMenuContents()
        workspaceMenu.visibility = View.VISIBLE
        val scroll = ScrollView(this).apply {
            tag = "docscan-workspace-menu-scroll"
            addView(workspaceMenu, ViewGroup.LayoutParams(-1, -2))
        }
        val appVersion = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        val titleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(8))
        }
        val versionView = TextView(this).apply {
            text = "Версия $appVersion"
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            tag = "docscan-version-label"
            setOnClickListener { onVersionLabelTapped() }
        }
        titleLayout.addView(versionView)

        val errorBadge = TextView(this).apply {
            tag = "docscan-error-badge"
            text = errorLog.size.toString()
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val badgeSize = dp(20)
            layoutParams = LinearLayout.LayoutParams(badgeSize, badgeSize).apply {
                marginStart = dp(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFEF4444.toInt())
            }
            setOnClickListener { showErrorsModal() }
        }
        titleLayout.addView(errorBadge)

        val dialog = dialogHost.builder(this).setCustomTitle(titleLayout).setView(scroll)
            .setNegativeButton(android.R.string.cancel, null).create()
        workspaceMenuDialog = dialog
        dialog.setOnDismissListener {
            pagesContainer?.let(::unbindWorkspaceThumbnails)
            pagesContainer?.adapter = null
            pagesContainer = null
            workspacePageAdapter = null
            workspaceEmptyLabel = null
            workspaceMenu.removeAllViews()
            scroll.removeView(workspaceMenu)
            workspaceMenu.visibility = View.GONE
            workspaceMenuDialog = null
        }
        dialog.show()
        dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.apply {
            tag = "docscan-version-label"
            setOnClickListener { onVersionLabelTapped() }
        }
        lastWorkspaceProjection?.let(::renderPages)
    }

    private fun refreshHomeRail(projection: app.docscan.core.store.WorkspaceProjection) {
        homeThumbnailAdapter.submitPages(projection.pages, projection.activePageId)
        homeEmptyLabel.visibility = View.GONE
        val manager = homeRail.layoutManager as? LinearLayoutManager ?: return
        val activeIndex = projection.pages.indexOfFirst { it.pageId == projection.activePageId }
        if (activeIndex >= 0 && (activeIndex < manager.findFirstVisibleItemPosition() || activeIndex > manager.findLastVisibleItemPosition())) {
            homeRail.scrollToPosition(activeIndex)
        }
    }

    private fun buildWorkspaceMenuContents() {
        workspaceMenu.removeAllViews()
        workspaceMenu.setPadding(dp(16), dp(12), dp(16), dp(16))

        fun createCard(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF1E222A.toInt())
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0xFF334155.toInt())
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        // 1. Storage Folder Card
        val folderCard = createCard().apply {
            val title = TextView(this@ProductActivity).apply {
                text = "📁  Папка сохранения"
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(0xFF93C5FD.toInt())
                setPadding(0, 0, 0, dp(8))
            }
            destinationButton.apply {
                background = modernButtonDrawable(normalBg = 0xFF2563EB.toInt(), pressedBg = 0xFF1D4ED8.toInt(), radiusDp = 12)
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                minHeight = dp(46)
            }
            addView(title)
            addView(destinationButton, matchWrap())
        }
        workspaceMenu.addView(folderCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 2. OCR Language Card
        val ocrCard = createCard().apply {
            val title = TextView(this@ProductActivity).apply {
                text = "🌐  Язык распознавания (OCR)"
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(0xFF93C5FD.toInt())
                setPadding(0, 0, 0, dp(8))
            }
            ocrLangButton.apply {
                background = modernButtonDrawable(normalBg = 0xFF334155.toInt(), pressedBg = 0xFF475569.toInt(), radiusDp = 12)
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                minHeight = dp(46)
            }
            addView(title)
            addView(ocrLangButton, matchWrap())
        }
        workspaceMenu.addView(ocrCard, LinearLayout.LayoutParams(-1, -2))
    }

    private fun updateOcrLangButtonText(target: Button? = if (::ocrLangButton.isInitialized) ocrLangButton else null) {
        val button = target ?: return
        val prefs = getSharedPreferences("docscan_app_preferences", Context.MODE_PRIVATE)
        val mode = readLangMode(prefs)
        val modeName = when (mode) {
            app.docscan.core.ocr.OcrLangMode.AUTO -> getString(R.string.ocr_lang_auto)
            app.docscan.core.ocr.OcrLangMode.RU_EN -> getString(R.string.ocr_lang_ru_en)
            app.docscan.core.ocr.OcrLangMode.ES_EN -> getString(R.string.ocr_lang_es_en)
            app.docscan.core.ocr.OcrLangMode.RU_ES_EN -> getString(R.string.ocr_lang_ru_es_en)
        }
        button.text = "${getString(R.string.ocr_lang_setting_title)}: $modeName"
    }

    private fun showOcrLangSelectionDialog() {
        val prefs = getSharedPreferences("docscan_app_preferences", Context.MODE_PRIVATE)
        val modes = app.docscan.core.ocr.OcrLangMode.values()
        val items = modes.map { m ->
            when (m) {
                app.docscan.core.ocr.OcrLangMode.AUTO -> getString(R.string.ocr_lang_auto)
                app.docscan.core.ocr.OcrLangMode.RU_EN -> getString(R.string.ocr_lang_ru_en)
                app.docscan.core.ocr.OcrLangMode.ES_EN -> getString(R.string.ocr_lang_es_en)
                app.docscan.core.ocr.OcrLangMode.RU_ES_EN -> getString(R.string.ocr_lang_ru_es_en)
            }
        }.toTypedArray()
        val current = readLangMode(prefs)
        val selectedIndex = modes.indexOf(current).coerceAtLeast(0)

        dialogHost.builder(this)
            .setTitle(R.string.ocr_lang_setting_title)
            .setSingleChoiceItems(items, selectedIndex) { d, which ->
                val selected = modes[which]
                saveLangMode(prefs, selected)
                updateOcrLangButtonText()
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun lockWorkspace(message: String) {
        workspaceActionBlocked = true
        recordError(message)
        listOf(
            captureButton,
            permissionActionButton,
            documentModeButton,
            ocrButton,
            blackAndWhiteButton,
            importButton,
            destinationButton,
            recentDocumentsButton,
            saveButton,
            selectionButton,
            recoverExportsButton,
            resumeExportsButton,
            deleteSelectedButton,
            clearWorkspaceButton,
        ).forEach { it.isEnabled = false }
    }

    private fun onVersionLabelTapped() {
        val now = SystemClock.elapsedRealtime()
        if (now - versionTapLastElapsed > SELF_TEST_TAP_WINDOW_MILLIS) versionTapCount = 0
        versionTapLastElapsed = now
        versionTapCount += 1
        if (versionTapCount >= SELF_TEST_VERSION_TAPS) {
            versionTapCount = 0
            startActivity(Intent(this, ProductSelfTestActivity::class.java))
        }
    }

    private fun launchDestinationPicker() {
        workspaceMenuDialog?.dismiss()
        destinationPickerPending = true
        try {
            destinationRequest.launch(DestinationPickerIntent.create(storage?.destinationGrant()?.treeUri))
        } catch (failure: Exception) {
            destinationPickerPending = false
            finishDestinationSetup(failure)
        }
    }

    private fun requestMainSave() {
        val activeStorage = storage ?: return
        val count = activeStorage.workspaceProjection().pages.count { it.selected }
        if (workspaceActionBlocked || count !in 1..MAX_SAVE_PAGES || externalWorkflowRunning.get()) return
        showModernSaveDialog()
    }

    /** Entry availability is separate from authorization to compose or write user outputs. */
    private fun prepareSaveDestination(onReady: () -> Unit, onCancelled: (() -> Unit)? = null) {
        val activeStorage = storage ?: return
        if (workspaceActionBlocked || externalWorkflowRunning.get() || !destinationSetupRunning.compareAndSet(false, true)) return
        destinationReadyAction = onReady
        destinationCancelledAction = onCancelled
        destinationButton.isEnabled = false
        updateSaveButtonState(activeStorage)
        status.text = getString(R.string.save_destination_checking)
        io.execute {
            try {
                val grant = activeStorage.destinationGrant()
                val valid = grant != null && SafDestinationGrantManager(this).revalidate(grant, activeStorage)
                if (!valid) {
                    runOnUiThread { if (!isFinishing && !isDestroyed) launchDestinationPicker() }
                } else {
                    checkSaveDestination(activeStorage, requireNotNull(grant).treeUri)
                }
            } catch (failure: Exception) {
                runOnUiThread { finishDestinationSetup(failure) }
            }
        }
    }

    /** Runs only on the I/O executor, never inside a metadata transaction. */
    private fun checkSaveDestination(activeStorage: ProductStorage, treeUri: String) {
        try {
            SafRuntimeDestinationCheck(this, activeStorage).ensureReady(treeUri)
            val grant = activeStorage.destinationGrant()
            check(grant?.treeUri == treeUri && SafDestinationGrantManager(this).revalidate(grant, activeStorage)) {
                getString(R.string.destination_revoked)
            }
            providerSaveReady = true
            runOnUiThread { finishDestinationSetup() }
        } catch (failure: Exception) {
            providerSaveReady = false
            runOnUiThread { finishDestinationSetup(failure) }
        }
    }

    private fun finishDestinationSetup(failure: Exception? = null, cancelled: Boolean = false) {
        val ready = destinationReadyAction ?: if (pendingMainSave) ({ showModernSaveDialog() }) else null
        val cancelAction = destinationCancelledAction
        destinationReadyAction = null
        destinationCancelledAction = null
        pendingMainSave = false
        destinationPickerPending = false
        destinationSetupRunning.set(false)
        if (isFinishing || isDestroyed) return
        destinationButton.isEnabled = !workspaceActionBlocked
        storage?.let(::updateSaveButtonState)
        when {
            cancelled -> cancelAction?.invoke()
            failure != null -> {
                cancelAction?.invoke()
                val message = getString(R.string.save_destination_check_failed, failure.message ?: failure.javaClass.simpleName)
                recordError(message)
                dialogHost.builder(this)
                    .setTitle(R.string.export_selected_pages)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            else -> ready?.invoke()
        }
    }

    private fun handleDestinationResult(result: ActivityResult) {
        destinationPickerPending = false
        if (result.resultCode != RESULT_OK || result.data?.data == null) {
            if (result.resultCode != RESULT_CANCELED) recordError(getString(R.string.destination_failed, "Picker returned no folder"))
            if (destinationSetupRunning.get() || pendingMainSave) finishDestinationSetup(cancelled = true)
            return
        }
        val activeStorage = storage
        if (activeStorage == null) {
            // ActivityResult can precede asynchronous storage initialization after recreation.
            deferredDestinationResult = result
            return
        }
        val data = result.data ?: return
        val continueSave = destinationSetupRunning.get() || pendingMainSave
        destinationSetupRunning.set(true)
        destinationButton.isEnabled = false
        updateSaveButtonState(activeStorage)
        status.text = getString(R.string.save_destination_checking)
        io.execute {
            try {
                val manager = SafDestinationGrantManager(this)
                val previous = activeStorage.destinationGrant()
                val uri = manager.persistTreeGrant(data.data, data.flags)
                activeStorage.recordDestinationGrant(uri)
                val saved = activeStorage.destinationGrant()
                    ?: throw IllegalStateException("Destination grant was not persisted")
                check(manager.revalidate(saved, activeStorage)) { "Persisted SAF grant cannot be revalidated" }
                providerSaveReady = hasQualifiedProvider(activeStorage, saved.treeUri)
                val oldGrantCleanupFailed = previous?.treeUri?.takeIf { it != uri }
                    ?.let { oldUri -> runCatching { manager.releasePersistedGrant(oldUri) }.isFailure } == true
                // A folder selected from the menu needs the same readiness probe as Save setup.
                checkSaveDestination(activeStorage, uri)
                if (!continueSave && providerSaveReady) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            if (oldGrantCleanupFailed) recordError(getString(R.string.destination_saved_cleanup_failed))
                            else status.text = getString(R.string.destination_saved)
                        }
                    }
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    finishDestinationSetup(failure)
                }
            }
        }
    }

    private fun refreshDestinationGrantStatus(activeStorage: ProductStorage) {
        io.execute {
            val grant = activeStorage.destinationGrant()
            if (grant == null) {
                providerSaveReady = false
                updateSaveButtonState(activeStorage)
                return@execute
            }
            val valid = runCatching { SafDestinationGrantManager(this).revalidate(grant, activeStorage) }.getOrDefault(false)
            providerSaveReady = valid && hasQualifiedProvider(activeStorage, grant.treeUri)
            runOnUiThread {
                if (!valid) recordError(getString(R.string.destination_revoked))
                else if (!providerSaveReady && !destinationSetupRunning.get()) status.text = getString(R.string.save_destination_setup_hint)
            }
            updateSaveButtonState(activeStorage)
        }
    }

    private fun hasQualifiedProvider(activeStorage: ProductStorage, treeUri: String): Boolean =
        runCatching { SafRuntimeDestinationCheck(this, activeStorage).findReady(treeUri) != null }.getOrDefault(false)

    private fun updateSaveButtonState(activeStorage: ProductStorage) {
        val selectedCount = runCatching { activeStorage.workspaceProjection().pages.count { it.selected } }.getOrDefault(0)
        runOnUiThread {
            saveButton.isEnabled = !workspaceActionBlocked && !destinationSetupRunning.get() && !externalWorkflowRunning.get()
                && selectedCount in 1..MAX_SAVE_PAGES
            saveButton.text = if (selectedCount > 0) "Save ($selectedCount)" else "Save"
        }
    }

    private fun toggleAllPageSelection() {
        if (!canInteractWorkspace()) return
        val activeStorage = storage ?: return
        val pages = runCatching { activeStorage.workspaceProjection().pages }.getOrNull() ?: return
        if (pages.isEmpty()) return
        val selectAll = pages.any { !it.selected }
        if (::selectionButton.isInitialized) selectionButton.isEnabled = false
        io.execute {
            try {
                activeStorage.setAllVisiblePagesSelected(selectAll)
                val projection = activeStorage.workspaceProjection()
                runOnUiThread {
                    renderPages(projection)
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    recordError(getString(R.string.selection_update_failed, failure.message ?: failure.javaClass.simpleName))
                    if (::selectionButton.isInitialized) selectionButton.isEnabled = true
                }
            }
        }
    }

    private fun showModernSaveDialog() {
        val activeStorage = storage ?: return
        val selectedCount = runCatching { activeStorage.workspaceProjection().pages.count { it.selected } }.getOrDefault(0)
        if (selectedCount == 0 || selectedCount > MAX_SAVE_PAGES || workspaceActionBlocked || externalWorkflowRunning.get()) return

        val now = java.time.LocalDateTime.now()
        val defaultBaseName = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm"))
        val defaultName = "$defaultBaseName.pdf"

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        // Header: /DocScan (no comments or explanations)
        val headerView = TextView(this).apply {
            text = if (activeStorage.destinationGrant() == null) DocScanPublisher.getDocScanDisplayPath() else "Выбранная папка"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFF1E293B.toInt())
            setPadding(0, dp(4), 0, dp(16))
        }
        container.addView(headerView)

        val prefixPrefs = FilePrefixPreferences(this)
        val savedPrefixes = prefixPrefs.getPrefixes().toMutableList()
        val templatePlaceholder = getString(R.string.prefix_template)
        val spinnerItems = mutableListOf(templatePlaceholder).apply { addAll(savedPrefixes) }
        val prefixAdapter = PrefixSpinnerAdapter(this, spinnerItems)

        val prefixRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val prefixSpinner = Spinner(this).apply {
            adapter = prefixAdapter
            background = DropdownBackgroundDrawable(
                backgroundColor = 0xFFF1F5F9.toInt(),
                strokeColor = 0xFFCBD5E1.toInt(),
                arrowColor = 0xFF64748B.toInt(),
                cornerRadius = dp(10).toFloat(),
                strokeWidth = dp(1).toFloat(),
                density = density,
            )
            setPadding(0, 0, 0, 0)
        }

        val prefixAddBtn = TextView(this).apply {
            text = "+"
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFF2563EB.toInt())
            background = GradientDrawable().apply {
                setColor(0xFFF1F5F9.toInt())
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), 0xFFCBD5E1.toInt())
            }
        }

        prefixRow.addView(
            prefixSpinner,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                rightMargin = dp(8)
            },
        )
        prefixRow.addView(prefixAddBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        container.addView(
            prefixRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            },
        )

        val spannable = SpannableStringBuilder(defaultName).apply {
            setSpan(
                BackgroundColorSpan(0x442563EB),
                0,
                defaultBaseName.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        var userHasEdited = false
        var initialSelectionArmed = true

        val input = EditText(this).apply {
            setSingleLine(true)
            setText(spannable)
            textSize = 16f
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(0xFF0F172A.toInt())
            highlightColor = 0x882563EB.toInt()
            background = GradientDrawable().apply {
                setColor(0xFFF1F5F9.toInt())
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), 0xFFCBD5E1.toInt())
            }
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setSelection(0, defaultBaseName.length)
        }

        fun selectBaseName() {
            input.requestFocus()
            val current = input.text?.toString().orEmpty()
            val dotIndex = current.lastIndexOf('.').takeIf { it > 0 } ?: defaultBaseName.length
            val end = minOf(dotIndex, input.text?.length ?: 0)
            if (end > 0) {
                input.setSelection(0, end)
            }
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (before > 0 || count > 0) {
                    userHasEdited = true
                    initialSelectionArmed = false
                    (s as? Spannable)?.let { spanText ->
                        val bgSpans = spanText.getSpans(0, spanText.length, BackgroundColorSpan::class.java)
                        for (span in bgSpans) spanText.removeSpan(span)
                    }
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        input.setOnClickListener {
            if (initialSelectionArmed && !userHasEdited) {
                selectBaseName()
                initialSelectionArmed = false
            }
        }

        input.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.post { selectBaseName() }
                v.postDelayed({ selectBaseName() }, 150)
            }
            override fun onViewDetachedFromWindow(v: View) = Unit
        })

        container.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(16)
        })

        var currentPrefix = ""

        prefixSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {

                val currentText = input.text?.toString().orEmpty()
                if (position == 0) {
                    if (currentPrefix.isNotEmpty()) {
                        val newText = PrefixApplicator.removePrefix(currentText, currentPrefix)
                        currentPrefix = ""
                        input.setText(newText)
                        val dotIndex = newText.lastIndexOf('.').takeIf { it > 0 } ?: newText.length
                        input.setSelection(minOf(newText.length, dotIndex))
                    }
                } else {
                    val chosenPrefix = savedPrefixes.getOrNull(position - 1) ?: return
                    if (chosenPrefix == currentPrefix) return

                    val newText = PrefixApplicator.applyPrefix(currentText, currentPrefix, chosenPrefix)
                    currentPrefix = chosenPrefix
                    input.setText(newText)
                    val dotIndex = newText.lastIndexOf('.').takeIf { it > 0 } ?: newText.length
                    input.setSelection(minOf(newText.length, dotIndex))
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        prefixAddBtn.setOnClickListener {
            PrefixEditDialog.show(this, dialogHost, savedPrefixes) { updatedPrefixes ->
                savedPrefixes.clear()
                savedPrefixes.addAll(updatedPrefixes)

                spinnerItems.clear()
                spinnerItems.add(templatePlaceholder)
                spinnerItems.addAll(savedPrefixes)
                prefixAdapter.notifyDataSetChanged()

                val newPosition = if (currentPrefix.isNotEmpty()) {
                    val index = savedPrefixes.indexOf(currentPrefix)
                    if (index >= 0) index + 1 else 0
                } else {
                    0
                }

                if (newPosition == 0 && currentPrefix.isNotEmpty()) {
                    val currentText = input.text?.toString().orEmpty()
                    val newText = PrefixApplicator.removePrefix(currentText, currentPrefix)
                    currentPrefix = ""
                    input.setText(newText)
                }

                prefixSpinner.setSelection(newPosition)
            }
        }

        val checkContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val ocrCheck = CheckBox(this).apply {
            text = "OCR"
            isChecked = autoOcrEnabled
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF334155.toInt())
        }
        val jpgCheck = CheckBox(this).apply {
            text = "JPG"
            isChecked = true
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF334155.toInt())
        }
        ocrCheck.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked && !jpgCheck.isChecked) {
                jpgCheck.isChecked = true
            }
        }
        jpgCheck.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked && !ocrCheck.isChecked) {
                ocrCheck.isChecked = true
            }
        }

        checkContainer.addView(ocrCheck, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(24)
        })
        checkContainer.addView(jpgCheck)
        container.addView(checkContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })

        val dialog = dialogHost.builder(this)
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val enteredName = input.text.toString().trim()
                val fileName = if (enteredName.isNotBlank()) enteredName else defaultName
                val cleanName = if (fileName.endsWith(".pdf", ignoreCase = true)) fileName else "$fileName.pdf"
                val includeOcr = ocrCheck.isChecked
                val includeJpg = jpgCheck.isChecked
                executeDocScanSave(cleanName, includeOcr, includeJpg)
            }
            .create()

        dialog.window?.let { window ->
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }

        dialog.setOnShowListener {
            selectBaseName()
            input.postDelayed({ selectBaseName() }, 100)
            input.postDelayed({ selectBaseName() }, 250)
        }
        dialog.show()
    }

    private fun promptExportBasename() {
        showModernSaveDialog()
    }

    private fun exportSelectedPages(basename: String) {
        val cleanName = if (basename.endsWith(".pdf", ignoreCase = true)) basename else "$basename.pdf"
        executeDocScanSave(cleanName, includeOcr = true, includeJpg = true)
    }

    private fun executeDocScanSave(cleanPdfName: String, includeOcr: Boolean, includeJpg: Boolean) {
        val activeStorage = storage ?: return
        if (!beginExternalWorkflow()) return
        saveButton.isEnabled = false
        status.text = "Сохранение…"
        io.execute {
            try {
                val selectedCount = activeStorage.workspaceProjection().pages.count { it.selected }
                if (selectedCount == 0) error(getString(R.string.export_no_selected_pages))
                if (selectedCount > MAX_SAVE_PAGES) {
                    error(getString(R.string.export_page_limit, selectedCount, MAX_SAVE_PAGES))
                }

                if (includeOcr) {
                    val pagesNeedingOcr = activeStorage.workspaceProjection().pages.filter { it.selected && it.recognizedText == null }
                    if (pagesNeedingOcr.isNotEmpty()) {
                        runOnUiThread { status.text = "Распознавание текста перед сохранением…" }
                        pagesNeedingOcr.forEach { scheduleOcr(activeStorage, it) }
                    }
                    // ReOCR may still be replacing an older, already available recognition.
                    (application as DocScanApplication).awaitOcrForPages(
                        activeStorage.workspaceProjection().pages.filter { it.selected }.map { it.pageId },
                    )
                    val selected = activeStorage.workspaceProjection().pages.filter { it.selected }
                    check(selected.all { it.recognizedText != null }) {
                        "Распознавание не завершено. Повторите OCR перед сохранением."
                    }
                    check(includeJpg || selected.all { !it.recognizedText.isNullOrBlank() }) {
                        "На одной из страниц не найден текст. Включите JPG или повторите OCR."
                    }
                }

                val frozen = activeStorage.freezeSelectedPagesForSave()
                if (frozen.pages.size != selectedCount) {
                    activeStorage.cancelUnstartedSaveManifest(frozen.saveManifestId)
                    error(getString(R.string.export_selection_changed))
                }
                val outputSet = activeStorage.composeFrozenSaveOutputSet(
                    frozen,
                    PdfOutputSettings(595.28f, 841.89f, 0.85f, 300, includeImage = includeJpg),
                    companionJpegQuality = 85,
                    includeOcr = includeOcr,
                )
                var savedName: String
                var savedPath: String
                var publishedUri: Uri
                val grant = activeStorage.destinationGrant()
                if (grant == null) {
                    val exportId = activeStorage.commitLocalExportOutputSet(frozen, outputSet)
                    val published = DocScanPublisher.publish(
                        context = this@ProductActivity,
                        pdfSource = outputSet.pdfFile,
                        pdfFileName = cleanPdfName,
                        jpegSources = outputSet.pages.map { it.jpegFile },
                        jpegFileNames = emptyList(),
                        saveJpg = includeJpg,
                    )
                    activeStorage.recordVerifiedPublicExport(
                        exportId, published.pdfUri.toString(), published.pdfDisplayName,
                        frozen.pages.size.toLong(), if (!includeOcr) "NONE" else if (outputSet.warnings.isEmpty()) "FULL" else "PARTIAL",
                    )
                    savedName = published.pdfDisplayName
                    savedPath = published.displayPath
                    publishedUri = published.pdfUri
                } else {
                    check(SafDestinationGrantManager(this).revalidate(grant, activeStorage)) {
                        "Нет доступа к выбранной папке. Выберите её повторно через меню ⋮."
                    }
                    val qualification = SafRuntimeDestinationCheck(this, activeStorage).findReady(grant.treeUri)
                        ?: error("Выбранная папка не готова. Выберите её повторно через меню ⋮.")
                    val tuple = resolveProviderTuple(grant.treeUri)
                    val gateway = SafExportGateway(this, activeStorage)
                    val resolution = if (includeJpg) gateway.resolveOutputNames(grant.treeUri, cleanPdfName, frozen.pages.size)
                        else gateway.resolveSinglePdfName(grant.treeUri, cleanPdfName)
                    val names = when (resolution) {
                        is OutputNameResolution.Resolved -> resolution.names
                        is OutputNameResolution.Invalid -> error(resolution.reason)
                        is OutputNameResolution.Collision -> error("Имя уже занято: ${resolution.conflictingNames}")
                    }
                    val plan = ExternalExportPackagePlan(
                        destinationTreeUri = grant.treeUri,
                        commonBasename = names.basename,
                        providerQualificationId = qualification.qualificationId,
                        adapterId = tuple.adapterId,
                        providerVersion = tuple.providerVersion,
                        osVersion = tuple.osVersion,
                        destinationAccessClass = tuple.accessClass,
                        outputDisplayNames = listOf(names.pdfName) + if (includeJpg) names.pageJpegNames else emptyList(),
                        ocrStatus = if (!includeOcr) "NONE" else if (outputSet.warnings.isEmpty()) "FULL" else "PARTIAL",
                    )
                    val exportId = activeStorage.commitLocalExportOutputSet(frozen, outputSet, plan)
                    runExternalExportPackage(activeStorage, gateway, exportId)
                    publishedUri = Uri.parse(activeStorage.recentDocuments().first { it.exportArtifactId == exportId }.documentUri)
                    savedName = names.pdfName
                    savedPath = "выбранная папка"
                }
                activeStorage.cleanupCommittedLocalOutputCandidate(frozen.saveManifestId)
                updateRecentDocuments(activeStorage)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        status.text = "Сохранено: $savedPath/$savedName"
                        showSavedSuccessDialog(savedName, publishedUri, savedPath, outputSet.warnings.isNotEmpty())
                    }
                }
            } catch (failure: Exception) {
                val message = "Ошибка сохранения: ${failure.message ?: failure.javaClass.simpleName}"
                runOnUiThread {
                    recordError(message)
                    Toast.makeText(this@ProductActivity, message, Toast.LENGTH_LONG).show()
                }
            } finally {
                externalWorkflowRunning.set(false)
                val projection = runCatching { activeStorage.workspaceProjection() }.getOrNull()
                runOnUiThread {
                    setWorkspaceInteractionEnabled(true)
                    if (projection != null) renderPages(projection)
                    else {
                        saveButton.isEnabled = false
                        updateBulkActionButtons(emptyList())
                    }
                    updateSaveButtonState(activeStorage)
                    updateBulkActionButtons(projection?.pages.orEmpty())
                    updateRecoveryActions(activeStorage)
                }
            }
        }
    }

    private fun showSavedSuccessDialog(pdfName: String, uri: Uri?, displayPath: String = DocScanPublisher.getDocScanDisplayPath(), textWarning: Boolean = false) {
        val builder = dialogHost.builder(this)
            .setTitle("Файл сохранён")
            .setMessage("Файл успешно сохранён в папку $displayPath:\n$pdfName" +
                if (textWarning) "\n\n" + getString(R.string.saved_text_layer_warning) else "")
            .setPositiveButton("OK", null)

        if (uri != null) {
            builder.setNeutralButton("Открыть PDF") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/pdf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Нет приложения для просмотра PDF", Toast.LENGTH_SHORT).show()
                }
            }
        }
        builder.show()
    }

    private fun recheckInterruptedExports() {
        val activeStorage = storage ?: return
        if (!beginExternalWorkflow()) return
        recoverExportsButton.isEnabled = false
        io.execute {
            try {
                val gateway = SafExportGateway(this, activeStorage)
                val attempts = activeStorage.unknownExportAttempts()
                var resolved = 0
                attempts.forEach { attempt ->
                    val result = gateway.revalidateUnknown(attempt.exportAttemptId)
                    if (result.state != "UNKNOWN_OUTCOME") resolved += 1
                }
                runOnUiThread {
                    status.text = getString(R.string.export_recovery_summary, resolved, attempts.size)
                }
                updateRecentDocuments(activeStorage)
            } catch (failure: Exception) {
                runOnUiThread { recordError(getString(R.string.export_failed, failure.message ?: failure.javaClass.simpleName)) }
            } finally {
                externalWorkflowRunning.set(false)
                val projection = runCatching { activeStorage.workspaceProjection() }.getOrNull()
                runOnUiThread {
                    setWorkspaceInteractionEnabled(true)
                    if (projection != null) renderPages(projection)
                    updateRecoveryActions(activeStorage)
                    updateSaveButtonState(activeStorage)
                    updateBulkActionButtons(projection?.pages.orEmpty())
                }
            }
        }
    }

    private fun resumeIncompleteExports() {
        val activeStorage = storage ?: return
        if (!beginExternalWorkflow()) return
        recoverExportsButton.isEnabled = false
        resumeExportsButton.isEnabled = false
        io.execute {
            try {
                val gateway = SafExportGateway(this, activeStorage)
                val packages = activeStorage.incompleteExternalExportPackages()
                var finished = 0
                packages.forEach { exportPackage ->
                    check(exportPackage.state != "WRITING_EXTERNAL") {
                        "This export is still active; wait for its provider operation to finish"
                    }
                    if (exportPackage.state == "UNKNOWN_OUTCOME") {
                        activeStorage.exportAttempts(exportPackage.exportArtifactId)
                            .filter { it.state == "UNKNOWN_OUTCOME" }
                            .forEach { attempt ->
                                val observed = gateway.revalidateUnknown(attempt.exportAttemptId)
                                check(observed.state != "UNKNOWN_OUTCOME") {
                                    "An earlier output is still uncertain; no new files were written"
                                }
                            }
                    }
                    check(activeStorage.externalExportPackage(exportPackage.exportArtifactId)?.state != "FAILED_KNOWN") {
                        "A prior output failed before write; choose a new basename for a new Save"
                    }
                    runExternalExportPackage(activeStorage, gateway, exportPackage.exportArtifactId)
                    finished += 1
                }
                updateRecentDocuments(activeStorage)
                runOnUiThread { status.text = getString(R.string.export_resume_summary, finished, packages.size) }
            } catch (failure: Exception) {
                runOnUiThread { recordError(getString(R.string.export_failed, failure.message ?: failure.javaClass.simpleName)) }
            } finally {
                externalWorkflowRunning.set(false)
                val projection = runCatching { activeStorage.workspaceProjection() }.getOrNull()
                runOnUiThread {
                    setWorkspaceInteractionEnabled(true)
                    if (projection != null) renderPages(projection)
                    updateRecoveryActions(activeStorage)
                    updateSaveButtonState(activeStorage)
                    updateBulkActionButtons(projection?.pages.orEmpty())
                }
            }
        }
    }

    private fun beginExternalWorkflow(): Boolean {
        if (workspaceActionBlocked) return false
        if (!externalWorkflowRunning.compareAndSet(false, true)) {
            recordError(getString(R.string.workspace_operation_already_running))
            return false
        }
        saveButton.isEnabled = false
        deleteSelectedButton.isEnabled = false
        clearWorkspaceButton.isEnabled = false
        recoverExportsButton.isEnabled = false
        resumeExportsButton.isEnabled = false
        setWorkspaceInteractionEnabled(false)
        return true
    }

    private fun setWorkspaceInteractionEnabled(enabled: Boolean) {
        val canInteract = enabled && !workspaceActionBlocked && !externalWorkflowRunning.get()
        fun setTreeEnabled(view: View, targetEnabled: Boolean) {
            view.isEnabled = targetEnabled
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    setTreeEnabled(view.getChildAt(index), targetEnabled)
                }
            }
        }

        pagesContainer?.let { setTreeEnabled(it, canInteract) }
        setTreeEnabled(homeRail, canInteract)
        homeRail.isEnabled = canInteract
        pagesContainer?.isEnabled = canInteract
        homeThumbnailAdapter.refreshBoundItems()
        workspacePageAdapter?.refreshBoundItems()
    }

    private fun canInteractWorkspace(): Boolean =
        !workspaceActionBlocked && !externalWorkflowRunning.get()

    private fun runExternalExportPackage(
        activeStorage: ProductStorage,
        gateway: SafExportGateway,
        exportArtifactId: ExportArtifactId,
    ): Int {
        val exportPackage = activeStorage.externalExportPackage(exportArtifactId)
            ?: error("Durable external export package is missing")
        check(exportPackage.state != "SUCCEEDED" && exportPackage.state != "FAILED_KNOWN") {
            "External export package is already terminal"
        }
        val outputsById = activeStorage.committedExportOutputs(exportArtifactId).associateBy { it.artifactId }
        val expectedCount = exportPackage.outputs.size
        check(expectedCount > 0 && exportPackage.outputs.all { it.outputArtifactId in outputsById }) { "Package output mapping differs from committed ExportArtifact" }
        var confirmed = 0
        exportPackage.outputs.forEach { packageOutput ->
            val output = outputsById[packageOutput.outputArtifactId]
                ?: error("External package points to a missing committed output")
            val previous = activeStorage.exportAttempts(exportArtifactId)
                .lastOrNull { it.outputArtifactId == output.artifactId }
            when (previous?.state) {
                "SUCCEEDED" -> confirmed += 1
                "UNKNOWN_OUTCOME" -> {
                    val observed = gateway.revalidateUnknown(previous.exportAttemptId)
                    check(observed.state == "SUCCEEDED") {
                        "Output outcome remains uncertain; no retry was made"
                    }
                    confirmed += 1
                }
                "WRITING_EXTERNAL" -> error("Output still has an active external write")
                "FAILED_KNOWN" -> error("Output previously failed; choose a new basename")
                null -> {
                    val command = BeginExportAttemptCommand(
                        exportAttemptId = ExportAttemptId.new(),
                        exportArtifactId = exportArtifactId,
                        outputArtifactId = output.artifactId,
                        destinationTreeUri = exportPackage.destinationTreeUri,
                        plannedDisplayName = packageOutput.displayName,
                        mimeType = output.mimeType,
                        providerQualificationId = exportPackage.providerQualificationId,
                        adapterId = exportPackage.adapterId,
                        providerVersion = exportPackage.providerVersion,
                        osVersion = exportPackage.osVersion,
                        destinationAccessClass = exportPackage.destinationAccessClass,
                    )
                    gateway.export(command)
                    confirmed += 1
                }
                else -> error("Unsupported prior export attempt state: ${previous.state}")
            }
            runOnUiThread { status.text = getString(R.string.export_progress, confirmed, expectedCount) }
        }
        check(activeStorage.externalExportPackage(exportArtifactId)?.state == "SUCCEEDED") {
            "Not every output has a confirmed successful attempt"
        }
        return confirmed
    }

    private fun updateRecoveryActions(activeStorage: ProductStorage) {
        val unknownCount = runCatching { activeStorage.unknownExportAttempts().size }.getOrDefault(0)
        val incomplete = runCatching { activeStorage.incompleteExternalExportPackages() }.getOrDefault(emptyList())
        runOnUiThread {
            recoverExportsButton.isEnabled = !externalWorkflowRunning.get() && unknownCount > 0
            resumeExportsButton.isEnabled = !externalWorkflowRunning.get() && incomplete.any { it.state == "PARTIAL" }
        }
    }

    private fun resolveProviderTuple(treeUri: String): SafProviderIdentity =
        SafProviderIdentity.resolve(this, treeUri)

    private fun updateRecentDocuments(activeStorage: ProductStorage) {
        val recent = runCatching { activeStorage.recentDocuments() }.getOrDefault(emptyList())
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            recentDocumentsButton.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
            recentDocumentsButton.text = recent.firstOrNull()?.displayName
                ?.let { getString(R.string.open_recent_documents_named, it) }
                ?: getString(R.string.open_recent_documents)
        }
    }

    private fun showRecentDocuments() {
        val activeStorage = storage ?: return
        io.execute {
            val recent = runCatching { activeStorage.recentDocuments() }.getOrElse {
                runOnUiThread { recordError(getString(R.string.recent_documents_failed, it.message ?: it.javaClass.simpleName)) }
                return@execute
            }
            runOnUiThread {
                if (recent.isEmpty()) {
                    recentDocumentsButton.visibility = View.GONE
                    return@runOnUiThread
                }
                dialogHost.builder(this)
                    .setTitle(R.string.recent_documents_title)
                    .setItems(recent.map { "${it.displayName} · ${it.pageCount}" }.toTypedArray()) { _, index ->
                        showRecentDocumentActions(recent[index])
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun showRecentDocumentActions(document: app.docscan.core.store.RecentDocumentSnapshot) {
        dialogHost.builder(this)
            .setTitle(document.displayName)
            .setItems(arrayOf(
                getString(R.string.recent_action_open),
                getString(R.string.recent_action_preview),
                getString(R.string.recent_action_edit),
                getString(R.string.recent_action_try_again),
            )) { _, index ->
                when (index) {
                    0 -> openRecentDocument(document.documentUri)
                    1 -> showSavedPdfPreview(document)
                    2 -> showSavedPdfEditor(document)
                    3 -> tryAgainWithSavedPdf(document)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSavedPdfEditor(document: app.docscan.core.store.RecentDocumentSnapshot) {
        val activeStorage = storage ?: return
        val pageCount = document.pageCount.toInt()
        if (pageCount !in 1..MAX_IMPORTED_PDF_PAGES) {
            recordError(getString(R.string.recent_document_unavailable, "Unsupported saved PDF page count"))
            return
        }
        val session = try {
            SavedPdfEditSession(pageCount)
        } catch (failure: Exception) {
            recordError(getString(R.string.recent_document_unavailable, failure.message ?: failure.javaClass.simpleName))
            return
        }

        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.saved_pdf_preview_description)
        }
        val pageLabel = TextView(this).apply { gravity = Gravity.CENTER; textSize = 16f }
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val previous = Button(this).apply { text = getString(R.string.viewer_previous) }
        val next = Button(this).apply { text = getString(R.string.viewer_next) }
        navigation.addView(previous)
        navigation.addView(next)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val orderControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val pageControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val moveUp = Button(this).apply { text = getString(R.string.saved_pdf_edit_move_up) }
        val moveDown = Button(this).apply { text = getString(R.string.saved_pdf_edit_move_down) }
        val delete = Button(this).apply { text = getString(R.string.saved_pdf_edit_delete) }
        val undo = Button(this).apply { text = getString(R.string.saved_pdf_edit_undo) }
        orderControls.addView(moveUp, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        orderControls.addView(moveDown, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pageControls.addView(delete, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pageControls.addView(undo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(orderControls, matchWrap())
        controls.addView(pageControls, matchWrap())
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(pageLabel, matchWrap())
            addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(controls, matchWrap())
        }
        var navigateTo: (Int) -> Unit = {}
        val thumbnailRail = pdfRail(document) { navigateTo(it) }
        content.addView(viewerFooter(thumbnailRail, navigation), LinearLayout.LayoutParams(-1, dp(172)))
        var selectedPosition = 0
        var generation = 0
        var editorBusy = false
        var renderedBitmap: android.graphics.Bitmap? = null
        lateinit var dialog: AlertDialog
        dialog = dialogHost.builder(this)
            .setTitle(R.string.saved_pdf_edit_title)
            .setView(content)
            .setNegativeButton(R.string.saved_pdf_edit_close, null)
            .setPositiveButton(R.string.saved_pdf_edit_save, null)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            thumbnailRail.close()
            generation += 1
            image.setImageDrawable(null)
            renderedBitmap?.recycle()
            renderedBitmap = null
        }

        fun refreshPage() {
            val order = session.pageOrder
            val hasPages = order.isNotEmpty()
            if (!hasPages) {
                thumbnailRail.update(order, 0, editorBusy)
                generation += 1
                image.setImageDrawable(null)
                renderedBitmap?.recycle()
                renderedBitmap = null
                pageLabel.text = getString(R.string.saved_pdf_edit_empty)
                previous.isEnabled = false
                next.isEnabled = false
                moveUp.isEnabled = false
                moveDown.isEnabled = false
                delete.isEnabled = false
                undo.isEnabled = session.canUndo && !editorBusy
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = !editorBusy
                return
            }
            selectedPosition = selectedPosition.coerceIn(0, order.lastIndex)
            thumbnailRail.update(order, selectedPosition, editorBusy)
            val sourcePageIndex = order[selectedPosition]
            pageLabel.text = getString(R.string.saved_pdf_edit_page, selectedPosition + 1, order.size)
            previous.isEnabled = selectedPosition > 0 && !editorBusy
            next.isEnabled = selectedPosition < order.lastIndex && !editorBusy
            moveUp.isEnabled = selectedPosition > 0 && !editorBusy
            moveDown.isEnabled = selectedPosition < order.lastIndex && !editorBusy
            delete.isEnabled = !editorBusy
            undo.isEnabled = session.canUndo && !editorBusy
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = session.isDirty && session.canSave && !editorBusy
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = !editorBusy
            val ticket = ++generation
            pageLabel.text = getString(R.string.saved_pdf_loading, selectedPosition + 1, order.size)
            io.execute {
                try {
                    val rendered = activeStorage.renderRecentSavedPdfPage(
                        document,
                        sourcePageIndex,
                        (application as DocScanApplication).heavyWorkScheduler,
                    )
                    runOnUiThread {
                        if (dialog.isShowing && generation == ticket) {
                            image.setImageDrawable(null)
                            renderedBitmap?.recycle()
                            renderedBitmap = rendered.bitmap
                            image.setImageBitmap(rendered.bitmap)
                            pageLabel.text = getString(R.string.saved_pdf_edit_page, selectedPosition + 1, session.pageCount)
                        } else rendered.bitmap.recycle()
                    }
                } catch (failure: Exception) {
                    runOnUiThread {
                        if (dialog.isShowing && generation == ticket) {
                            image.setImageDrawable(null)
                            renderedBitmap?.recycle()
                            renderedBitmap = null
                            pageLabel.text = getString(
                                R.string.saved_pdf_edit_page_load_failed,
                                failure.message ?: failure.javaClass.simpleName,
                            )
                        }
                    }
                }
            }
        }

        fun setEditorBusy(busy: Boolean) {
            thumbnailRail.update(session.pageOrder, selectedPosition, busy)
            editorBusy = busy
            if (busy) {
                listOf(previous, next, moveUp, moveDown, delete, undo).forEach { it.isEnabled = false }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
            } else if (dialog.isShowing) refreshPage()
        }

        fun promptDirtyExit() {
            if (editorBusy) return
            if (!session.isDirty) {
                dialog.dismiss()
                return
            }
            val prompt = dialogHost.builder(this)
                .setTitle(R.string.saved_pdf_edit_unsaved_title)
                .setNegativeButton(R.string.saved_pdf_edit_discard) { _, _ -> dialog.dismiss() }
                .setNeutralButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.saved_pdf_edit_save, null)
                .create()
            prompt.setOnShowListener {
                prompt.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = session.canSave
                prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    prompt.dismiss()
                    prepareSavedPdfEditSave(document, session, dialog, ::setEditorBusy)
                }
            }
            prompt.show()
        }

        previous.setOnClickListener { if (selectedPosition > 0) { selectedPosition--; refreshPage() } }
        next.setOnClickListener { if (selectedPosition < session.pageCount - 1) { selectedPosition++; refreshPage() } }
        moveUp.setOnClickListener {
            if (selectedPosition > 0) {
                session.move(selectedPosition, selectedPosition - 1)
                selectedPosition--
                refreshPage()
            }
        }
        moveDown.setOnClickListener {
            if (selectedPosition < session.pageCount - 1) {
                session.move(selectedPosition, selectedPosition + 1)
                selectedPosition++
                refreshPage()
            }
        }
        delete.setOnClickListener {
            if (session.canSave) selectedPosition = session.removeAt(selectedPosition).coerceAtLeast(0)
            refreshPage()
        }
        undo.setOnClickListener {
            if (session.undo()) selectedPosition = selectedPosition.coerceIn(0, (session.pageCount - 1).coerceAtLeast(0))
            refreshPage()
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                promptDirtyExit()
                true
            } else false
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { promptDirtyExit() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (session.isDirty && session.canSave) {
                    prepareSavedPdfEditSave(document, session, dialog, ::setEditorBusy)
                }
            }
        }
        navigateTo = { destination ->
            if (!editorBusy && destination in session.pageOrder.indices) {
                selectedPosition = destination
                refreshPage()
            }
        }
        installPageSwipe(image) { delta -> navigateTo(selectedPosition + delta) }
        dialog.show()
        refreshPage()
    }

    private fun prepareSavedPdfEditSave(
        document: app.docscan.core.store.RecentDocumentSnapshot,
        session: SavedPdfEditSession,
        dialog: AlertDialog,
        onBusyChanged: (Boolean) -> Unit,
    ) {
        if (!session.isDirty || !session.canSave) return
        if (workspaceActionBlocked || externalWorkflowRunning.get() || destinationSetupRunning.get()) return
        onBusyChanged(true)
        prepareSaveDestination(
            onReady = {
                onBusyChanged(false)
                if (dialog.isShowing) saveSavedPdfEdit(document, session, dialog, onBusyChanged)
            },
            onCancelled = { onBusyChanged(false) },
        )
    }

    /** The guarded export boundary remains independently safe if access changes after setup. */
    private fun saveSavedPdfEdit(
        document: app.docscan.core.store.RecentDocumentSnapshot,
        session: SavedPdfEditSession,
        dialog: AlertDialog,
        onBusyChanged: (Boolean) -> Unit,
    ) {
        val activeStorage = storage ?: return
        if (!session.isDirty || !session.canSave) return
        if (!beginExternalWorkflow()) return
        val order = session.pageOrder
        onBusyChanged(true)
        status.text = getString(R.string.saved_pdf_edit_saving)
        io.execute {
            var candidate: SavedPdfEditOutput? = null
            var exportId: ExportArtifactId? = null
            try {
                val grant = activeStorage.destinationGrant()
                    ?: error(getString(R.string.export_choose_folder_first))
                val manager = SafDestinationGrantManager(this)
                check(manager.revalidate(grant, activeStorage)) { getString(R.string.destination_revoked) }
                val tuple = resolveProviderTuple(grant.treeUri)
                val qualification = SafRuntimeDestinationCheck(this, activeStorage).findReady(grant.treeUri)
                    ?: error(getString(R.string.save_destination_setup_hint))
                val payload = org.json.JSONObject(qualification.qualifiedCapabilitiesPayload)
                val capabilities = payload.optJSONObject("capabilities") ?: payload
                check(capabilities.optBoolean("createDoesNotOverwriteExistingDocument", false)
                    && capabilities.optBoolean("enumerateChildren", false)
                    && capabilities.optBoolean("readBackSha256", false)
                ) { getString(R.string.export_provider_lacks_required_capabilities) }
                val gateway = SafExportGateway(this, activeStorage)
                val sourceBase = document.displayName.substringBeforeLast('.', document.displayName)
                val basename = "$sourceBase (edited)"
                val outputNames = when (val resolution = gateway.resolveSinglePdfName(grant.treeUri, basename)) {
                    is OutputNameResolution.Resolved -> resolution.names
                    is OutputNameResolution.Invalid -> error(resolution.reason)
                    is OutputNameResolution.Collision -> error(
                        getString(R.string.export_name_collision, resolution.conflictingNames.joinToString()),
                    )
                }
                candidate = SavedPdfEditComposer(this).compose(
                    sourceFile = activeStorage.recentSavedPdfFile(document),
                    expectedPageCount = document.pageCount.toInt(),
                    orderedSourcePageIndexes = order,
                    scheduler = (application as DocScanApplication).heavyWorkScheduler,
                )
                val plan = ExternalExportPackagePlan(
                    destinationTreeUri = grant.treeUri,
                    commonBasename = outputNames.basename,
                    providerQualificationId = qualification.qualificationId,
                    adapterId = tuple.adapterId,
                    providerVersion = tuple.providerVersion,
                    osVersion = tuple.osVersion,
                    destinationAccessClass = tuple.accessClass,
                    outputDisplayNames = listOf(outputNames.pdfName),
                    ocrStatus = document.ocrStatus,
                )
                exportId = activeStorage.commitSavedPdfEditOutput(document, order, candidate, plan)
                runExternalExportPackage(activeStorage, gateway, exportId)
                updateRecentDocuments(activeStorage)
                runOnUiThread {
                    if (dialog.isShowing) dialog.dismiss()
                    status.text = getString(R.string.saved_pdf_edit_saved, outputNames.pdfName)
                }
            } catch (failure: Exception) {
                val detail = failure.message ?: failure.javaClass.simpleName
                val savedAttempts = exportId?.let(activeStorage::exportAttempts).orEmpty()
                val confirmed = savedAttempts.count { it.state == "SUCCEEDED" }
                val unknown = savedAttempts.count { it.state == "UNKNOWN_OUTCOME" || it.state == "WRITING_EXTERNAL" }
                runOnUiThread {
                    val err = if (exportId != null && (confirmed > 0 || unknown > 0)) {
                        getString(R.string.export_partial, confirmed, 1, unknown, detail)
                    } else getString(R.string.saved_pdf_edit_save_failed, detail)
                    recordError(err)
                }
            } finally {
                candidate?.file?.delete()
                externalWorkflowRunning.set(false)
                runOnUiThread { setWorkspaceInteractionEnabled(true) }
                updateSaveButtonState(activeStorage)
                updateRecoveryActions(activeStorage)
                runOnUiThread { if (dialog.isShowing) onBusyChanged(false) }
            }
        }
    }

    internal fun showSavedPdfPreview(document: app.docscan.core.store.RecentDocumentSnapshot) {
        val activeStorage = storage ?: return
        if (document.pageCount !in 1L..MAX_IMPORTED_PDF_PAGES.toLong()) {
            recordError(getString(R.string.recent_document_unavailable, "Unsupported saved PDF page count"))
            return
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.saved_pdf_preview_description)
        }
        val pageLabel = TextView(this).apply { gravity = Gravity.CENTER; textSize = 16f }
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val previous = Button(this).apply { text = getString(R.string.viewer_previous) }
        val next = Button(this).apply { text = getString(R.string.viewer_next) }
        navigation.addView(previous)
        navigation.addView(next)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(pageLabel, matchWrap())
            addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        var navigateTo: (Int) -> Unit = {}
        val thumbnailRail = pdfRail(document) { navigateTo(it) }
        content.addView(viewerFooter(thumbnailRail, navigation), LinearLayout.LayoutParams(-1, dp(172)))
        var pageIndex = 0
        var generation = 0
        var renderedBitmap: android.graphics.Bitmap? = null
        val dialog = dialogHost.builder(this)
            .setTitle(document.displayName)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnDismissListener {
            thumbnailRail.close()
            generation += 1
            image.setImageDrawable(null)
            renderedBitmap?.recycle()
            renderedBitmap = null
        }

        fun loadPage(index: Int) {
            if (index !in 0 until document.pageCount.toInt()) return
            pageIndex = index
            thumbnailRail.update((0 until document.pageCount.toInt()).toList(), index)
            val ticket = ++generation
            previous.isEnabled = false
            next.isEnabled = false
            pageLabel.text = getString(R.string.saved_pdf_loading, index + 1, document.pageCount)
            io.execute {
                try {
                    val rendered = activeStorage.renderRecentSavedPdfPage(
                        document,
                        index,
                        (application as DocScanApplication).heavyWorkScheduler,
                    )
                    runOnUiThread {
                        if (generation == ticket && dialog.isShowing) {
                            image.setImageDrawable(null)
                            renderedBitmap?.recycle()
                            renderedBitmap = rendered.bitmap
                            image.setImageBitmap(rendered.bitmap)
                            pageLabel.text = getString(R.string.saved_pdf_page, index + 1, rendered.pageCount)
                            previous.isEnabled = index > 0
                            next.isEnabled = index < rendered.pageCount - 1
                        } else rendered.bitmap.recycle()
                    }
                } catch (failure: Exception) {
                    runOnUiThread {
                        if (generation == ticket && dialog.isShowing) {
                            image.setImageDrawable(null)
                            renderedBitmap?.recycle()
                            renderedBitmap = null
                            pageLabel.text = getString(
                                R.string.recent_document_unavailable,
                                failure.message ?: failure.javaClass.simpleName,
                            )
                            previous.isEnabled = index > 0
                            next.isEnabled = index < document.pageCount.toInt() - 1
                        }
                    }
                }
            }
        }
        previous.setOnClickListener { loadPage(pageIndex - 1) }
        next.setOnClickListener { loadPage(pageIndex + 1) }
        navigateTo = { index -> loadPage(index) }
        installPageSwipe(image) { delta -> loadPage(pageIndex + delta) }
        dialog.show()
        loadPage(0)
    }

    internal fun tryAgainWithSavedPdf(document: app.docscan.core.store.RecentDocumentSnapshot) {
        val activeStorage = storage ?: return
        if (!savedPdfImportRunning.compareAndSet(false, true)) {
            recordError(getString(R.string.try_again_already_running))
            return
        }
        val importSessionId = app.docscan.core.model.ImportSessionId.new()
        status.text = getString(R.string.try_again_importing, document.displayName)
        importExecutor.execute {
            var failureMessage: String? = null
            var importedPageIds: List<PageId> = emptyList()
            try {
                val savedPdf = activeStorage.committedExportOutputs(document.exportArtifactId)
                    .firstOrNull { it.mimeType == "application/pdf" }
                    ?: throw IllegalStateException("Recent saved PDF has no committed local source")
                check(activeStorage.verifyExportOutput(savedPdf)) {
                    "Recent saved PDF failed its local checksum check"
                }
                val pages = FileInputStream(activeStorage.exportOutputFile(savedPdf)).use { input ->
                    activeStorage.importPdf(
                        input,
                        document.displayName,
                        importSessionId,
                        (application as DocScanApplication).heavyWorkScheduler,
                    ) { completed, total ->
                        runOnUiThread {
                            status.text = getString(R.string.importing_pdf_pages_progress, document.displayName, completed, total)
                        }
                    }
                }
                importedPageIds = pages
            } catch (failure: Exception) {
                failureMessage = failure.message ?: failure.javaClass.simpleName
                importedPageIds = runCatching { activeStorage.importedPdfPageIds(importSessionId) }.getOrDefault(emptyList())
            }
            val projection = try {
                activeStorage.workspaceProjection()
            } catch (failure: Exception) {
                runOnUiThread {
                    recordError(getString(R.string.try_again_failed, failure.message ?: failure.javaClass.simpleName))
                    savedPdfImportRunning.set(false)
                }
                return@execute
            }
            if (autoOcrEnabled) {
                projection.pages.filter { it.pageId in importedPageIds && it.recognizedText == null }
                    .forEach { scheduleOcr(activeStorage, it) }
            }
            runOnUiThread {
                try {
                    val failed = failureMessage
                    if (failed != null) {
                        val err = if (importedPageIds.isEmpty()) getString(R.string.try_again_failed, failed)
                                  else getString(R.string.try_again_partial, importedPageIds.size, failed)
                        recordError(err)
                    } else {
                        status.text = getString(R.string.try_again_complete, importedPageIds.size)
                    }
                    renderPages(projection)
                } finally {
                    savedPdfImportRunning.set(false)
                }
            }
        }
    }

    private fun openRecentDocument(documentUri: String) {
        val uri = Uri.parse(documentUri)
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        } catch (failure: Exception) {
            recordError(getString(R.string.recent_document_unavailable, failure.message ?: failure.javaClass.simpleName))
        }
    }

    private fun startCamera() {
        if (workspaceActionBlocked || isFinishing || cameraStartupPending || imageCapture != null || !hasCameraPermission()) return
        cameraStartupPending = true
        val statusAtStart = status.text?.toString()
        liveDetectionCoordinator.newGeneration()
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.getSurfaceProvider()) }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    android.util.Size(1280, 960),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                ),
                            )
                            .build(),
                    )
                    .build()
                analysis.setAnalyzer(cameraAnalysisExecutor) { imageProxy ->
                    val receivedGeneration = liveDetectionCoordinator.currentGeneration()
                    if (!isDocumentMode) {
                        liveDetectionCoordinator.onFrameMissing(receivedGeneration, SystemClock.elapsedRealtime())
                        runOnUiThread {
                            if (!isDocumentMode && receivedGeneration == liveDetectionCoordinator.currentGeneration() &&
                                ::documentOverlayView.isInitialized) documentOverlayView.clear()
                        }
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastAnalyzedTimestamp < 100) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    lastAnalyzedTimestamp = now

                    try {
                        val cropRect = imageProxy.cropRect
                        val spec = AnalysisBufferSpec(
                            bufferWidth = imageProxy.width,
                            bufferHeight = imageProxy.height,
                            cropRect = BufferRect(cropRect.left, cropRect.top, cropRect.right, cropRect.bottom),
                            rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                        )
                        val frameGeneration = liveDetectionCoordinator.generationForFrame(spec, receivedGeneration)
                            ?: return@setAnalyzer
                        val plane = imageProxy.planes[0]
                        val detection = DocumentCropProcessor.detectCornersFromRgbaBuffer(
                            plane.buffer,
                            imageProxy.width,
                            imageProxy.height,
                            plane.rowStride,
                        )

                        val liveState = if (detection == null) {
                            liveDetectionCoordinator.onFrameMissing(frameGeneration, now)
                        } else {
                            liveDetectionCoordinator.onFrameDetected(
                                generationId = frameGeneration,
                                frameTimestampMonotonicMs = now,
                                rawBufferPoints = detection.bufferPoints,
                                rawScore = detection.score,
                                spec = spec,
                            )
                        }

                        if (liveState.generationId != frameGeneration) return@setAnalyzer
                        val sourceTransform = imageTransformFactory.getOutputTransform(imageProxy)
                        val pointsToMap = liveState.quadInBufferPixels?.clone()
                        val isHeld = liveState.status == LiveDetectionStatus.HELD
                        val showOverlay = (liveState.status == LiveDetectionStatus.CONFIRMED || liveState.status == LiveDetectionStatus.HELD) && pointsToMap != null

                        runOnUiThread {
                            if (!isDocumentMode || !::documentOverlayView.isInitialized ||
                                frameGeneration != liveDetectionCoordinator.currentGeneration() ||
                                isFinishing || isDestroyed) return@runOnUiThread
                            // A callback may spend its entire evidence lifetime waiting for main.
                            // An older queued callback must not clear a more recent publication.
                            if (!liveDetectionCoordinator.isCurrent(liveState)) return@runOnUiThread
                            if (!showOverlay || !liveDetectionCoordinator.canDisplay(liveState)) {
                                documentOverlayView.clear()
                            } else {
                                val targetTransform = previewView.outputTransform
                                if (targetTransform != null) {
                                    try {
                                        val coordinateTransform = CoordinateTransform(sourceTransform, targetTransform)
                                        coordinateTransform.mapPoints(pointsToMap)
                                        documentOverlayView.setPolygonPixels(pointsToMap, isHeld = isHeld,
                                            deadlineMonotonicMs = liveState.visualDeadlineMonotonicMs,
                                            isCurrent = { isDocumentMode && !isFinishing && !isDestroyed &&
                                                liveDetectionCoordinator.canDisplay(liveState) })
                                    } catch (transformError: Throwable) {
                                        documentOverlayView.clear()
                                        android.util.Log.w("DocScan", "CoordinateTransform mapping failed", transformError)
                                    }
                                } else {
                                    documentOverlayView.clear()
                                }
                            }
                        }
                    } catch (failure: Exception) {
                        // Forget a failed frame; never leave an apparently usable old contour.
                        if (receivedGeneration == liveDetectionCoordinator.currentGeneration()) {
                            liveDetectionCoordinator.invalidate()
                            val failedGeneration = liveDetectionCoordinator.currentGeneration()
                            runOnUiThread {
                                if (failedGeneration == liveDetectionCoordinator.currentGeneration() &&
                                    ::documentOverlayView.isInitialized && !isDestroyed) documentOverlayView.clear()
                            }
                        }
                        android.util.Log.e("DocScan", "Document live detection failed", failure)
                    } finally {
                        imageProxy.close()
                    }
                }
                provider.unbindAll()
                val viewPort = previewView.viewPort
                if (viewPort != null) {
                    val useCaseGroup = UseCaseGroup.Builder()
                        .setViewPort(viewPort)
                        .addUseCase(preview)
                        .addUseCase(capture)
                        .addUseCase(analysis)
                        .build()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, useCaseGroup)
                } else {
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
                }
                imageCapture = capture
                imageAnalysis = analysis
                cameraStartupPending = false
                captureButton.isEnabled = true
                updateCameraPreviewFilter()
                if (status.text?.toString() == statusAtStart) {
                    status.text = getString(R.string.camera_ready)
                }
            } catch (failure: Exception) {
                cameraStartupPending = false
                if (status.text?.toString() == statusAtStart) {
                    recordError(getString(R.string.camera_error, failure.message ?: failure.javaClass.simpleName))
                }
                captureButton.isEnabled = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePicture() {
        val capture = imageCapture ?: return
        val activeStorage = storage ?: return
        uiHapticFeedback(captureButton)
        captureButton.isEnabled = false
        val pageBeingRetaken = retakeTarget
        val captureDocumentMode = isDocumentMode
        val captureBlackAndWhiteMode = isBlackAndWhiteMode
        val shutterTimestampMs = SystemClock.elapsedRealtime()
        val generationAtShutter = liveDetectionCoordinator.currentGeneration()
        val liveSnapshot = if (captureDocumentMode) liveDetectionCoordinator.captureSnapshot() else null
        status.text = getString(R.string.capturing)
        val temp = File(cacheDir, "capture-${System.nanoTime()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(temp).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                // Instantly re-enable shutter button for non-blocking burst capture
                captureButton.isEnabled = pageBeingRetaken == null && imageCapture != null
                io.execute {
                    try {
                        // Publish the untouched camera JPEG before producing any derivative.
                        val captureSource = FileInputStream(temp).use(activeStorage::stageCapturedJpeg)
                        var wasRectified = false
                        var cropStatusRes: Int? = null
                        var prepared: File? = null
                        try {
                            if (captureDocumentMode) {
                                val jpegBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeFile(temp.absolutePath, jpegBounds)
                                val exif = ExifInterface(temp)
                                val rotation = exif.rotationDegrees
                                val orientedW = if (rotation == 90 || rotation == 270) jpegBounds.outHeight else jpegBounds.outWidth
                                val orientedH = if (rotation == 90 || rotation == 270) jpegBounds.outWidth else jpegBounds.outHeight
                                val jpegSpec = JpegGeometrySpec(orientedW, orientedH)

                                val heavyScheduler = (application as DocScanApplication).heavyWorkScheduler
                                val stillQuad = heavyScheduler.withAdmission(
                                    DocumentCropMemoryCalculator.ESTIMATED_DETECTION_OVERHEAD_BYTES,
                                ) {
                                    val thumbnail = ProductImageDecoder.decodeThumbnailForDetection(temp)
                                    try { DocumentCropProcessor.detectCorners(thumbnail)?.toNormalized() }
                                    finally { thumbnail.recycle() }
                                }

                                val decision = DocumentCapturePolicy.decide(stillQuad, liveSnapshot, jpegSpec,
                                    generationAtShutter, shutterTimestampMs)
                                if (decision == DocumentCaptureDecision.NO_STILL_BOUNDARY) {
                                    cropStatusRes = R.string.document_not_detected_full_frame_saved
                                } else {
                                    if (decision == DocumentCaptureDecision.BOUNDARIES_DISAGREE) {
                                        cropStatusRes = R.string.document_boundaries_not_confirmed_full_frame_saved
                                    } else {
                                        val cropQuad = requireNotNull(stillQuad)
                                        val work = DocumentCropMemoryCalculator.planWarp(orientedW, orientedH, cropQuad,
                                            maxBudgetBytes = heavyScheduler.decodedByteBudget)
                                        val output = File(cacheDir, "rectified-${System.nanoTime()}.jpg")
                                        prepared = output
                                        heavyScheduler.withAdmission(work.workingSetBytes) {
                                            val oriented = ProductImageDecoder.decodePrecisely(temp, work.sourcePixelLimit)
                                            try {
                                                val rectified = DocumentCropProcessor.rectifyManual(oriented, cropQuad)
                                                try {
                                                    val finalBitmap = if (captureBlackAndWhiteMode) {
                                                        applyBlackAndWhiteFilter(rectified)
                                                    } else rectified
                                                    try {
                                                        FileOutputStream(output).use { out ->
                                                            check(finalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out))
                                                            out.fd.sync()
                                                        }
                                                    } finally {
                                                        if (finalBitmap !== rectified) finalBitmap.recycle()
                                                    }
                                                } finally { rectified.recycle() }
                                            } finally { oriented.recycle() }
                                        }
                                        wasRectified = true
                                        cropStatusRes = R.string.document_rectified_and_cropped
                                    }
                                }
                            }
                        } catch (failure: Exception) {
                            android.util.Log.w("DocScan", "Rectification unavailable; keeping camera source", failure)
                            prepared?.delete()
                            prepared = null
                            wasRectified = false
                            cropStatusRes = R.string.document_save_error_kept_original
                        } catch (memory: OutOfMemoryError) {
                            android.util.Log.w("DocScan", "Not enough memory to rectify; retaining camera source", memory)
                            prepared?.delete()
                            prepared = null
                            wasRectified = false
                            cropStatusRes = R.string.document_save_oom_kept_original
                        }
                        if (captureBlackAndWhiteMode && prepared == null) {
                            try {
                                val heavyScheduler = (application as DocScanApplication).heavyWorkScheduler
                                val output = File(cacheDir, "bw-${System.nanoTime()}.jpg")
                                val workingBytes = temp.length() * 4L
                                heavyScheduler.withAdmission(workingBytes) {
                                    val decoded = ProductImageDecoder.decodePrecisely(temp, maxPixels = 16L * 1024L * 1024L)
                                    try {
                                        val bwBitmap = applyBlackAndWhiteFilter(decoded)
                                        try {
                                            FileOutputStream(output).use { out ->
                                                check(bwBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out))
                                                out.fd.sync()
                                            }
                                            prepared = output
                                        } finally {
                                            if (bwBitmap !== decoded) bwBitmap.recycle()
                                        }
                                    } finally {
                                        decoded.recycle()
                                    }
                                }
                            } catch (failure: Exception) {
                                android.util.Log.w("DocScan", "Black-and-white conversion failed; retaining original", failure)
                                prepared?.delete()
                                prepared = null
                            }
                        }
                        val artifact = try {
                            prepared?.let { FileInputStream(it).use(activeStorage::stageCapturedJpeg) } ?: captureSource
                        } finally { prepared?.delete() }
                        val affectedPageId: PageId
                        if (pageBeingRetaken != null) {
                            activeStorage.replacePageImage(pageBeingRetaken, artifact, captureSource)
                            affectedPageId = pageBeingRetaken.pageId
                        } else {
                            val pageId = PageId.new()
                            affectedPageId = pageId
                            val versionId = ImageVersionId.new()
                            activeStorage.commitCapture(
                                CaptureCommand(
                                    workspaceId = activeStorage.workspaceId,
                                    pageId = pageId,
                                    imageVersionId = versionId,
                                    artifact = artifact,
                                    selected = false,
                                    makeActive = true,
                                    coordinateSpace = "JPEG_PIXEL_EDGE_ORIGIN_TOP_LEFT",
                                    processingDesiredState = OCR_OPERATION_KEY,
                                    sourceProvenance = "CAMERA",
                                    autoSelectWhenReady = true,
                                    captureSource = captureSource,
                                ),
                            )
                        }
                        val projection = activeStorage.workspaceProjection()
                        if (autoOcrEnabled) {
                            projection.pages.firstOrNull { it.pageId == affectedPageId }?.let {
                                scheduleOcr(activeStorage, it)
                            }
                        }
                        runOnUiThread {
                            captureButton.isEnabled = imageCapture != null
                            retakeTarget = null
                            if (cropStatusRes == R.string.document_save_error_kept_original || cropStatusRes == R.string.document_save_oom_kept_original) {
                                recordError(getString(cropStatusRes))
                            } else if (cropStatusRes != null) {
                                status.text = getString(cropStatusRes)
                            } else {
                                status.text = getString(if (pageBeingRetaken == null) R.string.capture_saved else R.string.retake_saved)
                            }
                            renderPages(projection)
                        }
                    } catch (failure: Exception) {
                        runOnUiThread {
                            captureButton.isEnabled = imageCapture != null
                            retakeTarget = null
                            recordError(getString(R.string.capture_error, failure.message ?: failure.javaClass.simpleName))
                        }
                    } finally {
                        temp.delete()
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                temp.delete()
                captureButton.isEnabled = imageCapture != null
                retakeTarget = null
                recordError(getString(R.string.capture_error, exception.message ?: exception.imageCaptureError.toString()))
            }
        })
    }

    internal fun renderPages(projection: app.docscan.core.store.WorkspaceProjection) {
        lastWorkspaceProjection = projection
        refreshHomeRail(projection)
        val pages = projection.pages
        val selectedCount = pages.count { it.selected }
        saveButton.isEnabled = !workspaceActionBlocked && !destinationSetupRunning.get() && !externalWorkflowRunning.get()
            && selectedCount in 1..MAX_SAVE_PAGES
        saveButton.text = if (selectedCount > 0) {
            "Save ($selectedCount)"
        } else {
            "Save"
        }
        deleteSelectedButton.isEnabled = !externalWorkflowRunning.get() && selectedCount > 0
        clearWorkspaceButton.isEnabled = !externalWorkflowRunning.get() && pages.isNotEmpty()
        selectionButton.isEnabled = !externalWorkflowRunning.get() && pages.isNotEmpty()
        val allSelected = pages.isNotEmpty() && pages.all { it.selected }
        selectionButton.text = "Select"
        selectionButton.contentDescription = getString(if (allSelected) R.string.deselect_all_pages else R.string.select_all_pages)
        if (::ocrButton.isInitialized) {
            ocrButton.isEnabled = !externalWorkflowRunning.get() && !workspaceActionBlocked
        }
        if (::blackAndWhiteButton.isInitialized) {
            blackAndWhiteButton.isEnabled = !externalWorkflowRunning.get() && !workspaceActionBlocked
        }
        if (workspaceMenuDialog?.isShowing == true) {
            workspaceEmptyLabel?.visibility = if (pages.isEmpty()) View.VISIBLE else View.GONE
            workspacePageAdapter?.submitProjection(projection)
        }
    }

    private fun bindWorkspacePageThumbnail(
        row: View,
        projection: app.docscan.core.store.WorkspaceProjection,
        index: Int,
    ) {
        val page = projection.pages.getOrNull(index) ?: return
        if (page.artifactState != "COMMITTED") return
        val image = row.findViewWithTag<ImageView>(WORKSPACE_PAGE_THUMBNAIL_TAG_PREFIX + page.pageId.value) ?: return
        val file = thumbnailFile(page)
        workspaceThumbnailLoader.bind(
            image,
            WorkspaceThumbnailLoader.Key(
                pageId = page.pageId,
                imageVersionId = page.imageVersionId,
                sourceSizeBytes = file.length(),
                targetWidthPx = dp(92) * 2,
                targetHeightPx = dp(72) * 2,
            ),
            file,
        )
    }

    private fun createWorkspacePageRow(
        projection: app.docscan.core.store.WorkspaceProjection,
        index: Int,
    ): View {
        val activeStorage = storage ?: return View(this)
        val pages = projection.pages
        val page = pages[index]
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            tag = PAGE_ROW_TAG_PREFIX + page.pageId.value
        }
        val insertBefore = View(this).apply {
            setBackgroundColor(0xff1976d2.toInt())
            visibility = View.GONE
            contentDescription = getString(R.string.insert_before_page, index + 1)
            tag = PAGE_DROP_BEFORE_TAG_PREFIX + page.pageId.value
        }
        val insertAfter = View(this).apply {
            setBackgroundColor(0xff1976d2.toInt())
            visibility = View.GONE
            contentDescription = getString(R.string.insert_after_page, index + 1)
            tag = PAGE_DROP_AFTER_TAG_PREFIX + page.pageId.value
        }
        row.addView(insertBefore, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val image = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            if (page.artifactState == "COMMITTED") setImageDrawable(android.graphics.drawable.ColorDrawable(0xff424242.toInt()))
                contentDescription = if (projection.activePageId == page.pageId) {
                    getString(R.string.active_page, index + 1)
                } else {
                    getString(R.string.page_image_description, index + 1)
                }
                tag = WORKSPACE_PAGE_THUMBNAIL_TAG_PREFIX + page.pageId.value
                background = pageThumbnailFrame(projection.activePageId == page.pageId)
                setPadding(dp(3), dp(3), dp(3), dp(3))
                setOnClickListener {
                    if (canInteractWorkspace()) showPageViewer(pages, index)
                }
            }
            installWorkspacePageSwipeDelete(image, page)
            header.addView(image, LinearLayout.LayoutParams(dp(92), dp(72)))
            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            details.addView(TextView(this).apply {
                text = getString(R.string.page_number, index + 1)
                textSize = 17f
            })
            details.addView(CheckBox(this).apply {
                text = getString(R.string.include_in_save)
                contentDescription = getString(R.string.include_page_in_save, index + 1)
                minimumHeight = dp(48)
                tag = WORKSPACE_PAGE_SELECTION_TAG_PREFIX + page.pageId.value
                isChecked = page.selected
                var restoringSelection = false
                setOnCheckedChangeListener { _, checked ->
                    if (restoringSelection) return@setOnCheckedChangeListener
                    if (!canInteractWorkspace()) {
                        restoringSelection = true
                        isChecked = page.selected
                        restoringSelection = false
                        return@setOnCheckedChangeListener
                    }
                    io.execute {
                        try {
                            activeStorage.setSelected(page.pageId, checked)
                            val projection = activeStorage.workspaceProjection()
                            runOnUiThread { renderPages(projection) }
                        } catch (failure: Exception) {
                            runOnUiThread { recordError(getString(R.string.capture_error, failure.message ?: "")) }
                        }
                    }
                }
            })
            details.addView(TextView(this).apply {
                text = page.recognizedText?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.ocr_pending_or_empty)
                textSize = 13f
                maxLines = 3
            })
            if (page.artifactState == "QUARANTINED") {
                details.addView(TextView(this).apply {
                    text = getString(R.string.page_image_quarantined)
                    contentDescription = text
                })
            }
            header.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val dragHandle = TextView(this).apply {
                text = getString(R.string.reorder_page_handle)
                contentDescription = getString(R.string.reorder_page_handle_description, index + 1)
                gravity = Gravity.CENTER
                minimumWidth = dp(48)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isLongClickable = true
                tag = PAGE_DRAG_HANDLE_TAG_PREFIX + page.pageId.value
                setOnLongClickListener {
                    if (!canInteractWorkspace()) return@setOnLongClickListener false
                    val data = android.content.ClipData.newPlainText(WORKSPACE_PAGE_DRAG_LABEL, page.pageId.value)
                    startDragAndDrop(
                        data,
                        View.DragShadowBuilder(row),
                        page.pageId.value,
                        0,
                    )
                }
            }
            header.addView(dragHandle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val actionRail = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = true
                isFillViewport = false
                addView(actions, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            }
            fun addPageAction(button: Button) {
                button.minimumWidth = dp(48)
                button.minimumHeight = dp(48)
                actions.addView(button, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            }
            val moveUp = Button(this).apply {
                text = getString(R.string.move_page_up)
                contentDescription = getString(R.string.move_page_up_description, index + 1)
                minimumWidth = dp(48)
                isEnabled = index > 0
                setOnClickListener { movePage(projection, index, index - 1) }
            }
            val moveDown = Button(this).apply {
                text = getString(R.string.move_page_down)
                contentDescription = getString(R.string.move_page_down_description, index + 1)
                minimumWidth = dp(48)
                isEnabled = index < pages.lastIndex
                setOnClickListener { movePage(projection, index, index + 1) }
            }
            addPageAction(moveUp)
            addPageAction(moveDown)
            addPageAction(Button(this).apply {
                text = getString(R.string.recognize_text)
                minimumWidth = dp(48)
                isEnabled = page.artifactState == "COMMITTED"
                setOnClickListener { if (canInteractWorkspace()) scheduleOcr(activeStorage, page, force = true) }
            })
            addPageAction(Button(this).apply {
                text = getString(R.string.retake_page)
                minimumWidth = dp(48)
                setOnClickListener {
                    if (!canInteractWorkspace()) return@setOnClickListener
                    retakeTarget = page
                    status.text = getString(R.string.retake_instruction, index + 1)
                    workspaceMenuDialog?.dismiss()
                }
            })
            addPageAction(Button(this).apply {
                text = getString(R.string.edit_image)
                minimumWidth = dp(48)
                tag = PAGE_IMAGE_EDIT_BUTTON_TAG_PREFIX + page.pageId.value
                isEnabled = page.artifactState == "COMMITTED"
                setOnClickListener { if (canInteractWorkspace()) showPageImageEditor(page) }
            })
            addPageAction(Button(this).apply {
                text = getString(R.string.auto_crop_page)
                minimumWidth = dp(48)
                isEnabled = page.artifactState == "COMMITTED"
                setOnClickListener { if (canInteractWorkspace()) showDocumentCropEditor(page) }
            })
            if (activeStorage.cameraSource(page.pageId) != null) {
                addPageAction(Button(this).apply {
                    text = getString(R.string.restore_camera_source)
                    contentDescription = text
                    tag = "docscan-restore-source-${page.pageId.value}"
                    setOnClickListener {
                        if (!canInteractWorkspace()) return@setOnClickListener
                        isEnabled = false
                        io.execute {
                            try {
                                val restored = activeStorage.restoreCameraSource(page)
                                val projection = activeStorage.workspaceProjection()
                                projection.pages.firstOrNull { it.pageId == restored }?.let { scheduleOcr(activeStorage, it) }
                                runOnUiThread { workspaceMenuDialog?.dismiss(); renderPages(projection) }
                            } catch (failure: Exception) {
                                runOnUiThread {
                                    isEnabled = true
                                    recordError(getString(R.string.capture_error, failure.message ?: ""))
                                }
                            }
                        }
                    }
                })
            }
            addPageAction(Button(this).apply {
                text = getString(R.string.delete_page)
                minimumWidth = dp(48)
                tag = WORKSPACE_PAGE_DELETE_BUTTON_TAG_PREFIX + page.pageId.value
                setOnClickListener { deleteWorkspacePage(page) }
            })
            if (page.canUndoImage) {
                addPageAction(Button(this).apply {
                    text = getString(R.string.undo_page)
                    minimumWidth = dp(48)
                    setOnClickListener {
                        if (!canInteractWorkspace()) return@setOnClickListener
                        io.execute {
                            try {
                                activeStorage.undoPageImage(page)
                                val refreshed = activeStorage.workspaceProjection()
                                runOnUiThread {
                                    status.text = getString(R.string.undo_complete)
                                    renderPages(refreshed)
                                    refreshed.pages.firstOrNull { it.pageId == page.pageId }?.let { restored ->
                                        if (restored.recognizedText == null) scheduleOcr(activeStorage, restored)
                                    }
                                }
                            } catch (failure: Exception) {
                                runOnUiThread { recordError(getString(R.string.capture_error, failure.message ?: "")) }
                            }
                        }
                    }
                })
            }
            row.addView(header, matchWrap())
            row.addView(actionRail, matchWrap())
            row.addView(insertAfter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
            row.setOnDragListener { _, event ->
                val description = event.clipDescription
                val pageDrag = description?.label?.toString() == WORKSPACE_PAGE_DRAG_LABEL &&
                    description.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN)
                if (!pageDrag || !canInteractWorkspace()) return@setOnDragListener false
                when (event.action) {
                    android.view.DragEvent.ACTION_DRAG_STARTED -> {
                        if (event.localState == page.pageId.value) row.alpha = 0.55f
                        true
                    }
                    android.view.DragEvent.ACTION_DRAG_ENTERED,
                    android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                        val after = event.y >= row.height / 2f
                        insertBefore.visibility = if (after) View.GONE else View.VISIBLE
                        insertAfter.visibility = if (after) View.VISIBLE else View.GONE
                        true
                    }
                    android.view.DragEvent.ACTION_DRAG_EXITED -> {
                        insertBefore.visibility = View.GONE
                        insertAfter.visibility = View.GONE
                        true
                    }
                    android.view.DragEvent.ACTION_DROP -> {
                        val sourcePageId = event.clipData?.takeIf { it.itemCount == 1 }
                            ?.getItemAt(0)?.text?.toString()
                        if (sourcePageId != null) {
                            movePageByDrop(sourcePageId, page.pageId.value, event.y >= row.height / 2f)
                        }
                        true
                    }
                    android.view.DragEvent.ACTION_DRAG_ENDED -> {
                        row.alpha = 1f
                        insertBefore.visibility = View.GONE
                        insertAfter.visibility = View.GONE
                        true
                    }
                    else -> true
                }
            }


            return row
    }

    private fun movePage(projection: app.docscan.core.store.WorkspaceProjection, from: Int, to: Int) {
        if (!canInteractWorkspace()) return
        val activeStorage = storage ?: return
        val reordered = projection.pages.toMutableList()
        val moving = reordered.removeAt(from)
        reordered.add(to, moving)
        io.execute {
            try {
                activeStorage.reorderPages(projection, reordered)
                val refreshed = activeStorage.workspaceProjection()
                runOnUiThread {
                    status.text = getString(R.string.page_order_updated)
                    uiHapticFeedback(root)
                    renderPages(refreshed)
                }
            } catch (failure: Exception) {
                runOnUiThread { recordError(getString(R.string.capture_error, failure.message ?: "")) }
            }
        }
    }

    internal fun movePageByDrop(sourcePageId: String, targetPageId: String, afterTarget: Boolean) {
        if (!canInteractWorkspace()) return
        val activeStorage = storage ?: return
        io.execute {
            try {
                val projection = activeStorage.workspaceProjection()
                val from = projection.pages.indexOfFirst { it.pageId.value == sourcePageId }
                val target = projection.pages.indexOfFirst { it.pageId.value == targetPageId }
                if (from < 0 || target < 0 || sourcePageId == targetPageId) return@execute
                val insertionPoint = target + if (afterTarget) 1 else 0
                val finalIndex = if (insertionPoint > from) insertionPoint - 1 else insertionPoint
                if (finalIndex == from) return@execute
                val reordered = projection.pages.toMutableList()
                val moving = reordered.removeAt(from)
                reordered.add(finalIndex, moving)
                activeStorage.reorderPages(projection, reordered)
                val refreshed = activeStorage.workspaceProjection()
                runOnUiThread {
                    status.text = getString(R.string.page_order_updated)
                    uiHapticFeedback(root)
                    renderPages(refreshed)
                }
            } catch (failure: Exception) {
                runOnUiThread { recordError(getString(R.string.capture_error, failure.message ?: "")) }
            }
        }
    }

    private fun deleteWorkspacePage(page: app.docscan.core.store.StoredPage) {
        val activeStorage = storage ?: return
        if (!beginExternalWorkflow()) return
        io.execute {
            try {
                (application as DocScanApplication).cancelOcrForPages(listOf(page.pageId))
                val receipt = activeStorage.deletePage(page)
                val projection = activeStorage.workspaceProjection()
                runOnUiThread {
                    status.text = getString(R.string.page_deleted)
                    renderPages(projection)
                    Snackbar.make(dialogHost.foregroundView ?: root, R.string.page_deleted, 2_000)
                        .setAction(R.string.undo_delete) { undoWorkspaceDelete(receipt.pageId) }
                        .show()
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    status.text = getString(R.string.capture_error, failure.message ?: failure.javaClass.simpleName)
                }
            } finally {
                externalWorkflowRunning.set(false)
                val refreshed = runCatching { activeStorage.workspaceProjection() }.getOrNull()
                runOnUiThread {
                    setWorkspaceInteractionEnabled(true)
                    if (refreshed != null) {
                        renderPages(refreshed)
                        updateRecoveryActions(activeStorage)
                    } else {
                        saveButton.isEnabled = false
                        updateBulkActionButtons(emptyList())
                    }
                    updateSaveButtonState(activeStorage)
                    updateBulkActionButtons(refreshed?.pages.orEmpty())
                }
            }
        }
    }

    private fun installWorkspacePageSwipeDelete(image: ImageView, page: app.docscan.core.store.StoredPage) {
        val touchSlop = android.view.ViewConfiguration.get(image.context).scaledTouchSlop
        var startX = 0f
        var startY = 0f
        var horizontalSwipe = false
        var verticalScroll = false
        image.setOnTouchListener { view, event ->
            if (!canInteractWorkspace()) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    horizontalSwipe = false
                    verticalScroll = false
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.x - startX
                    val deltaY = event.y - startY
                    if (!horizontalSwipe && !verticalScroll && kotlin.math.abs(deltaY) > touchSlop && kotlin.math.abs(deltaY) > kotlin.math.abs(deltaX)) {
                        verticalScroll = true
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        return@setOnTouchListener false
                    }
                    if (!horizontalSwipe && !verticalScroll && kotlin.math.abs(deltaX) > touchSlop && kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY)) {
                        horizontalSwipe = true
                        view.isPressed = false
                        view.cancelLongPress()
                    }
                    horizontalSwipe
                }
                MotionEvent.ACTION_UP -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (verticalScroll) {
                        verticalScroll = false
                        return@setOnTouchListener false
                    }
                    if (!horizontalSwipe) return@setOnTouchListener false
                    val distance = kotlin.math.abs(event.x - startX)
                    val deleteThreshold = maxOf(view.width / 3, touchSlop * 3)
                    horizontalSwipe = false
                    if (distance >= deleteThreshold) deleteWorkspacePage(page)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    val wasHorizontalSwipe = horizontalSwipe
                    horizontalSwipe = false
                    verticalScroll = false
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    wasHorizontalSwipe
                }
                else -> horizontalSwipe
            }
        }
    }

    private fun updateBulkActionButtons(pages: List<app.docscan.core.store.StoredPage>) {
        runOnUiThread {
            deleteSelectedButton.isEnabled = !externalWorkflowRunning.get() && pages.any { it.selected }
            clearWorkspaceButton.isEnabled = !externalWorkflowRunning.get() && pages.isNotEmpty()
        }
    }

    private fun deleteSinglePage(pageId: app.docscan.core.model.PageId) {
        val activeStorage = storage ?: return
        if (!beginExternalWorkflow()) return
        io.execute {
            try {
                val projection = activeStorage.workspaceProjection()
                val target = projection.pages.find { it.pageId == pageId } ?: return@execute
                (application as DocScanApplication).cancelOcrForPages(listOf(target.pageId))
                val receipt = activeStorage.deletePages(listOf(target))
                val refreshed = activeStorage.workspaceProjection()
                runOnUiThread {
                    status.text = getString(R.string.pages_deleted, 1)
                    renderPages(refreshed)
                    Snackbar.make(dialogHost.foregroundView ?: root, getString(R.string.pages_deleted, 1), 2_000)
                        .setAction(R.string.undo_delete) { undoWorkspaceDeleteBatch(receipt.batchId) }
                        .show()
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    status.text = getString(R.string.capture_error, failure.message ?: failure.javaClass.simpleName)
                }
            } finally {
                externalWorkflowRunning.set(false)
                val refreshed = runCatching { activeStorage.workspaceProjection() }.getOrNull()
                runOnUiThread {
                    setWorkspaceInteractionEnabled(true)
                    if (refreshed != null) {
                        renderPages(refreshed)
                        updateRecoveryActions(activeStorage)
                    } else {
                        saveButton.isEnabled = false
                        updateBulkActionButtons(emptyList())
                    }
                    updateSaveButtonState(activeStorage)
                    updateBulkActionButtons(refreshed?.pages.orEmpty())
                }
            }
        }
    }

    private fun deletePageBatch(clearAll: Boolean) {
        val activeStorage = storage ?: return
        if (!beginExternalWorkflow()) return
        io.execute {
            try {
                val projection = activeStorage.workspaceProjection()
                val targets = if (clearAll) projection.pages else projection.pages.filter { it.selected }
                if (targets.isEmpty()) {
                    runOnUiThread { status.text = getString(R.string.no_pages_to_delete) }
                    return@execute
                }
                (application as DocScanApplication).cancelOcrForPages(targets.map { it.pageId })
                val receipt = activeStorage.deletePages(targets)
                val refreshed = activeStorage.workspaceProjection()
                runOnUiThread {
                    status.text = getString(R.string.pages_deleted, receipt.pageIds.size)
                    renderPages(refreshed)
                    Snackbar.make(dialogHost.foregroundView ?: root, getString(R.string.pages_deleted, receipt.pageIds.size), 2_000)
                        .setAction(R.string.undo_delete) { undoWorkspaceDeleteBatch(receipt.batchId) }
                        .show()
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    status.text = getString(R.string.capture_error, failure.message ?: failure.javaClass.simpleName)
                }
            } finally {
                externalWorkflowRunning.set(false)
                val refreshed = runCatching { activeStorage.workspaceProjection() }.getOrNull()
                runOnUiThread {
                    setWorkspaceInteractionEnabled(true)
                    if (refreshed != null) {
                        renderPages(refreshed)
                        updateRecoveryActions(activeStorage)
                    } else {
                        saveButton.isEnabled = false
                        updateBulkActionButtons(emptyList())
                    }
                    updateSaveButtonState(activeStorage)
                    updateBulkActionButtons(refreshed?.pages.orEmpty())
                }
            }
        }
    }

    private fun undoWorkspaceDeleteBatch(batchId: app.docscan.core.model.DeleteBatchId) {
        if (!canInteractWorkspace()) return
        val activeStorage = storage ?: return
        io.execute {
            try {
                activeStorage.undoDeleteBatch(batchId)
                val projection = activeStorage.workspaceProjection()
                if (autoOcrEnabled) {
                    projection.pages.filter { it.recognizedText == null }.forEach { scheduleOcr(activeStorage, it) }
                }
                runOnUiThread {
                    status.text = getString(R.string.delete_batch_undo_complete)
                    renderPages(projection)
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    recordError(getString(R.string.delete_undo_failed, failure.message ?: ""))
                }
            }
        }
    }

    private fun undoWorkspaceDelete(pageId: PageId) {
        if (!canInteractWorkspace()) return
        val activeStorage = storage ?: return
        io.execute {
            try {
                activeStorage.undoDelete(pageId)
                val projection = activeStorage.workspaceProjection()
                if (autoOcrEnabled) {
                    projection.pages.filter { it.recognizedText == null }.forEach { scheduleOcr(activeStorage, it) }
                }
                runOnUiThread {
                    status.text = getString(R.string.delete_undo_complete)
                    renderPages(projection)
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    recordError(getString(R.string.delete_undo_failed, failure.message ?: ""))
                }
            }
        }
    }

    private fun rotateViewerCurrentPage() {
        if (!canInteractWorkspace()) return
        val activeStorage = storage ?: return
        val page = pageViewerPages.getOrNull(pageViewerIndex) ?: return
        val imageFile = activeStorage.artifactPath(page.artifactRelativePath).toFile()
        if (!imageFile.exists()) return

        val viewerAtStart = pageViewer
        val generationAtStart = ++pageViewerGeneration
        status.text = getString(R.string.edit_applying)
        uiHapticFeedback(pageViewerImage ?: root)

        io.execute {
            try {
                val source = decodeViewerImage(imageFile) ?: throw IllegalArgumentException("Image cannot be decoded")
                val matrix = android.graphics.Matrix().apply { postRotate(90f) }
                val rotated = android.graphics.Bitmap.createBitmap(
                    source, 0, 0, source.width, source.height, matrix, true
                )
                if (rotated !== source) source.recycle()
                val newProjection = activeStorage.applyPageImageEdit(page, rotated)
                runOnUiThread {
                    renderPages(newProjection)
                    if (pageViewer === viewerAtStart && pageViewer?.isShowing == true
                        && pageViewerGeneration == generationAtStart
                        && pageViewerPages.getOrNull(pageViewerIndex)?.pageId == page.pageId
                    ) {
                        (pageViewerImage?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.recycle()
                        pageViewerImage?.setImageBitmap(rotated)
                        pageViewerText?.text = getString(R.string.ocr_pending_or_empty)
                        pageViewerPages = newProjection.pages.toList()
                        renderPages(newProjection)
                        refreshViewerStrip(newProjection.pages)
                        syncViewerStripActive()
                        status.text = getString(R.string.edit_applied)
                    } else {
                        rotated.recycle()
                    }
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    status.text = getString(R.string.capture_error, failure.message ?: failure.javaClass.simpleName)
                }
            }
        }
    }

    private fun cropViewerCurrentPage() {
        if (!canInteractWorkspace()) return
        val page = pageViewerPages.getOrNull(pageViewerIndex) ?: return
        showCropDialogForViewer(page)
    }

    private fun showCropDialogForViewer(page: app.docscan.core.store.StoredPage) {
        val activeStorage = storage ?: return
        val imageFile = activeStorage.artifactPath(page.artifactRelativePath).toFile()
        if (!imageFile.exists()) return

        val selection = CropSelectionView(this).apply {
            contentDescription = getString(R.string.manual_crop_view_description)
        }
        var sourceBitmap: android.graphics.Bitmap? = null
        var cropApplying = false
        var cropDismissed = false
        val cropViewerAtStart = pageViewer
        val cropGenerationAtStart = pageViewerGeneration

        val cropRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF111316.toInt())
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0xFF27272A.toInt())
            }
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }

        val backBtn = Button(this).apply {
            text = "⏎"
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = modernButtonDrawable(radiusDp = 18)
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
        }

        val saveTopBtn = Button(this).apply {
            text = "Сохранить"
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = modernButtonDrawable(normalBg = 0xFF2563EB.toInt(), pressedBg = 0xFF1D4ED8.toInt(), radiusDp = 8)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            isEnabled = false
        }

        topBar.addView(backBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        topBar.addView(android.widget.Space(this), LinearLayout.LayoutParams(0, dp(36), 1f))
        topBar.addView(saveTopBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        cropRoot.addView(topBar, matchWrap())

        val cropHeight = maxOf(dp(220), minOf(dp(440), root.height - dp(180)))
        cropRoot.addView(selection, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cropHeight))

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }

        val cancelBtn = Button(this).apply {
            text = "Отмена"
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = modernButtonDrawable(normalBg = 0x22FFFFFF.toInt(), pressedBg = 0x44FFFFFF.toInt(), strokeColor = 0x44FFFFFF.toInt(), radiusDp = 10)
        }

        val applyBtn = Button(this).apply {
            text = "Применить"
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = modernButtonDrawable(normalBg = 0xFF2563EB.toInt(), pressedBg = 0xFF1D4ED8.toInt(), radiusDp = 10)
            isEnabled = false
        }

        bottomBar.addView(cancelBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        bottomBar.addView(applyBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) })
        cropRoot.addView(bottomBar, matchWrap())

        val cropDialog = dialogHost.builder(this)
            .setView(cropRoot)
            .create()
        cropDialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        cropDialog.setCanceledOnTouchOutside(false)
        cropDialog.setOnDismissListener {
            cropDismissed = true
            selection.bitmap = null
            if (!cropApplying) {
                sourceBitmap?.recycle()
                sourceBitmap = null
            }
        }

        fun executeCrop() {
            if (cropApplying) return
            val source = sourceBitmap ?: return
            val quad = selection.quadrilateral
            cropApplying = true
            saveTopBtn.isEnabled = false
            applyBtn.isEnabled = false
            status.text = getString(R.string.edit_applying)
            io.execute {
                var cropped: android.graphics.Bitmap? = null
                try {
                    cropped = (application as DocScanApplication).heavyWorkScheduler.withAdmission(
                        EDIT_WORKING_SET_BYTES,
                    ) { PageImageAdjustmentProcessor.render(source, PageImageEditState(crop = quad)) }
                    val newProjection = activeStorage.applyPageImageEdit(page, cropped)
                    runOnUiThread {
                        cropApplying = false
                        cropDialog.dismiss()
                        if (cropDismissed) {
                            sourceBitmap?.recycle()
                            sourceBitmap = null
                        }
                        renderPages(newProjection)
                        if (pageViewer === cropViewerAtStart && pageViewer?.isShowing == true
                            && pageViewerGeneration == cropGenerationAtStart
                            && pageViewerPages.getOrNull(pageViewerIndex)?.pageId == page.pageId
                        ) {
                            (pageViewerImage?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.recycle()
                            pageViewerImage?.setImageBitmap(cropped)
                            pageViewerPages = newProjection.pages.toList()
                            renderPages(newProjection)
                            refreshViewerStrip(newProjection.pages)
                            syncViewerStripActive()
                        } else {
                            cropped.recycle()
                        }
                        status.text = getString(R.string.edit_applied)
                    }
                } catch (failure: Exception) {
                    runOnUiThread {
                        cropApplying = false
                        if (cropDismissed) {
                            sourceBitmap?.recycle()
                            sourceBitmap = null
                        } else {
                            saveTopBtn.isEnabled = true
                            applyBtn.isEnabled = true
                        }
                        status.text = getString(R.string.capture_error, failure.message ?: failure.javaClass.simpleName)
                    }
                }
            }
        }

        backBtn.setOnClickListener { cropDialog.dismiss() }
        cancelBtn.setOnClickListener { cropDialog.dismiss() }
        saveTopBtn.setOnClickListener { executeCrop() }
        applyBtn.setOnClickListener { executeCrop() }

        cropDialog.show()

        io.execute {
            try {
                val decoded = decodeEditableImage(imageFile) ?: throw IllegalArgumentException("Cannot decode image")
                sourceBitmap = decoded

                // Автоматическая детекция границ документа по алгоритмам «зелёных линий» (DocumentCropProcessor)
                val detectedQuad = try {
                    val detectionThumb = try {
                        ProductImageDecoder.decodeThumbnailForDetection(imageFile, maxSide = 720)
                    } catch (_: Throwable) {
                        null
                    }
                    if (detectionThumb != null) {
                        try {
                            DocumentCropProcessor.detectCorners(detectionThumb)?.toNormalized()
                        } finally {
                            detectionThumb.recycle()
                        }
                    } else {
                        DocumentCropProcessor.detectCorners(decoded)?.toNormalized()
                    }
                } catch (_: Throwable) {
                    null
                }

                runOnUiThread {
                    if (cropDialog.isShowing) {
                        selection.bitmap = decoded
                        if (detectedQuad != null) {
                            selection.quadrilateral = detectedQuad
                        }
                        saveTopBtn.isEnabled = true
                        applyBtn.isEnabled = true
                    } else {
                        decoded.recycle()
                    }
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    if (cropDialog.isShowing) {
                        recordError(getString(R.string.edit_preview_failed, failure.message ?: ""))
                    }
                }
            }
        }
    }

    private fun showPageViewer(pages: List<app.docscan.core.store.StoredPage>, index: Int) {
        if (!canInteractWorkspace() || pages.isEmpty() || index !in pages.indices) return
        pageViewer?.dismiss()
        pageViewerPages = pages.toList()
        pageViewerIndex = index

        val dialogRoot = android.widget.FrameLayout(this).apply {
            setBackgroundColor(0xFF111316.toInt())
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111316.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minimumHeight = dp(420)
        }

        // Expanded preview overlay for double-tap zoom
        val expandedPreviewOverlay = android.widget.FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(0xFF0B0D10.toInt())
        }
        val expandedImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        val closeExpandedBtn = Button(this).apply {
            text = "✕"
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = modernButtonDrawable(radiusDp = 18)
        }
        expandedPreviewOverlay.addView(
            expandedImage,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ).apply {
                setMargins(dp(8), dp(48), dp(8), dp(8))
            },
        )
        expandedPreviewOverlay.addView(
            closeExpandedBtn,
            android.widget.FrameLayout.LayoutParams(dp(36), dp(36), Gravity.START or Gravity.TOP).apply {
                marginStart = dp(12)
                topMargin = dp(12)
            },
        )

        fun collapsePreview() {
            if (expandedPreviewOverlay.visibility != View.VISIBLE) return
            uiHapticFeedback(closeExpandedBtn)
            expandedPreviewOverlay.animate()
                .alpha(0f)
                .scaleX(0.85f)
                .scaleY(0.85f)
                .setDuration(180)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    expandedPreviewOverlay.visibility = View.GONE
                    expandedImage.setImageDrawable(null)
                }
                .start()
        }

        fun expandPreview() {
            val drawable = pageViewerImage?.drawable ?: return
            expandedImage.setImageDrawable(drawable)
            uiHapticFeedback(pageViewerImage ?: dialogRoot)
            expandedPreviewOverlay.visibility = View.VISIBLE
            expandedPreviewOverlay.alpha = 0f
            expandedPreviewOverlay.scaleX = 0.85f
            expandedPreviewOverlay.scaleY = 0.85f
            expandedPreviewOverlay.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .setListener(null)
                .start()
        }

        closeExpandedBtn.setOnClickListener { collapsePreview() }
        expandedPreviewOverlay.setOnClickListener { collapsePreview() }

        val collapseGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                collapsePreview()
                return true
            }
        })
        expandedImage.setOnTouchListener { _, event ->
            collapseGestureDetector.onTouchEvent(event)
            true
        }

        // Header: Back/Close button + Title "Page X of Y"
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val closeBtn = Button(this).apply {
            text = "✕"
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = modernButtonDrawable(radiusDp = 18)
            setOnClickListener { pageViewer?.dismiss() }
        }
        pageViewerTitle = TextView(this).apply {
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFF8FAFC.toInt())
            gravity = Gravity.CENTER
            tag = VIEWER_TITLE_TAG
        }
        header.addView(closeBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        header.addView(pageViewerTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val headerSpacer = View(this)
        header.addView(headerSpacer, LinearLayout.LayoutParams(dp(36), dp(36)))

        content.addView(header, matchWrap())

        pageViewerStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = VIEWER_THUMBNAIL_STRIP_TAG
        }
        pageViewerStripScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setOnScrollChangeListener { _, _, _, _, _ -> loadVisibleViewerThumbnails() }
            addView(pageViewerStrip, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val viewerGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                expandPreview()
                return true
            }
        })
        pageViewerImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            tag = VIEWER_MAIN_IMAGE_TAG
            background = GradientDrawable().apply {
                setColor(0xFF0B0D10.toInt())
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), 0xFF22262E.toInt())
            }
            var touchDownX = 0f
            var touchDownY = 0f
            setOnTouchListener { _, event ->
                viewerGestureDetector.onTouchEvent(event)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchDownX = event.x
                        touchDownY = event.y
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = event.x - touchDownX
                        val dy = event.y - touchDownY
                        if (kotlin.math.abs(dx) >= dp(48) && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                            val destination = pageViewerIndex + if (dx < 0) 1 else -1
                            android.util.Log.d("DocScanViewerGesture", "dx=$dx dy=$dy from=$pageViewerIndex to=$destination")
                            if (destination in pageViewerPages.indices) showViewerPage(destination)
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> true
                }
            }
        }
        // Preview Row: Left Toolbar (Rotate, Crop, Adjust), Center Image, Right Toolbar (ReClick, ReOCR, Delete)
        val previewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Editing Toolbar: Rotate, Crop/Resize, Adjust/Filters (Vertical, Left of Preview)
        val editToolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 0, dp(6), 0)
        }
        val rotateBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_rotate_right)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = ColorStateList.valueOf(0xFFF1F5F9.toInt())
            background = modernButtonDrawable(radiusDp = 10)
            contentDescription = getString(R.string.edit_rotate_clockwise)
            setOnClickListener { rotateViewerCurrentPage() }
        }
        val cropBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_crop)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = ColorStateList.valueOf(0xFFF1F5F9.toInt())
            background = modernButtonDrawable(radiusDp = 10)
            contentDescription = getString(R.string.auto_crop_page)
            setOnClickListener { cropViewerCurrentPage() }
        }
        val adjustBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_tune)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = ColorStateList.valueOf(0xFFF1F5F9.toInt())
            background = modernButtonDrawable(radiusDp = 10)
            contentDescription = getString(R.string.edit_image_title)
            setOnClickListener {
                val page = pageViewerPages.getOrNull(pageViewerIndex) ?: return@setOnClickListener
                pageViewer?.dismiss()
                showPageImageEditor(page)
            }
        }
        editToolbar.addView(rotateBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { bottomMargin = dp(8) })
        editToolbar.addView(cropBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { bottomMargin = dp(8) })
        editToolbar.addView(adjustBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        previewRow.addView(editToolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Center Preview Image (+17% height and proportional width: 270dp * 1.17 = 316dp)
        previewRow.addView(pageViewerImage, LinearLayout.LayoutParams(0, dp(316), 1f))

        // Secondary Actions: ReClick, ReOCR, Delete (Vertical, Right of Preview)
        val viewerActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, 0, 0)
        }
        val retakeBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_reclick_aperture)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = ColorStateList.valueOf(0xFFF1F5F9.toInt())
            background = modernButtonDrawable(radiusDp = 10)
            contentDescription = getString(R.string.retake_instruction, pageViewerIndex + 1)
            setOnClickListener {
                val page = pageViewerPages.getOrNull(pageViewerIndex) ?: return@setOnClickListener
                retakeTarget = page
                status.text = getString(R.string.retake_instruction, pageViewerIndex + 1)
                pageViewer?.dismiss()
            }
        }
        val ocrBtn = Button(this).apply {
            text = "[Aa]"
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFF1F5F9.toInt())
            background = modernButtonDrawable(radiusDp = 10)
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
            minWidth = 0
            minHeight = 0
            contentDescription = "ReOCR"
            setOnClickListener {
                val page = pageViewerPages.getOrNull(pageViewerIndex) ?: return@setOnClickListener
                val activeStorage = storage ?: return@setOnClickListener
                scheduleOcr(activeStorage, page, force = true)
            }
            setOnLongClickListener {
                val page = pageViewerPages.getOrNull(pageViewerIndex) ?: return@setOnLongClickListener true
                val activeStorage = storage ?: return@setOnLongClickListener true
                val modes = app.docscan.core.ocr.OcrLangMode.values()
                val items = modes.map { m ->
                    when (m) {
                        app.docscan.core.ocr.OcrLangMode.AUTO -> getString(R.string.ocr_lang_auto)
                        app.docscan.core.ocr.OcrLangMode.RU_EN -> getString(R.string.ocr_lang_ru_en)
                        app.docscan.core.ocr.OcrLangMode.ES_EN -> getString(R.string.ocr_lang_es_en)
                        app.docscan.core.ocr.OcrLangMode.RU_ES_EN -> getString(R.string.ocr_lang_ru_es_en)
                    }
                }.toTypedArray()
                dialogHost.builder(this@ProductActivity)
                    .setTitle(R.string.reocr_with_lang)
                    .setItems(items) { d, which ->
                        d.dismiss()
                        scheduleOcr(activeStorage, page, force = true, langMode = modes[which])
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        }
        val deleteBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_delete_trash)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            contentDescription = getString(R.string.delete_page)
            background = modernButtonDrawable(strokeColor = Color.TRANSPARENT, radiusDp = 10)
            setOnClickListener {
                val page = pageViewerPages.getOrNull(pageViewerIndex) ?: return@setOnClickListener
                pageViewer?.dismiss()
                deleteWorkspacePage(page)
            }
        }
        viewerActions.addView(retakeBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { bottomMargin = dp(8) })
        viewerActions.addView(ocrBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { bottomMargin = dp(8) })
        viewerActions.addView(deleteBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        previewRow.addView(viewerActions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        content.addView(previewRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })

        pageViewerText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF94A3B8.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setTextIsSelectable(true)
        }
        val ocrScroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxHeight = dp(160)
                val mode = MeasureSpec.getMode(heightMeasureSpec)
                val size = MeasureSpec.getSize(heightMeasureSpec)
                val limitedSpec = if (mode == MeasureSpec.UNSPECIFIED) {
                    MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
                } else {
                    MeasureSpec.makeMeasureSpec(minOf(size, maxHeight), MeasureSpec.AT_MOST)
                }
                super.onMeasure(widthMeasureSpec, limitedSpec)
            }
        }.apply {
            minimumHeight = dp(46)
            background = GradientDrawable().apply {
                setColor(0xFF0F1216.toInt())
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), 0xFF1E222A.toInt())
            }
            addView(pageViewerText)
        }
        content.addView(ocrScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })

        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val previous = Button(this).apply {
            text = getString(R.string.viewer_previous)
            tag = VIEWER_PREVIOUS_TAG
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFE2E8F0.toInt())
            background = modernButtonDrawable(radiusDp = 10)
            setOnClickListener { showViewerPage(pageViewerIndex - 1) }
        }
        val next = Button(this).apply {
            text = getString(R.string.viewer_next)
            tag = VIEWER_NEXT_TAG
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFE2E8F0.toInt())
            background = modernButtonDrawable(radiusDp = 10)
            setOnClickListener { showViewerPage(pageViewerIndex + 1) }
        }
        pageViewerPrevious = previous
        pageViewerNext = next
        navigation.addView(previous, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(8) })
        navigation.addView(next, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(8) })

        content.addView(viewerFooter(requireNotNull(pageViewerStripScroll), navigation), LinearLayout.LayoutParams(-1, dp(154)))
        dialogRoot.addView(content, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        dialogRoot.addView(expandedPreviewOverlay, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        pageViewer = dialogHost.builder(this)
            .setView(dialogRoot)
            .create()
            .also { dialog ->
                dialog.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        if (expandedPreviewOverlay.visibility == View.VISIBLE) {
                            collapsePreview()
                            return@setOnKeyListener true
                        }
                    }
                    false
                }
                dialog.setOnDismissListener {
                    pageViewerGeneration += 1
                    (pageViewerImage?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.recycle()
                    expandedImage.setImageDrawable(null)
                    pageViewerStrip?.let(::unbindWorkspaceThumbnails)
                    pageViewerImage = null
                    pageViewerTitle = null
                    pageViewerText = null
                    pageViewerPrevious = null
                    pageViewerNext = null
                    pageViewerStrip = null
                    pageViewerStripScroll = null
                    pageViewer = null
                }
                dialog.show()
            }
        refreshViewerStrip(pageViewerPages)
        showViewerPage(index)
    }

    private fun installPageSwipe(view: View, navigate: (Int) -> Unit) {
        var downX = 0f
        var downY = 0f
        var tracking = false
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; tracking = true; true }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (tracking && kotlin.math.abs(dx) >= dp(48) && kotlin.math.abs(dx) > kotlin.math.abs(dy)) navigate(if (dx < 0) 1 else -1)
                    else if (tracking) touched.performClick()
                    tracking = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> { tracking = false; true }
                else -> tracking
            }
        }
    }

    private fun pdfRail(document: app.docscan.core.store.RecentDocumentSnapshot, navigate: (Int) -> Unit): PdfPageRail =
        PdfPageRail(this, io, { index ->
            val source = requireNotNull(storage).renderRecentSavedPdfPage(document, index, (application as DocScanApplication).heavyWorkScheduler).bitmap
            val scale = minOf(dp(48).toFloat() / source.width, dp(64).toFloat() / source.height)
            try { android.graphics.Bitmap.createScaledBitmap(source, maxOf(1, (source.width * scale).toInt()), maxOf(1, (source.height * scale).toInt()), true).let { scaled ->
                if (scaled === source) source.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else scaled
            } } finally { source.recycle() }
        }, navigate)

    private fun viewerFooter(rail: View, navigation: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tag = InsetDialogHost.RAIL_TAG
        rail.tag = "docscan-thumbnail-scroll"
        setBackgroundColor(0xff17191d.toInt())
        addView(rail, LinearLayout.LayoutParams(-1, dp(124)))
        addView(navigation, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun pageThumbnailFrame(active: Boolean): GradientDrawable = GradientDrawable().apply {
        setColor(0xff303030.toInt())
        setStroke(dp(if (active) 3 else 1), if (active) Color.WHITE else 0xff777777.toInt())
    }

    private fun refreshViewerStrip(pages: List<app.docscan.core.store.StoredPage>) {
        val strip = pageViewerStrip ?: return
        val activeStorage = storage ?: return
        unbindWorkspaceThumbnails(strip)
        strip.removeAllViews()
        pageViewerPages = pages.toList()
        pages.forEachIndexed { index, page ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(2), dp(4), dp(2))
            }
            val thumbnail = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = if (page.pageId == pages.getOrNull(pageViewerIndex)?.pageId) {
                    getString(R.string.active_page, index + 1)
                } else {
                    getString(R.string.page_image_description, index + 1)
                }
                tag = VIEWER_PAGE_THUMBNAIL_TAG_PREFIX + page.pageId.value
                background = pageThumbnailFrame(page.pageId == pages.getOrNull(pageViewerIndex)?.pageId)
                setPadding(dp(3), dp(3), dp(3), dp(3))
                if (page.artifactState == "COMMITTED") {
                    setImageDrawable(android.graphics.drawable.ColorDrawable(0xff424242.toInt()))
                }
                setOnClickListener { showViewerPage(index) }
            }
            cell.addView(thumbnail, LinearLayout.LayoutParams(dp(103), dp(75)))
            cell.addView(CheckBox(this).apply {
                // Match Home: the checkbox carries state; the full action is announced accessibly.
                contentDescription = getString(R.string.include_page_in_save, index + 1)
                minimumWidth = dp(56)
                minimumHeight = dp(48)
                tag = VIEWER_PAGE_SELECTION_TAG_PREFIX + page.pageId.value
                buttonTintList = ColorStateList.valueOf(Color.WHITE)
                isChecked = page.selected
                setOnCheckedChangeListener { _, checked ->
                    io.execute {
                        try {
                            activeStorage.setSelected(page.pageId, checked)
                            val projection = activeStorage.workspaceProjection()
                            runOnUiThread {
                                if (pageViewer?.isShowing == true) {
                                    renderPages(projection)
                                    pageViewerPages = projection.pages.toList()
                                }
                            }
                        } catch (failure: Exception) {
                            runOnUiThread { recordError(getString(R.string.capture_error, failure.message ?: "")) }
                        }
                    }
                }
            }, LinearLayout.LayoutParams(dp(56), dp(48)))
            strip.addView(cell, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        val currentId = pages.getOrNull(pageViewerIndex)?.pageId ?: return
        val currentIndex = pages.indexOfFirst { it.pageId == currentId }
        if (currentIndex >= 0) pageViewerStripScroll?.post {
            val target = strip.getChildAt(currentIndex) ?: return@post
            pageViewerStripScroll?.smoothScrollTo(target.left, 0)
            loadVisibleViewerThumbnails()
        }
    }

    private fun loadVisibleViewerThumbnails() {
        val pages = pageViewerPages
        val strip = pageViewerStrip ?: return
        val scroll = pageViewerStripScroll ?: return
        if (scroll.width <= 0) return
        val visibleLeft = scroll.scrollX
        val visibleRight = visibleLeft + scroll.width
        pages.forEachIndexed { index, page ->
            val cell = strip.getChildAt(index) as? ViewGroup ?: return@forEachIndexed
            val image = cell.getChildAt(0) as? ImageView ?: return@forEachIndexed
            val visible = cell.right > visibleLeft && cell.left < visibleRight
            if (!visible || page.artifactState != "COMMITTED") {
                workspaceThumbnailLoader.unbind(image)
                return@forEachIndexed
            }
            val file = thumbnailFile(page)
            workspaceThumbnailLoader.bind(
                image,
                WorkspaceThumbnailLoader.Key(
                    page.pageId,
                    page.imageVersionId,
                    file.length(),
                    dp(103) * 2,
                    dp(75) * 2,
                ),
                file,
            )
        }
    }

    private fun syncViewerStripActive() {
        val pages = pageViewerPages
        val strip = pageViewerStrip ?: return
        val currentId = pages.getOrNull(pageViewerIndex)?.pageId ?: return
        pages.forEachIndexed { index, page ->
            val cell = strip.getChildAt(index) as? ViewGroup ?: return@forEachIndexed
            val thumbnail = cell.getChildAt(0) as? ImageView ?: return@forEachIndexed
            val active = page.pageId == currentId
            thumbnail.background = pageThumbnailFrame(active)
            thumbnail.contentDescription = if (active) {
                getString(R.string.active_page, index + 1)
            } else {
                getString(R.string.page_image_description, index + 1)
            }
        }
        val currentCell = strip.getChildAt(pageViewerIndex) ?: return
        pageViewerStripScroll?.smoothScrollTo(currentCell.left, 0)
    }

    private fun showViewerPage(index: Int) {
        if (index !in pageViewerPages.indices) return
        val activeStorage = storage ?: return
        pageViewerIndex = index
        val page = pageViewerPages[index]
        val generation = ++pageViewerGeneration
        syncViewerStripActive()
        pageViewerTitle?.text = getString(R.string.page_number, index + 1)
        pageViewerPrevious?.isEnabled = index > 0
        pageViewerNext?.isEnabled = index < pageViewerPages.lastIndex
        pageViewerText?.text = page.recognizedText?.takeIf { it.isNotBlank() }
            ?: getString(R.string.ocr_pending_or_empty)
        (pageViewerImage?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.recycle()
        pageViewerImage?.setImageDrawable(null)
        pageViewerImage?.contentDescription = getString(R.string.page_image_description, index + 1)
        io.execute {
            try {
                // Active is navigation state and is durable even if image decoding later fails.
                activeStorage.setActivePage(page.pageId)
                val activeProjection = activeStorage.workspaceProjection()
                runOnUiThread {
                    if (pageViewerGeneration == generation && pageViewer?.isShowing == true) {
                        renderPages(activeProjection)
                        pageViewerPages = activeProjection.pages.toList()
                        syncViewerStripActive()
                    }
                }
                val imageFile = activeStorage.artifactPath(page.artifactRelativePath).toFile()
                val image = (application as DocScanApplication).heavyWorkScheduler.withAdmission(
                    ProductImageDecoder.estimateWorkingSetBytes(imageFile, 12L * 1024L * 1024L),
                ) {
                    decodeViewerImage(imageFile)
                } ?: throw IllegalArgumentException("Image cannot be decoded")
                runOnUiThread {
                    if (pageViewerGeneration == generation && pageViewer?.isShowing == true) {
                        pageViewerImage?.setImageBitmap(image)
                    } else {
                        image.recycle()
                    }
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    if (pageViewerGeneration == generation && pageViewer?.isShowing == true) {
                        pageViewerTitle?.text = getString(R.string.viewer_load_failed, failure.message ?: "")
                    }
                }
            }
        }
    }

    private fun showPageImageEditor(page: app.docscan.core.store.StoredPage) {
        if (!canInteractWorkspace()) return
        val activeStorage = storage ?: return
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.edit_preview_description)
        }
        val brightness = android.widget.SeekBar(this).apply {
            max = 200
            progress = 100
            contentDescription = getString(R.string.edit_brightness)
            minimumWidth = dp(48)
            minimumHeight = dp(48)
        }
        val contrast = android.widget.SeekBar(this).apply {
            max = 200
            progress = 100
            contentDescription = getString(R.string.edit_contrast)
            minimumWidth = dp(48)
            minimumHeight = dp(48)
        }
        val sharpness = android.widget.SeekBar(this).apply {
            max = 200
            progress = 0
            contentDescription = getString(R.string.edit_sharpness)
            minimumWidth = dp(48)
            minimumHeight = dp(48)
        }
        fun adjustmentControl(title: String, bar: android.widget.SeekBar) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(TextView(this@ProductActivity).apply {
                text = title
                textSize = 13f
                setTextColor(0xFFCBD5E1.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }, matchWrap())
            addView(
                bar,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)),
            )
        }

        val adjustRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF111316.toInt())
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0xFF27272A.toInt())
            }
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }

        val backBtn = Button(this).apply {
            text = "⏎"
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = modernButtonDrawable(radiusDp = 18)
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
        }

        val saveTopBtn = Button(this).apply {
            text = "Сохранить"
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = modernButtonDrawable(normalBg = 0xFF2563EB.toInt(), pressedBg = 0xFF1D4ED8.toInt(), radiusDp = 8)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            isEnabled = false
        }

        topBar.addView(backBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        topBar.addView(android.widget.Space(this), LinearLayout.LayoutParams(0, dp(36), 1f))
        topBar.addView(saveTopBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        adjustRoot.addView(topBar, matchWrap())

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)).apply {
                bottomMargin = dp(8)
            })
            addView(adjustmentControl("Яркость", brightness), matchWrap())
            addView(adjustmentControl("Контраст", contrast), matchWrap())
            addView(adjustmentControl("Резкость", sharpness), matchWrap())
        }
        val editorScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val editorHeight = maxOf(dp(260), minOf(dp(440), root.height - dp(180)))
        adjustRoot.addView(editorScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, editorHeight))

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }

        val cancelBtn = Button(this).apply {
            text = "Отмена"
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = modernButtonDrawable(normalBg = 0x22FFFFFF.toInt(), pressedBg = 0x44FFFFFF.toInt(), strokeColor = 0x44FFFFFF.toInt(), radiusDp = 10)
        }

        val applyBtn = Button(this).apply {
            text = "Применить"
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = modernButtonDrawable(normalBg = 0xFF2563EB.toInt(), pressedBg = 0xFF1D4ED8.toInt(), radiusDp = 10)
            isEnabled = false
        }

        bottomBar.addView(cancelBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        bottomBar.addView(applyBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) })
        adjustRoot.addView(bottomBar, matchWrap())

        val draft = PageImageEditDraft()
        var previewBitmap: android.graphics.Bitmap? = null
        var sourceBitmap: android.graphics.Bitmap? = null
        var previewGeneration = 0
        var pendingWork = 0
        var dismissed = false
        var busy = false
        var syncingControls = false

        val dialog = dialogHost.builder(this)
            .setView(adjustRoot)
            .create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.setOnDismissListener {
            dismissed = true
            image.setImageDrawable(null)
            if (pendingWork == 0) {
                sourceBitmap?.recycle()
                sourceBitmap = null
                previewBitmap?.recycle()
                previewBitmap = null
            }
        }
        dialog.setCanceledOnTouchOutside(false)

        fun updateControls() {
            syncingControls = true
            brightness.progress = draft.state.brightness + 100
            contrast.progress = draft.state.contrastPercent
            sharpness.progress = draft.state.sharpnessPercent
            syncingControls = false

            val canSave = sourceBitmap != null && draft.isDirty && !busy
            saveTopBtn.isEnabled = canSave
            applyBtn.isEnabled = canSave
            cancelBtn.isEnabled = !busy
            backBtn.isEnabled = !busy

            val ready = sourceBitmap != null
            brightness.isEnabled = ready && !busy
            contrast.isEnabled = ready && !busy
            sharpness.isEnabled = ready && !busy
        }

        fun releaseIfClosed() {
            if (!dismissed || pendingWork != 0) return
            sourceBitmap?.recycle()
            sourceBitmap = null
            previewBitmap?.recycle()
            previewBitmap = null
        }

        fun requestPreview(state: PageImageEditState) {
            val source = sourceBitmap ?: return
            val ticket = ++previewGeneration
            pendingWork++
            io.execute {
                var candidate: android.graphics.Bitmap? = null
                var failure: Exception? = null
                try {
                    candidate = (application as DocScanApplication).heavyWorkScheduler.withAdmission(
                        EDIT_WORKING_SET_BYTES,
                    ) { PageImageAdjustmentProcessor.render(source, state) }
                } catch (problem: Exception) {
                    failure = problem
                }
                runOnUiThread {
                    pendingWork--
                    if (!dismissed && dialog.isShowing && ticket == previewGeneration) {
                        candidate?.let {
                            image.setImageDrawable(null)
                            previewBitmap?.recycle()
                            previewBitmap = it
                            image.setImageBitmap(it)
                        }
                        failure?.let {
                            recordError(getString(R.string.edit_preview_failed, it.message ?: it.javaClass.simpleName))
                        }
                    } else candidate?.recycle()
                    releaseIfClosed()
                }
            }
        }

        fun saveDraft() {
            if (!draft.isDirty || busy) return
            if (sourceBitmap == null) return
            busy = true
            updateControls()
            status.text = getString(R.string.edit_applying)
            val state = draft.state
            pendingWork++
            io.execute {
                var projection: app.docscan.core.store.WorkspaceProjection? = null
                var failure: Exception? = null
                var edited: android.graphics.Bitmap? = null
                try {
                    edited = (application as DocScanApplication).heavyWorkScheduler.withAdmission(
                        EDIT_WORKING_SET_BYTES,
                    ) {
                        val fullSource = decodeEditableImage(activeStorage.artifactPath(page.artifactRelativePath).toFile())
                            ?: error("Image cannot be decoded for saving")
                        try { PageImageAdjustmentProcessor.render(fullSource, state) }
                        finally { fullSource.recycle() }
                    }
                    projection = activeStorage.applyPageImageEdit(page, edited)
                } catch (problem: Exception) {
                    failure = problem
                } finally {
                    edited?.recycle()
                }
                runOnUiThread {
                    pendingWork--
                    val savedProjection = projection
                    if (savedProjection != null) {
                        status.text = getString(R.string.edit_applied)
                        dialog.dismiss()
                        renderPages(savedProjection)
                    } else {
                        busy = false
                        recordError(getString(R.string.capture_error, failure?.message ?: "Image edit failed"))
                        updateControls()
                    }
                    releaseIfClosed()
                }
            }
        }

        fun promptDirtyExit() {
            dialogHost.builder(this)
                .setTitle(R.string.edit_unsaved_title)
                .setMessage(R.string.edit_discard_confirmation)
                .setNegativeButton(R.string.edit_keep_editing, null)
                .setNeutralButton(R.string.edit_discard) { _, _ -> dialog.dismiss() }
                .setPositiveButton(R.string.edit_save) { _, _ -> saveDraft() }
                .create()
                .apply { setCanceledOnTouchOutside(false) }
                .show()
        }

        fun onCancelClicked() {
            if (!draft.isDirty && !busy) {
                dialog.dismiss()
            } else if (draft.isDirty && !busy) {
                promptDirtyExit()
            }
        }

        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode != android.view.KeyEvent.KEYCODE_BACK
                || event.action != android.view.KeyEvent.ACTION_UP
            ) return@setOnKeyListener false
            if (!draft.isDirty) return@setOnKeyListener false
            promptDirtyExit()
            true
        }

        backBtn.setOnClickListener { onCancelClicked() }
        cancelBtn.setOnClickListener { onCancelClicked() }
        saveTopBtn.setOnClickListener { saveDraft() }
        applyBtn.setOnClickListener { saveDraft() }

        dialog.show()
        updateControls()

        fun bindSlider(bar: android.widget.SeekBar, value: (Int) -> PageImageEditState) {
            bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser || syncingControls || busy) return
                    val next = value(progress)
                    draft.preview(next)
                    updateControls()
                    requestPreview(next)
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                    draft.commitPreview()
                    updateControls()
                }
            })
        }
        bindSlider(brightness) { draft.state.copy(brightness = it - 100) }
        bindSlider(contrast) { draft.state.copy(contrastPercent = it) }
        bindSlider(sharpness) { draft.state.copy(sharpnessPercent = it) }

        val maxImagePixels = 3L * 1024L * 1024L
        pendingWork++
        io.execute {
            try {
                val file = activeStorage.artifactPath(page.artifactRelativePath).toFile()
                val bitmap = (application as DocScanApplication).heavyWorkScheduler.withAdmission(
                    ProductImageDecoder.estimateWorkingSetBytes(file, maxImagePixels),
                ) { ProductImageDecoder.decode(file, maxImagePixels) }
                    ?: throw IllegalArgumentException("Image cannot be previewed")
                runOnUiThread {
                    pendingWork--
                    if (!dismissed && dialog.isShowing) {
                        sourceBitmap = bitmap
                        updateControls()
                        requestPreview(draft.state)
                    } else bitmap.recycle()
                    releaseIfClosed()
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    pendingWork--
                    if (dialog.isShowing) recordError(getString(
                        R.string.edit_preview_failed, failure.message ?: failure.javaClass.simpleName,
                    ))
                    updateControls()
                    releaseIfClosed()
                }
            }
        }
    }

    private fun showDocumentCropEditor(page: app.docscan.core.store.StoredPage) {
        if (!canInteractWorkspace()) return
        val activeStorage = storage ?: return
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.auto_crop_preview_description)
        }
        val message = TextView(this).apply {
            gravity = Gravity.CENTER
            text = getString(R.string.auto_crop_detecting)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(message, matchWrap())
            addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(dp(96), minOf(dp(440), root.height - dp(400)))))
        }
        var candidateBitmap: android.graphics.Bitmap? = null
        var candidatePlan: DocumentCropPlan? = null
        var candidateReady = false
        val dialog = dialogHost.builder(this)
            .setTitle(R.string.auto_crop_title)
            .setView(content)
            .setNegativeButton(R.string.edit_discard, null)
            .setPositiveButton(R.string.auto_crop_apply, null)
            .create()
        dialog.setOnDismissListener {
            preview.setImageDrawable(null)
            candidateBitmap?.recycle()
            candidateBitmap = null
            candidatePlan = null
        }
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode != android.view.KeyEvent.KEYCODE_BACK
                || event.action != android.view.KeyEvent.ACTION_UP
            ) return@setOnKeyListener false
            if (!candidateReady) return@setOnKeyListener false
            dialogHost.builder(this)
                .setMessage(R.string.edit_discard_confirmation)
                .setNegativeButton(R.string.edit_keep_editing, null)
                .setPositiveButton(R.string.edit_discard) { _, _ -> dialog.dismiss() }
                .show()
            true
        }
        dialog.show()
        val applyButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply { isEnabled = false }
        applyButton.setOnClickListener {
            val approvedPlan = candidatePlan ?: return@setOnClickListener
            if (!candidateReady) return@setOnClickListener
            dialog.dismiss()
            status.text = getString(R.string.auto_crop_applying)
            io.execute {
                try {
                    val projection = activeStorage.applyDocumentCrop(
                        approvedPlan,
                        (application as DocScanApplication).heavyWorkScheduler,
                    )
                    if (autoOcrEnabled) {
                        projection.pages.firstOrNull { it.pageId == approvedPlan.pageId }?.let { updatedPage ->
                            scheduleOcr(activeStorage, updatedPage, force = true)
                        }
                    }
                    runOnUiThread {
                        status.text = getString(R.string.document_manual_crop_saved)
                        renderPages(projection)
                    }
                } catch (failure: Exception) {
                    runOnUiThread {
                        val errorMsg = if (failure is IllegalStateException && failure.message?.contains("устарел") == true) {
                            getString(R.string.document_crop_plan_stale)
                        } else {
                            getString(R.string.capture_error, failure.message ?: failure.javaClass.simpleName)
                        }
                        recordError(errorMsg)
                    }
                } catch (_: OutOfMemoryError) {
                    runOnUiThread { recordError(getString(R.string.document_crop_oom_page_unchanged)) }
                }
            }
        }
        (application as DocScanApplication).let { app ->
            io.execute {
                try {
                    val result = activeStorage.createDocumentCropPreview(page, app.heavyWorkScheduler)
                    runOnUiThread {
                        if (dialog.isShowing) {
                            if (result == null) {
                                message.text = getString(R.string.auto_crop_not_found)
                            } else {
                                candidateBitmap = result.previewBitmap
                                candidatePlan = result.plan
                                candidateReady = true
                                preview.setImageBitmap(result.previewBitmap)
                                message.text = getString(R.string.auto_crop_preview_ready)
                                applyButton.isEnabled = true
                            }
                        } else result?.previewBitmap?.recycle()
                    }
                } catch (failure: Exception) {
                    runOnUiThread {
                        if (dialog.isShowing) message.text = getString(
                            R.string.auto_crop_failed,
                            failure.message ?: failure.javaClass.simpleName,
                        )
                    }
                } catch (_: OutOfMemoryError) {
                    runOnUiThread {
                        if (dialog.isShowing) message.text = getString(R.string.document_crop_oom_page_unchanged)
                    }
                }
            }
        }
    }

    /** Preserve every pixel usable by the 3000 px PDF/JPG export, independent of preview size. */
    private fun decodeEditableImage(file: File): Bitmap? {
        val decoded = ProductImageDecoder.decode(file, 12L * 1024L * 1024L) ?: return null
        val longest = maxOf(decoded.width, decoded.height)
        if (longest <= 3000) return decoded
        val scale = 3000.0 / longest
        return try {
            Bitmap.createScaledBitmap(decoded, maxOf(1, (decoded.width * scale).toInt()),
                maxOf(1, (decoded.height * scale).toInt()), true)
        } finally { decoded.recycle() }
    }

    private fun decodeViewerImage(file: File): android.graphics.Bitmap? {
        return ProductImageDecoder.decode(file, 12L * 1024L * 1024L)
    }

    private fun scheduleOcr(
        activeStorage: ProductStorage,
        page: app.docscan.core.store.StoredPage,
        force: Boolean = false,
        langMode: app.docscan.core.ocr.OcrLangMode? = null,
    ) {
        if (!force && page.recognizedText != null) return
        val app = application as DocScanApplication
        val prefs = getSharedPreferences("docscan_app_preferences", Context.MODE_PRIVATE)
        val mode = langMode ?: readLangMode(prefs)
        app.enqueueOcr(
            page.pageId,
            replaceExisting = force,
            requestKey = "${page.imageVersionId.value}:${page.contentRevision}:${mode.name}",
        ) { ocrAdapter, isCancelled ->
            try {
                if (isCancelled()) return@enqueueOcr
                val imageFile = activeStorage.artifactPath(page.artifactRelativePath).toFile()
                val scheduler = (application as DocScanApplication).heavyWorkScheduler
                val result = scheduler.withAdmission(ocrAdapter.estimateDecodedWorkingSetBytes(imageFile)) {
                    ocrAdapter.recognizeDetailed(imageFile, isCancelled, mode)
                }
                if (isCancelled()) return@enqueueOcr
                try {
                    activeStorage.commitRecognition(
                        RecognitionCandidate(
                            recognitionId = RecognitionId.new(),
                            workspaceId = activeStorage.workspaceId,
                            pageId = page.pageId,
                            imageVersionId = page.imageVersionId,
                            contentRevision = page.contentRevision,
                            desiredOperationKey = OCR_OPERATION_KEY,
                            recognizedText = result.text,
                            geometry = result.geometry,
                            layout = result.layout,
                        ),
                    )
                    val projection = activeStorage.workspaceProjection()
                    runOnUiThread {
                        if (!isFinishing) {
                            status.text = getString(if (result.text.isBlank()) R.string.ocr_no_text else R.string.ocr_complete)
                            renderPages(projection)
                            if (pageViewer?.isShowing == true) {
                                pageViewerPages = projection.pages.toList()
                                pageViewerPages.getOrNull(pageViewerIndex)?.let { currentPage ->
                                    pageViewerText?.text = currentPage.recognizedText?.takeIf { it.isNotBlank() }
                                        ?: getString(R.string.ocr_pending_or_empty)
                                }
                            }
                        }
                    }
                } catch (_: app.docscan.core.store.StoreConflict) {
                    // A Retake/Edit/Delete can make this disposable candidate stale.
                } catch (failure: Exception) {
                    throw failure
                }
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                runOnUiThread {
                    if (!isFinishing) recordError(getString(R.string.ocr_failed, failure.message ?: ""))
                }
                throw failure
            }
        }
    }

    private fun importSelectedDocuments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val activeStorage = storage ?: return
        val importRequestId = currentImportRequestId ?: UUID.randomUUID().toString()
        status.text = getString(R.string.importing_documents)
        importExecutor.execute {
            val importedPageIds = mutableSetOf<PageId>()
            val failures = mutableListOf<String>()
            val initialPageIds = activeStorage.workspaceProjection().pages.map { it.pageId }.toSet()
            uris.forEachIndexed { index, uri ->
                runOnUiThread { status.text = getString(R.string.importing_documents_progress, index + 1, uris.size) }
                try {
                    val mimeType = contentResolver.getType(uri)
                        ?: throw IllegalArgumentException("Selected file type is unknown")
                    val displayName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        .use { cursor: Cursor? ->
                            if (cursor != null && cursor.moveToFirst()) {
                                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                            } else {
                                uri.lastPathSegment ?: "Imported document"
                            }
                        }
                    when {
                        mimeType.equals("application/pdf", ignoreCase = true) -> {
                            val scheduler = (application as DocScanApplication).heavyWorkScheduler
                            val pages = contentResolver.openInputStream(uri)?.use { input ->
                                activeStorage.importPdf(
                                    input,
                                    displayName,
                                    stablePdfImportSessionId(importRequestId, index),
                                    scheduler,
                                ) { completed, total ->
                                    runOnUiThread {
                                        status.text = getString(
                                            R.string.importing_pdf_pages_progress,
                                            displayName,
                                            completed,
                                            total,
                                        )
                                    }
                                }
                            } ?: throw IllegalStateException("Selected PDF cannot be opened")
                            importedPageIds += pages
                        }
                        mimeType.startsWith("image/", ignoreCase = true) -> {
                            val captured = activeStorage.importImageFromProvider(
                                { contentResolver.openInputStream(uri) },
                                mimeType,
                                displayName,
                                stableImageImportSessionId(importRequestId, index),
                            )
                            importedPageIds += captured.pageId
                        }
                        else -> throw IllegalArgumentException("Only images and PDF documents can be imported")
                    }
                } catch (failure: Exception) {
                    failures += failure.message ?: failure.javaClass.simpleName
                }
            }
            val projection = activeStorage.workspaceProjection()
            importedPageIds += projection.pages.filter { it.pageId !in initialPageIds }.map { it.pageId }
            if (autoOcrEnabled) {
                projection.pages.filter { it.pageId in importedPageIds && it.recognizedText == null }
                    .forEach { scheduleOcr(activeStorage, it) }
            }
            runOnUiThread {
                if (failures.isNotEmpty()) {
                    val err = if (importedPageIds.isEmpty()) {
                        getString(R.string.import_documents_failed, failures.size, failures.first())
                    } else {
                        getString(
                            R.string.import_documents_partial,
                            importedPageIds.size,
                            uris.size,
                            failures.size,
                            failures.first(),
                        )
                    }
                    recordError(err)
                } else {
                    status.text = getString(R.string.import_documents_complete, importedPageIds.size, uris.size)
                }
                renderPages(projection)
            }
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun stablePdfImportSessionId(requestId: String, sourceIndex: Int): app.docscan.core.model.ImportSessionId {
        val stable = UUID.nameUUIDFromBytes("$requestId:$sourceIndex".toByteArray()).toString()
        return app.docscan.core.model.ImportSessionId.parse(stable)
    }

    private fun stableImageImportSessionId(requestId: String, sourceIndex: Int): app.docscan.core.model.ImportSessionId {
        val stable = UUID.nameUUIDFromBytes("$requestId:$sourceIndex:image".toByteArray()).toString()
        return app.docscan.core.model.ImportSessionId.parse(stable)
    }

    private fun unbindWorkspaceThumbnails(container: ViewGroup) {
        for (index in 0 until container.childCount) {
            val child = container.getChildAt(index)
            if (child is ImageView) {
                workspaceThumbnailLoader.unbind(child)
            } else if (child is ViewGroup) {
                unbindWorkspaceThumbnails(child)
            }
        }
    }

    internal fun thumbnailFile(page: app.docscan.core.store.StoredPage): File =
        requireNotNull(storage).artifactPath(page.artifactRelativePath).toFile()

    internal fun dpForThumbnail(value: Int): Int = dp(value)

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    internal companion object {
        const val SELF_TEST_VERSION_TAPS = 7
        const val SELF_TEST_TAP_WINDOW_MILLIS = 3_000L
        const val OCR_OPERATION_KEY = "OCR_RUS_ENG_TESSERACT_V1"
        const val WORKSPACE_PAGE_DRAG_LABEL = "docscan.workspace.page"
        const val PAGE_ROW_TAG_PREFIX = "docscan-page-row:"
        const val WORKSPACE_PAGE_THUMBNAIL_TAG_PREFIX = "docscan-workspace-page-thumbnail:"
        const val WORKSPACE_PAGE_SELECTION_TAG_PREFIX = "docscan-workspace-page-selection:"
        const val WORKSPACE_PAGE_DELETE_BUTTON_TAG_PREFIX = "docscan-workspace-page-delete-button:"
        const val VIEWER_THUMBNAIL_STRIP_TAG = "docscan-viewer-thumbnail-strip"
        const val VIEWER_MAIN_IMAGE_TAG = "docscan-viewer-main-image"
        const val VIEWER_TITLE_TAG = "docscan-viewer-title"
        const val VIEWER_PREVIOUS_TAG = "docscan-viewer-previous"
        const val VIEWER_NEXT_TAG = "docscan-viewer-next"
        const val VIEWER_PAGE_THUMBNAIL_TAG_PREFIX = "docscan-viewer-page-thumbnail:"
        const val VIEWER_PAGE_SELECTION_TAG_PREFIX = "docscan-viewer-page-selection:"
        const val PAGE_IMAGE_EDIT_BUTTON_TAG_PREFIX = "docscan-page-edit-image:"
        const val PAGE_DRAG_HANDLE_TAG_PREFIX = "docscan-page-drag:"
        const val PAGE_DROP_BEFORE_TAG_PREFIX = "docscan-page-before:"
        const val PAGE_DROP_AFTER_TAG_PREFIX = "docscan-page-after:"
        const val MAX_SAVE_PAGES = 100
        const val MAX_IMPORTED_PDF_PAGES = 500
        const val EDIT_WORKING_SET_BYTES = 88L * 1024L * 1024L
        const val SAF_ADAPTER_ID = "android-saf-documents-v1"
        const val SAF_ACCESS_CLASS = "PERSISTED_TREE_RW"
        const val STATE_IMPORT_REQUEST_ID = "pending_import_request_id"
        private const val STATE_PENDING_MAIN_SAVE = "pending_main_save"
        private const val STATE_DESTINATION_PICKER_PENDING = "destination_picker_pending"
        private const val STATE_DOCUMENT_MODE = "state_document_mode"
        private const val STATE_BLACK_AND_WHITE_MODE = "state_black_and_white_mode"
    }
}

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
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Minimal product UI for persistent capture and page selection. */
class ProductActivity : AppCompatActivity() {
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val importExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val thumbnailExecutor: ExecutorService = Executors.newSingleThreadExecutor()
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
    private lateinit var previewView: PreviewView
    private lateinit var captureButton: Button
    private lateinit var permissionActionButton: Button
    private lateinit var importButton: Button
    private lateinit var destinationButton: Button
    private lateinit var recentDocumentsButton: Button
    private lateinit var saveButton: Button
    private lateinit var selectionButton: Button
    private lateinit var recoverExportsButton: Button
    private lateinit var resumeExportsButton: Button
    private lateinit var deleteSelectedButton: Button
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
            startCamera()
        } else if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            status.text = getString(R.string.camera_permission_rationale)
            permissionActionButton.text = getString(R.string.retry_camera_permission)
            permissionActionButton.visibility = View.VISIBLE
            permissionActionButton.setOnClickListener { permissionRequest.launch(Manifest.permission.CAMERA) }
        } else {
            status.text = getString(R.string.camera_permission_denied)
            permissionActionButton.text = getString(R.string.open_app_settings)
            permissionActionButton.visibility = View.VISIBLE
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
                            if (!isFinishing) status.text = getString(
                                R.string.resume_pdf_import_failed,
                                failure.message ?: failure.javaClass.simpleName,
                            )
                        }
                    }
                    val refreshed = runCatching { initialized.workspaceProjection() }.getOrNull() ?: return@execute
                    refreshed.pages.filter { it.recognizedText == null && it.artifactState == "COMMITTED" }
                        .forEach { scheduleOcr(initialized, it) }
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
        pageViewer?.dismiss()
        if (::dialogHost.isInitialized) dialogHost.dismissAll()
        cameraProvider?.unbindAll()
        destinationReadyAction = null
        destinationCancelledAction = null
        io.shutdown()
        importExecutor.shutdown()
        workspaceThumbnailLoader.close()
        thumbnailExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        currentImportRequestId?.let { outState.putString(STATE_IMPORT_REQUEST_ID, it) }
        outState.putBoolean(STATE_PENDING_MAIN_SAVE, pendingMainSave)
        outState.putBoolean(STATE_DESTINATION_PICKER_PENDING, destinationPickerPending)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (workspaceActionBlocked) return
        storage?.let(::refreshDestinationGrantStatus)
        if (storage != null && imageCapture == null && hasCameraPermission()) {
            permissionActionButton.visibility = View.GONE
            startCamera()
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
        }
        captureButton = Button(this).apply {
            text = getString(R.string.capture_page)
            tag = "docscan-capture-button"
            isEnabled = false
            setOnClickListener { takePicture() }
        }
        importButton = Button(this).apply {
            text = getString(R.string.home_import)
            contentDescription = getString(R.string.import_images_or_pdf)
            tag = "docscan-import-button"
            setOnClickListener {
                currentImportRequestId = UUID.randomUUID().toString()
                importRequest.launch(arrayOf("image/*", "application/pdf"))
            }
        }
        destinationButton = Button(this).apply {
            text = getString(R.string.choose_save_folder)
            tag = "docscan-destination-button"
            isEnabled = false
            setOnClickListener {
                if (!destinationSetupRunning.get()) launchDestinationPicker()
            }
        }
        recentDocumentsButton = Button(this).apply {
            tag = "docscan-recent-button"
            visibility = View.GONE
            setOnClickListener { showRecentDocuments() }
        }
        saveButton = Button(this).apply {
            text = getString(R.string.export_selected_pages)
            tag = "docscan-save-button"
            isEnabled = false
            setOnClickListener { requestMainSave() }
        }
        selectionButton = Button(this).apply {
            text = getString(R.string.home_select_all)
            contentDescription = getString(R.string.select_all_pages)
            tag = "docscan-selection-button"
            isEnabled = false
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
        deleteSelectedButton = Button(this).apply {
            text = getString(R.string.home_delete)
            contentDescription = getString(R.string.delete_selected_pages)
            tag = "docscan-delete-selected-button"
            isEnabled = false
            setOnClickListener { deletePageBatch(clearAll = false) }
        }
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
        workspaceMenu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "docscan-workspace-menu"
            visibility = View.GONE
        }
        val menuButton = Button(this).apply {
            text = "⋯"; contentDescription = getString(R.string.workspace_menu)
            tag = "docscan-menu-button"; minimumWidth = dp(48); minimumHeight = dp(48)
            setOnClickListener { showWorkspaceMenu() }
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(menuButton, LinearLayout.LayoutParams(dp(56), dp(48)))
            addView(TextView(this@ProductActivity).apply { text = "DocScan"; setTextColor(Color.WHITE); textSize = 18f }, LinearLayout.LayoutParams(0, dp(48), 1f))
            saveButton.contentDescription = getString(R.string.export_selected_pages)
            saveButton.text = getString(R.string.save_pdf_short)
            addView(saveButton, LinearLayout.LayoutParams(dp(150), dp(48)))
        }
        val camera = android.widget.FrameLayout(this).apply {
            tag = "docscan-camera-area"
            addView(previewView, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(top, android.widget.FrameLayout.LayoutParams(-1, dp(56), Gravity.TOP))
            status.apply { setTextColor(Color.WHITE); textSize = 12f; maxLines = 3; setPadding(dp(16), dp(8), dp(16), dp(8)) }
            addView(status, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(60) })
            val shutterRow = android.widget.FrameLayout(this@ProductActivity).apply {
                recentDocumentsButton.apply { textSize = 10f; minWidth = dp(48) }
                addView(recentDocumentsButton, android.widget.FrameLayout.LayoutParams(dp(104), dp(56), Gravity.START or Gravity.CENTER_VERTICAL))
                captureButton.apply {
                    contentDescription = getString(R.string.capture_page); text = "●"; textSize = 36f
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(3), 0xffaab4c4.toInt()) }
                    setTextColor(0xffe4e8ee.toInt())
                }
                addView(captureButton, android.widget.FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
                addView(permissionActionButton, android.widget.FrameLayout.LayoutParams(dp(104), dp(56), Gravity.END or Gravity.CENTER_VERTICAL))
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
                    runOnUiThread { status.text = getString(R.string.capture_error, failure.message.orEmpty()) }
                }
            } },
            onLongClick = { if (canInteractWorkspace()) showWorkspaceMenu() },
        )
        homeRail = RecyclerView(this).apply {
            tag = "docscan-home-thumbnail-scroll"
            layoutManager = LinearLayoutManager(this@ProductActivity, RecyclerView.HORIZONTAL, false)
            adapter = homeThumbnailAdapter
            itemAnimator = null
            isNestedScrollingEnabled = false
            setPadding(dp(8), dp(4), dp(8), dp(4))
            clipToPadding = false
        }
        val homeRailFrame = android.widget.FrameLayout(this).apply {
            tag = "docscan-home-thumbnail-strip"
            addView(homeRail, android.widget.FrameLayout.LayoutParams(-1, -1))
            homeEmptyLabel = TextView(this@ProductActivity).apply {
                text = getString(R.string.no_pages)
                setTextColor(0xffb9c0cb.toInt())
                gravity = Gravity.CENTER
            }
            addView(homeEmptyLabel, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(homeRailFrame, LinearLayout.LayoutParams(-1, dp(124)))
        val actions = LinearLayout(this).apply {
            tag = "docscan-home-actions"; gravity = Gravity.CENTER_VERTICAL
            listOf(importButton, selectionButton, deleteSelectedButton).forEach { button ->
                button.textSize = 12f; button.maxLines = 2; button.minimumWidth = dp(48); button.minimumHeight = dp(48)
                addView(button, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
        }
        root.addView(actions, LinearLayout.LayoutParams(-1, dp(48)))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
    }

    private fun showWorkspaceMenu() {
        if (!canInteractWorkspace()) return
        if (workspaceMenuDialog?.isShowing == true) return
        buildWorkspaceMenuContents()
        workspaceMenu.visibility = View.VISIBLE
        val scroll = ScrollView(this).apply {
            tag = "docscan-workspace-menu-scroll"
            addView(workspaceMenu, ViewGroup.LayoutParams(-1, -2))
        }
        val dialog = dialogHost.builder(this).setTitle(R.string.workspace_menu).setView(scroll)
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
        lastWorkspaceProjection?.let(::renderPages)
    }

    private fun refreshHomeRail(projection: app.docscan.core.store.WorkspaceProjection) {
        homeThumbnailAdapter.submitPages(projection.pages, projection.activePageId)
        homeEmptyLabel.visibility = if (projection.pages.isEmpty()) View.VISIBLE else View.GONE
        val manager = homeRail.layoutManager as? LinearLayoutManager ?: return
        val activeIndex = projection.pages.indexOfFirst { it.pageId == projection.activePageId }
        if (activeIndex >= 0 && (activeIndex < manager.findFirstVisibleItemPosition() || activeIndex > manager.findLastVisibleItemPosition())) {
            homeRail.scrollToPosition(activeIndex)
        }
    }

    private fun buildWorkspaceMenuContents() {
        workspaceMenu.removeAllViews()
        workspaceMenu.addView(versionLabel, matchWrap())
        workspaceMenu.addView(TextView(this).apply {
            text = getString(R.string.default_save_folder_help)
            tag = "docscan-default-folder-help"
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }, matchWrap())
        workspaceMenu.addView(destinationButton, matchWrap())
        workspaceMenu.addView(recoverExportsButton, matchWrap())
        workspaceMenu.addView(resumeExportsButton, matchWrap())
        workspaceMenu.addView(clearWorkspaceButton, matchWrap())
        workspaceEmptyLabel = TextView(this).apply {
            text = getString(R.string.no_pages)
            visibility = View.GONE
        }
        workspaceMenu.addView(workspaceEmptyLabel, matchWrap())
        workspacePageAdapter = WorkspacePageAdapter(
            loader = workspaceThumbnailLoader,
            createRow = ::createWorkspacePageRow,
            bindRowThumbnail = ::bindWorkspacePageThumbnail,
        )
        pagesContainer = RecyclerView(this).apply {
            tag = "docscan-pages-container"
            layoutManager = LinearLayoutManager(this@ProductActivity, RecyclerView.VERTICAL, false)
            adapter = workspacePageAdapter
            itemAnimator = null
            isNestedScrollingEnabled = true
            setItemViewCacheSize(2)
        }
        workspaceMenu.addView(pagesContainer, LinearLayout.LayoutParams(-1, dp(420)))
    }

    private fun lockWorkspace(message: String) {
        workspaceActionBlocked = true
        status.text = message
        listOf(
            captureButton,
            permissionActionButton,
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
        pendingMainSave = true
        prepareSaveDestination(onReady = { promptExportBasename() })
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
        val ready = destinationReadyAction ?: if (pendingMainSave) ({ promptExportBasename() }) else null
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
                status.text = message
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
            if (result.resultCode != RESULT_CANCELED) status.text = getString(R.string.destination_failed, "Picker returned no folder")
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
        if (continueSave) {
            destinationSetupRunning.set(true)
            destinationButton.isEnabled = false
            status.text = getString(R.string.save_destination_checking)
        }
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
                if (continueSave) {
                    checkSaveDestination(activeStorage, uri)
                } else {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) status.text = getString(
                            if (oldGrantCleanupFailed) R.string.destination_saved_cleanup_failed else R.string.destination_saved,
                        )
                    }
                    updateSaveButtonState(activeStorage)
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    if (continueSave) finishDestinationSetup(failure)
                    else if (!isFinishing && !isDestroyed) status.text = getString(
                        R.string.destination_failed, failure.message ?: failure.javaClass.simpleName,
                    )
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
                if (!valid) status.text = getString(R.string.destination_revoked)
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
        }
    }

    private fun toggleAllPageSelection() {
        if (!canInteractWorkspace()) return
        val activeStorage = storage ?: return
        val pages = runCatching { activeStorage.workspaceProjection().pages }.getOrNull() ?: return
        if (pages.isEmpty()) return
        val selectAll = pages.any { !it.selected }
        selectionButton.isEnabled = false
        io.execute {
            try {
                activeStorage.setAllVisiblePagesSelected(selectAll)
                val projection = activeStorage.workspaceProjection()
                runOnUiThread {
                    status.text = getString(if (selectAll) R.string.all_pages_selected else R.string.all_pages_deselected)
                    renderPages(projection)
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    status.text = getString(R.string.selection_update_failed, failure.message ?: failure.javaClass.simpleName)
                    selectionButton.isEnabled = true
                }
            }
        }
    }

    private fun promptExportBasename() {
        val now = java.time.LocalDateTime.now()
        val suggestedName = runCatching {
            storage?.workspaceProjection()?.pages.orEmpty()
                .filter { it.selected }
                .sortedBy { it.orderIndex }
                .map { it.recognizedText }
                .let { OutputBasenameSuggester.suggest(it, now) }
        }.getOrElse { OutputBasenameSuggester.suggest(emptyList(), now) }
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(suggestedName)
            selectAll()
        }
        dialogHost.builder(this)
            .setTitle(R.string.export_basename_title)
            .setMessage(R.string.export_basename_message)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.export_selected_pages) { _, _ -> exportSelectedPages(input.text.toString()) }
            .show()
    }

    private fun exportSelectedPages(basename: String) {
        val activeStorage = storage ?: return
        if (!beginExternalWorkflow()) return
        saveButton.isEnabled = false
        status.text = getString(R.string.export_preparing)
        io.execute {
            var exportId: ExportArtifactId? = null
            var confirmed = 0
            var expected = 0
            var candidateCleanupPending = false
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

                val selectedCount = activeStorage.workspaceProjection().pages.count { it.selected }
                if (selectedCount == 0) error(getString(R.string.export_no_selected_pages))
                if (selectedCount > MAX_SAVE_PAGES) {
                    error(getString(R.string.export_page_limit, selectedCount, MAX_SAVE_PAGES))
                }
                expected = selectedCount + 1
                val gateway = SafExportGateway(this, activeStorage)
                val nameResolution = gateway.resolveOutputNames(grant.treeUri, basename, selectedCount)
                val names = when (nameResolution) {
                    is OutputNameResolution.Resolved -> nameResolution.names
                    is OutputNameResolution.Invalid -> error(nameResolution.reason)
                    is OutputNameResolution.Collision -> error(
                        getString(R.string.export_name_collision, nameResolution.conflictingNames.joinToString()),
                    )
                }
                val frozen = activeStorage.freezeSelectedPagesForSave()
                if (frozen.pages.size != selectedCount) {
                    activeStorage.cancelUnstartedSaveManifest(frozen.saveManifestId)
                    error(getString(R.string.export_selection_changed))
                }
                val outputSet = activeStorage.composeFrozenSaveOutputSet(
                    frozen,
                    PdfOutputSettings(595.28f, 841.89f, 0.85f, 300),
                    companionJpegQuality = 85,
                )
                val namesInOrder = listOf(names.pdfName) + names.pageJpegNames
                val externalPackage = ExternalExportPackagePlan(
                    destinationTreeUri = grant.treeUri,
                    commonBasename = names.basename,
                    providerQualificationId = qualification.qualificationId,
                    adapterId = tuple.adapterId,
                    providerVersion = tuple.providerVersion,
                    osVersion = tuple.osVersion,
                    destinationAccessClass = tuple.accessClass,
                    outputDisplayNames = namesInOrder,
                    ocrStatus = when (frozen.pages.count { it.recognizedGeometry?.words?.isNotEmpty() == true }) {
                        frozen.pages.size -> "FULL"
                        0 -> "NONE"
                        else -> "PARTIAL"
                    },
                )
                val committedExportId = activeStorage.commitLocalExportOutputSet(frozen, outputSet, externalPackage)
                exportId = committedExportId
                candidateCleanupPending = activeStorage.cleanupCommittedLocalOutputCandidate(frozen.saveManifestId).isFailure
                check(activeStorage.committedExportOutputs(committedExportId).size == expected) {
                    "Committed output count differs from frozen Save"
                }
                updateRecoveryActions(activeStorage)
                confirmed = runExternalExportPackage(activeStorage, gateway, committedExportId)
                updateRecentDocuments(activeStorage)
                runOnUiThread {
                    status.text = if (candidateCleanupPending) {
                        getString(R.string.export_success_cleanup_pending, expected)
                    } else getString(R.string.export_success, expected)
                }
            } catch (failure: Exception) {
                val detail = (failure.message ?: failure.javaClass.simpleName) + if (candidateCleanupPending) {
                    " — ${getString(R.string.local_candidate_cleanup_pending_suffix)}"
                } else ""
                val savedAttempts = exportId?.let(activeStorage::exportAttempts).orEmpty()
                val confirmedSoFar = maxOf(confirmed, savedAttempts.count { it.state == "SUCCEEDED" })
                val unresolved = savedAttempts.count { it.state == "UNKNOWN_OUTCOME" || it.state == "WRITING_EXTERNAL" }
                val message = if (confirmedSoFar > 0 || unresolved > 0) {
                    getString(R.string.export_partial, confirmedSoFar, expected, unresolved, detail)
                } else {
                    getString(R.string.export_failed, detail)
                }
                runOnUiThread { status.text = message }
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
                }
                updateSaveButtonState(activeStorage)
                updateBulkActionButtons(projection?.pages.orEmpty())
                updateRecoveryActions(activeStorage)
            }
        }
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
                runOnUiThread { status.text = getString(R.string.export_failed, failure.message ?: failure.javaClass.simpleName) }
            } finally {
                externalWorkflowRunning.set(false)
                runOnUiThread { setWorkspaceInteractionEnabled(true) }
                updateRecoveryActions(activeStorage)
                updateSaveButtonState(activeStorage)
                updateBulkActionButtons(activeStorage.workspaceProjection().pages)
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
                runOnUiThread { status.text = getString(R.string.export_failed, failure.message ?: failure.javaClass.simpleName) }
            } finally {
                externalWorkflowRunning.set(false)
                runOnUiThread { setWorkspaceInteractionEnabled(true) }
                updateRecoveryActions(activeStorage)
                updateSaveButtonState(activeStorage)
                updateBulkActionButtons(activeStorage.workspaceProjection().pages)
            }
        }
    }

    private fun beginExternalWorkflow(): Boolean {
        if (workspaceActionBlocked) return false
        if (!externalWorkflowRunning.compareAndSet(false, true)) {
            status.text = getString(R.string.workspace_operation_already_running)
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
        if (!enabled) {
            fun disable(view: View) {
                view.isEnabled = false
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) disable(view.getChildAt(index))
                }
            }
            pagesContainer?.let(::disable)
            disable(homeRail)
            return
        }

        // RecyclerView/ListAdapter can legitimately skip an identical-list diff. Restore the
        // container itself and explicitly rebind attached rows so their child enabled state is
        // recomputed from current workflow guards.
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
        check(expectedCount == outputsById.size) { "Package output mapping differs from committed ExportArtifact" }
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
                runOnUiThread { status.text = getString(R.string.recent_documents_failed, it.message ?: it.javaClass.simpleName) }
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
            status.text = getString(R.string.recent_document_unavailable, "Unsupported saved PDF page count")
            return
        }
        val session = try {
            SavedPdfEditSession(pageCount)
        } catch (failure: Exception) {
            status.text = getString(R.string.recent_document_unavailable, failure.message ?: failure.javaClass.simpleName)
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
                    status.text = if (exportId != null && (confirmed > 0 || unknown > 0)) {
                        getString(R.string.export_partial, confirmed, 1, unknown, detail)
                    } else getString(R.string.saved_pdf_edit_save_failed, detail)
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
            status.text = getString(R.string.recent_document_unavailable, "Unsupported saved PDF page count")
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
            status.text = getString(R.string.try_again_already_running)
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
                    status.text = getString(R.string.try_again_failed, failure.message ?: failure.javaClass.simpleName)
                    savedPdfImportRunning.set(false)
                }
                return@execute
            }
            projection.pages.filter { it.pageId in importedPageIds && it.recognizedText == null }
                .forEach { scheduleOcr(activeStorage, it) }
            runOnUiThread {
                try {
                    val failed = failureMessage
                    status.text = when {
                        failed == null -> getString(R.string.try_again_complete, importedPageIds.size)
                        importedPageIds.isEmpty() -> getString(R.string.try_again_failed, failed)
                        else -> getString(R.string.try_again_partial, importedPageIds.size, failed)
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
            status.text = getString(R.string.recent_document_unavailable, failure.message ?: failure.javaClass.simpleName)
        }
    }

    private fun startCamera() {
        if (workspaceActionBlocked || isFinishing || cameraStartupPending || imageCapture != null || !hasCameraPermission()) return
        cameraStartupPending = true
        val statusAtStart = status.text?.toString()
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.getSurfaceProvider()) }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                imageCapture = capture
                cameraStartupPending = false
                captureButton.isEnabled = true
                if (status.text?.toString() == statusAtStart) {
                    status.text = getString(R.string.camera_ready)
                }
            } catch (failure: Exception) {
                cameraStartupPending = false
                if (status.text?.toString() == statusAtStart) {
                    status.text = getString(R.string.camera_error, failure.message ?: failure.javaClass.simpleName)
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
        status.text = getString(R.string.capturing)
        val temp = File(cacheDir, "capture-${System.nanoTime()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(temp).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                // Instantly re-enable shutter button for non-blocking burst capture
                captureButton.isEnabled = imageCapture != null
                io.execute {
                    try {
                        val artifact = FileInputStream(temp).use(activeStorage::stageCapturedJpeg)
                        val affectedPageId: PageId
                        if (pageBeingRetaken != null) {
                            activeStorage.replacePageImage(pageBeingRetaken, artifact)
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
                                ),
                            )
                        }
                        val projection = activeStorage.workspaceProjection()
                        projection.pages.firstOrNull { it.pageId == affectedPageId }?.let {
                            scheduleOcr(activeStorage, it)
                        }
                        runOnUiThread {
                            captureButton.isEnabled = imageCapture != null
                            retakeTarget = null
                            status.text = getString(if (pageBeingRetaken == null) R.string.capture_saved else R.string.retake_saved)
                            renderPages(projection)
                        }
                    } catch (failure: Exception) {
                        runOnUiThread {
                            captureButton.isEnabled = imageCapture != null
                            retakeTarget = null
                            status.text = getString(R.string.capture_error, failure.message ?: failure.javaClass.simpleName)
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
                status.text = getString(R.string.capture_error, exception.message ?: exception.imageCaptureError.toString())
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
            "${getString(R.string.save_pdf_short)} ($selectedCount)"
        } else {
            getString(R.string.save_pdf_short)
        }
        deleteSelectedButton.isEnabled = !externalWorkflowRunning.get() && selectedCount > 0
        clearWorkspaceButton.isEnabled = !externalWorkflowRunning.get() && pages.isNotEmpty()
        selectionButton.isEnabled = !externalWorkflowRunning.get() && pages.isNotEmpty()
        val allSelected = pages.isNotEmpty() && pages.all { it.selected }
        selectionButton.text = getString(if (allSelected) R.string.home_deselect_all else R.string.home_select_all)
        selectionButton.contentDescription = getString(if (allSelected) R.string.deselect_all_pages else R.string.select_all_pages)
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
                            runOnUiThread { status.text = getString(R.string.capture_error, failure.message ?: "") }
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
                                runOnUiThread { status.text = getString(R.string.capture_error, failure.message ?: "") }
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
                runOnUiThread { status.text = getString(R.string.capture_error, failure.message ?: "") }
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
                runOnUiThread { status.text = getString(R.string.capture_error, failure.message ?: "") }
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
                    Snackbar.make(dialogHost.foregroundView ?: root, R.string.page_deleted, 8_000)
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
                if (refreshed != null) {
                    runOnUiThread {
                        setWorkspaceInteractionEnabled(true)
                        renderPages(refreshed)
                        updateRecoveryActions(activeStorage)
                    }
                } else {
                    runOnUiThread {
                        setWorkspaceInteractionEnabled(true)
                        saveButton.isEnabled = false
                        updateBulkActionButtons(emptyList())
                    }
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
                    Snackbar.make(dialogHost.foregroundView ?: root, getString(R.string.pages_deleted, receipt.pageIds.size), 8_000)
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
                if (refreshed != null) {
                    runOnUiThread {
                        setWorkspaceInteractionEnabled(true)
                        renderPages(refreshed)
                        updateRecoveryActions(activeStorage)
                    }
                } else {
                    runOnUiThread {
                        setWorkspaceInteractionEnabled(true)
                        saveButton.isEnabled = false
                        updateBulkActionButtons(emptyList())
                    }
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
                projection.pages.filter { it.recognizedText == null }.forEach { scheduleOcr(activeStorage, it) }
                runOnUiThread {
                    status.text = getString(R.string.delete_batch_undo_complete)
                    renderPages(projection)
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    status.text = getString(R.string.delete_undo_failed, failure.message ?: "")
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
                projection.pages.filter { it.recognizedText == null }.forEach { scheduleOcr(activeStorage, it) }
                runOnUiThread {
                    status.text = getString(R.string.delete_undo_complete)
                    renderPages(projection)
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    status.text = getString(R.string.delete_undo_failed, failure.message ?: "")
                }
            }
        }
    }

    private fun showPageViewer(pages: List<app.docscan.core.store.StoredPage>, index: Int) {
        if (!canInteractWorkspace() || pages.isEmpty() || index !in pages.indices) return
        pageViewer?.dismiss()
        pageViewerPages = pages.toList()
        pageViewerIndex = index

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        pageViewerTitle = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            tag = VIEWER_TITLE_TAG
        }
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val previous = Button(this).apply {
            text = getString(R.string.viewer_previous)
            tag = VIEWER_PREVIOUS_TAG
            setOnClickListener { showViewerPage(pageViewerIndex - 1) }
        }
        val next = Button(this).apply {
            text = getString(R.string.viewer_next)
            tag = VIEWER_NEXT_TAG
            setOnClickListener { showViewerPage(pageViewerIndex + 1) }
        }
        pageViewerPrevious = previous
        pageViewerNext = next
        navigation.addView(previous)
        navigation.addView(next)
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
        pageViewerImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            tag = VIEWER_MAIN_IMAGE_TAG
            var touchDownX = 0f
            var touchDownY = 0f
            setOnTouchListener { _, event ->
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
                            // At either edge, consume the gesture without wrapping.
                            if (destination in pageViewerPages.indices) showViewerPage(destination)
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> true
                }
            }
        }
        pageViewerText = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, 0)
        }
        content.addView(pageViewerTitle, matchWrap())
        content.addView(pageViewerImage, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        content.addView(ScrollView(this).apply {
            addView(pageViewerText)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(112)))

        content.addView(viewerFooter(requireNotNull(pageViewerStripScroll), navigation), LinearLayout.LayoutParams(-1, dp(172)))

        pageViewer = dialogHost.builder(this)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener {
                    pageViewerGeneration += 1
                    (pageViewerImage?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.recycle()
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
            cell.addView(thumbnail, LinearLayout.LayoutParams(dp(88), dp(64)))
            cell.addView(CheckBox(this).apply {
                // Match Home: the checkbox carries state; the full action is announced accessibly.
                contentDescription = getString(R.string.include_page_in_save, index + 1)
                minimumWidth = dp(56)
                minimumHeight = dp(48)
                tag = VIEWER_PAGE_SELECTION_TAG_PREFIX + page.pageId.value
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
                            runOnUiThread { status.text = getString(R.string.capture_error, failure.message ?: "") }
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
                    dp(88) * 2,
                    dp(64) * 2,
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
        status.text = getString(R.string.active_page, index + 1)
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
        val hint = TextView(this).apply {
            text = getString(R.string.edit_adjustment_summary, 0, 100, 0, 0)
            gravity = Gravity.CENTER
        }
        val rotateButton = Button(this).apply {
            text = getString(R.string.edit_rotate_clockwise)
            minimumWidth = dp(48)
            minimumHeight = dp(48)
        }
        val cropButton = Button(this).apply {
            text = getString(R.string.edit_crop_four_points)
            minimumWidth = dp(48)
            minimumHeight = dp(48)
        }
        val undoButton = Button(this).apply {
            text = getString(R.string.edit_undo)
            minimumWidth = dp(48)
            minimumHeight = dp(48)
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
        fun adjustmentControl(title: Int, bar: android.widget.SeekBar) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@ProductActivity).apply { text = getString(title) }, matchWrap())
            addView(
                bar,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)),
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(hint, matchWrap())
            addView(rotateButton, matchWrap())
            addView(cropButton, matchWrap())
            addView(undoButton, matchWrap())
            addView(adjustmentControl(R.string.edit_brightness, brightness), matchWrap())
            addView(adjustmentControl(R.string.edit_contrast, contrast), matchWrap())
            addView(adjustmentControl(R.string.edit_sharpness, sharpness), matchWrap())
            addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)))
        }
        val editorScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val draft = PageImageEditDraft()
        var previewBitmap: android.graphics.Bitmap? = null
        var sourceBitmap: android.graphics.Bitmap? = null
        var previewGeneration = 0
        var pendingWork = 0
        var dismissed = false
        var busy = false
        var syncingControls = false
        val dialog = dialogHost.builder(this)
            .setTitle(R.string.edit_image_title)
            .setView(editorScroll)
            .setNegativeButton(R.string.edit_cancel, null)
            .setPositiveButton(R.string.edit_save, null)
            .create()
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
            hint.text = getString(
                R.string.edit_adjustment_summary,
                draft.state.clockwiseTurns * 90,
                draft.state.brightness,
                draft.state.contrastPercent,
                draft.state.sharpnessPercent,
            )
            if (dialog.isShowing) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = sourceBitmap != null && draft.isDirty && !busy
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = !busy
            }
            val ready = sourceBitmap != null
            rotateButton.isEnabled = ready && !busy
            cropButton.isEnabled = ready && !busy
            undoButton.isEnabled = ready && draft.canUndo && !busy
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
                            status.text = getString(R.string.edit_preview_failed, it.message ?: it.javaClass.simpleName)
                        }
                    } else candidate?.recycle()
                    releaseIfClosed()
                }
            }
        }
        fun saveDraft() {
            if (!draft.isDirty || busy) return
            val source = sourceBitmap ?: return
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
                    ) { PageImageAdjustmentProcessor.render(source, state) }
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
                        status.text = getString(R.string.capture_error, failure?.message ?: "Image edit failed")
                        updateControls()
                    }
                    releaseIfClosed()
                }
            }
        }
        fun showManualCropSelector() {
            val source = sourceBitmap ?: return
            val selection = CropSelectionView(this).apply {
                contentDescription = getString(R.string.manual_crop_view_description)
            }
            val message = TextView(this).apply { text = getString(R.string.manual_crop_preparing) }
            val cropContent = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                addView(message, matchWrap())
                addView(selection, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(dp(96), minOf(dp(420), root.height - dp(400)))))
            }
            var cropPreview: android.graphics.Bitmap? = null
            val cropDialog = dialogHost.builder(this)
                .setTitle(R.string.manual_crop_title)
                .setView(cropContent)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.manual_crop_apply, null)
                .create()
            cropDialog.setCanceledOnTouchOutside(false)
            cropDialog.setOnDismissListener {
                selection.bitmap = null
                cropPreview?.recycle()
                cropPreview = null
            }
            cropDialog.show()
            val applyButton = cropDialog.getButton(AlertDialog.BUTTON_POSITIVE).apply { isEnabled = false }
            applyButton.setOnClickListener {
                if (!applyButton.isEnabled) return@setOnClickListener
                draft.apply(draft.state.copy(crop = selection.quadrilateral))
                updateControls()
                requestPreview(draft.state)
                cropDialog.dismiss()
            }
            pendingWork++
            val uncroppedState = draft.state.copy(crop = null)
            io.execute {
                var rendered: android.graphics.Bitmap? = null
                var failure: Exception? = null
                try {
                    rendered = (application as DocScanApplication).heavyWorkScheduler.withAdmission(
                        EDIT_WORKING_SET_BYTES,
                    ) { PageImageAdjustmentProcessor.render(source, uncroppedState) }
                } catch (problem: Exception) {
                    failure = problem
                }
                runOnUiThread {
                    pendingWork--
                    if (cropDialog.isShowing) {
                        val readyPreview = rendered
                        if (readyPreview != null) {
                            cropPreview = readyPreview
                            selection.bitmap = readyPreview
                            message.text = getString(R.string.manual_crop_instruction)
                            applyButton.isEnabled = true
                        } else {
                            message.text = getString(
                                R.string.edit_preview_failed,
                                failure?.message ?: "Image crop preview failed",
                            )
                        }
                    } else rendered?.recycle()
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
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode != android.view.KeyEvent.KEYCODE_BACK
                || event.action != android.view.KeyEvent.ACTION_UP
            ) return@setOnKeyListener false
            if (!draft.isDirty) return@setOnKeyListener false
            promptDirtyExit()
            true
        }
        dialog.show()
        updateControls()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            if (!draft.isDirty && !busy) {
                dialog.dismiss()
            } else if (draft.isDirty) promptDirtyExit()
        }
        rotateButton.setOnClickListener {
            val current = draft.state
            val nextTurns = (current.clockwiseTurns + 1) % 4
            val deltaTurns = (nextTurns - current.clockwiseTurns + 4) % 4
            val crop = current.crop?.rotatedClockwise(deltaTurns)
            draft.apply(current.copy(clockwiseTurns = nextTurns, crop = crop))
            updateControls()
            requestPreview(draft.state)
        }
        cropButton.setOnClickListener { showManualCropSelector() }
        undoButton.setOnClickListener {
            if (draft.canUndo) draft.undo()
            updateControls()
            requestPreview(draft.state)
        }
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
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { saveDraft() }
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
                    if (dialog.isShowing) status.text = getString(
                        R.string.edit_preview_failed, failure.message ?: failure.javaClass.simpleName,
                    )
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
            if (!candidateReady) return@setOnClickListener
            dialog.dismiss()
            status.text = getString(R.string.auto_crop_applying)
            io.execute {
                try {
                    val projection = activeStorage.applyDocumentCrop(
                        page,
                        (application as DocScanApplication).heavyWorkScheduler,
                    )
                    runOnUiThread {
                        status.text = getString(R.string.auto_crop_applied)
                        renderPages(projection)
                    }
                } catch (failure: Exception) {
                    runOnUiThread {
                        status.text = getString(R.string.capture_error, failure.message ?: failure.javaClass.simpleName)
                    }
                }
            }
        }
        (application as DocScanApplication).let { app ->
            io.execute {
                try {
                    val candidate = activeStorage.createDocumentCropPreview(page, app.heavyWorkScheduler)
                    runOnUiThread {
                        if (dialog.isShowing) {
                            if (candidate == null) {
                                message.text = getString(R.string.auto_crop_not_found)
                            } else {
                                candidateBitmap = candidate
                                candidateReady = true
                                preview.setImageBitmap(candidate)
                                message.text = getString(R.string.auto_crop_preview_ready)
                                applyButton.isEnabled = true
                            }
                        } else candidate?.recycle()
                    }
                } catch (failure: Exception) {
                    runOnUiThread {
                        if (dialog.isShowing) message.text = getString(
                            R.string.auto_crop_failed,
                            failure.message ?: failure.javaClass.simpleName,
                        )
                    }
                }
            }
        }
    }

    private fun decodeViewerImage(file: File): android.graphics.Bitmap? {
        return ProductImageDecoder.decode(file, 12L * 1024L * 1024L)
    }

    private fun scheduleOcr(
        activeStorage: ProductStorage,
        page: app.docscan.core.store.StoredPage,
        force: Boolean = false,
    ) {
        if (!force && page.recognizedText != null) return
        val app = application as DocScanApplication
        app.enqueueOcr(page.pageId) { ocrAdapter, isCancelled ->
            try {
                if (isCancelled()) return@enqueueOcr
                val imageFile = activeStorage.artifactPath(page.artifactRelativePath).toFile()
                val scheduler = (application as DocScanApplication).heavyWorkScheduler
                val result = scheduler.withAdmission(ocrAdapter.estimateDecodedWorkingSetBytes(imageFile)) {
                    ocrAdapter.recognizeDetailed(imageFile, isCancelled)
                }
                if (isCancelled()) return@enqueueOcr
                io.execute {
                    try {
                        if (isCancelled()) return@execute
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
                            ),
                        )
                        val projection = activeStorage.workspaceProjection()
                        runOnUiThread {
                            if (!isFinishing) {
                                status.text = getString(R.string.ocr_complete)
                                renderPages(projection)
                            }
                        }
                    } catch (_: app.docscan.core.store.StoreConflict) {
                        // A Retake/Edit/Delete can make this disposable candidate stale.
                    } catch (failure: Exception) {
                        runOnUiThread {
                            if (!isFinishing) status.text = getString(R.string.ocr_failed, failure.message ?: "")
                        }
                    }
                }
            } catch (_: java.util.concurrent.CancellationException) {
                // Deleted/replaced Pages discard their in-flight native OCR result.
            } catch (failure: Exception) {
                runOnUiThread {
                    if (!isFinishing) status.text = getString(R.string.ocr_failed, failure.message ?: "")
                }
            }
        }
    }

    private fun importSelectedDocuments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val activeStorage = storage ?: return
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
                                    stablePdfImportSessionId(currentImportRequestId ?: UUID.randomUUID().toString(), index),
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
                                stableImageImportSessionId(currentImportRequestId ?: UUID.randomUUID().toString(), index),
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
            projection.pages.filter { it.pageId in importedPageIds && it.recognizedText == null }
                .forEach { scheduleOcr(activeStorage, it) }
            runOnUiThread {
                status.text = when {
                    failures.isEmpty() -> getString(R.string.import_documents_complete, importedPageIds.size, uris.size)
                    importedPageIds.isEmpty() -> getString(R.string.import_documents_failed, failures.size, failures.first())
                    else -> getString(
                        R.string.import_documents_partial,
                        importedPageIds.size,
                        uris.size,
                        failures.size,
                        failures.first(),
                    )
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
    }
}

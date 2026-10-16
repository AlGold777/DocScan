package app.docscan.core.edit

/** Volatile, multi-operation image edit state; pixels are rendered from the immutable source. */
data class PageImageEditState(
    val clockwiseTurns: Int = 0,
    val brightness: Int = 0,
    val contrastPercent: Int = 100,
    val sharpnessPercent: Int = 0,
    val crop: CropQuadrilateral? = null,
) {
    init {
        require(clockwiseTurns in 0..3)
        require(brightness in -100..100)
        require(contrastPercent in 0..200)
        require(sharpnessPercent in 0..200)
    }
}

class PageImageEditDraft {
    private val undoStates = mutableListOf<PageImageEditState>()
    private var savedState = PageImageEditState()
    private var previewOrigin: PageImageEditState? = null

    var state: PageImageEditState = savedState
        private set

    val isDirty: Boolean get() = state != savedState || undoStates.isNotEmpty()
    val canUndo: Boolean get() = undoStates.isNotEmpty()

    fun preview(next: PageImageEditState) {
        if (previewOrigin == null) previewOrigin = state
        state = next
    }

    fun commitPreview() {
        val origin = previewOrigin ?: return
        if (state != origin) undoStates += origin
        previewOrigin = null
    }

    fun apply(next: PageImageEditState) {
        commitPreview()
        if (next == state) return
        undoStates += state
        state = next
    }

    fun undo() {
        check(canUndo) { "There is no image edit to undo" }
        previewOrigin = null
        state = undoStates.removeAt(undoStates.lastIndex)
        if (state == savedState) undoStates.clear()
    }

    fun markSaved() {
        commitPreview()
        savedState = state
        undoStates.clear()
    }
}

package app.docscan.android

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import app.docscan.core.model.PageId
import app.docscan.core.store.StoredPage
import java.util.UUID

/** Stable-identity camera rail with drag & drop reordering, double-tap viewer open, and responsive buttons. */
internal class WorkspaceHomeAdapter(
    private val activity: ProductActivity,
    private val loader: WorkspaceThumbnailLoader,
    private val enabled: () -> Boolean,
    private val onPageClick: (StoredPage) -> Unit,
    private val onSelectionChanged: (StoredPage, Boolean) -> Unit,
    private val onPageActive: ((StoredPage) -> Unit)? = null,
) : RecyclerView.Adapter<WorkspaceHomeAdapter.Holder>() {
    internal data class Item(val page: StoredPage, val index: Int, val active: Boolean)

    private val items = mutableListOf<Item>()

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = stableIdFor(items[position].page.pageId)

    override fun getItemCount(): Int = items.size

    internal fun getItem(position: Int): Item = items[position]

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(activity).also { holder ->
        holder.thumbnail.layoutParams = LinearLayout.LayoutParams(activity.dpForThumbnail(72), activity.dpForThumbnail(92))
        holder.selection.layoutParams = LinearLayout.LayoutParams(activity.dpForThumbnail(72), activity.dpForThumbnail(32)).apply {
            topMargin = activity.dpForThumbnail(2)
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = bind(holder, items[position])

    override fun onViewAttachedToWindow(holder: Holder) {
        super.onViewAttachedToWindow(holder)
        holder.boundItem?.let { bindThumbnail(holder, it) }
    }

    override fun onViewDetachedFromWindow(holder: Holder) {
        loader.unbind(holder.thumbnail)
        super.onViewDetachedFromWindow(holder)
    }

    override fun onViewRecycled(holder: Holder) {
        loader.unbind(holder.thumbnail)
        holder.boundItem = null
        super.onViewRecycled(holder)
    }

    private fun bind(holder: Holder, item: Item) {
        val isItemEnabled = enabled()
        holder.boundItem = item
        holder.itemView.isEnabled = isItemEnabled
        holder.thumbnail.isEnabled = isItemEnabled
        holder.thumbnail.tag = HOME_THUMBNAIL_TAG_PREFIX + item.page.pageId.value
        holder.thumbnail.contentDescription = activity.getString(
            if (item.active) R.string.active_page else R.string.page_image_description,
            item.index + 1,
        )
        holder.thumbnail.background = GradientDrawable().apply {
            setColor(0xff303030.toInt())
            setStroke(activity.dpForThumbnail(if (item.active) 3 else 1), if (item.active) Color.WHITE else 0xff777777.toInt())
        }

        holder.selection.tag = HOME_SELECTION_TAG_PREFIX + item.page.pageId.value
        holder.selection.contentDescription = activity.getString(R.string.include_page_in_save, item.index + 1)
        holder.selection.minimumWidth = activity.dpForThumbnail(32)
        holder.selection.minimumHeight = activity.dpForThumbnail(32)
        holder.selection.isEnabled = isItemEnabled
        holder.selection.setOnCheckedChangeListener(null)
        holder.selection.isChecked = item.page.selected
        holder.selection.setOnCheckedChangeListener { checkbox, checked ->
            if (!enabled()) {
                checkbox.isChecked = item.page.selected
                return@setOnCheckedChangeListener
            }
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos in items.indices) {
                val page = items[pos].page
                if (page.selected != checked) {
                    onSelectionChanged(page, checked)
                }
            }
        }
        if (holder.itemView.isAttachedToWindow) bindThumbnail(holder, item)
    }

    private fun bindThumbnail(holder: Holder, item: Item) {
        if (item.page.artifactState != "COMMITTED") {
            loader.unbind(holder.thumbnail)
            return
        }
        val file = activity.thumbnailFile(item.page)
        loader.bind(
            holder.thumbnail,
            WorkspaceThumbnailLoader.Key(
                pageId = item.page.pageId,
                imageVersionId = item.page.imageVersionId,
                sourceSizeBytes = file.length(),
                targetWidthPx = activity.dpForThumbnail(72) * 2,
                targetHeightPx = activity.dpForThumbnail(92) * 2,
            ),
            file,
        )
    }

    internal fun submitPages(pages: List<StoredPage>, activePageId: PageId?) {
        val newItems = pages.mapIndexed { index, page -> Item(page, index, page.pageId == activePageId) }
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = items.size
            override fun getNewListSize(): Int = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean = items[oldPos].page.pageId == newItems[newPos].page.pageId
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean = items[oldPos] == newItems[newPos]
        })
        items.clear()
        items.addAll(newItems)
        diffResult.dispatchUpdatesTo(this)
    }

    internal fun currentPages(): List<StoredPage> = items.map { it.page }

    internal fun moveItem(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition !in items.indices || toPosition !in items.indices || fromPosition == toPosition) return false
        val item = items.removeAt(fromPosition)
        items.add(toPosition, item)
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    /** Rebind attached checkboxes after the workspace busy state changes without a page diff. */
    internal fun refreshBoundItems() {
        notifyDataSetChanged()
    }

    internal inner class Holder(activity: ProductActivity) : RecyclerView.ViewHolder(
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(activity.dpForThumbnail(4), 0, activity.dpForThumbnail(4), 0)
        },
    ) {
        val thumbnail = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(activity.dpForThumbnail(3), activity.dpForThumbnail(3), activity.dpForThumbnail(3), activity.dpForThumbnail(3))
        }
        val selection = CheckBox(activity)
        var boundItem: Item? = null

        init {
            val isItemEnabled = enabled()
            itemView.isEnabled = isItemEnabled
            thumbnail.isEnabled = isItemEnabled
            selection.isEnabled = isItemEnabled
            (itemView as LinearLayout).apply {
                addView(thumbnail)
                addView(selection)
                layoutParams = RecyclerView.LayoutParams(activity.dpForThumbnail(84), ViewGroup.LayoutParams.MATCH_PARENT)
            }
            thumbnail.setOnClickListener { handlePageTap(this, thumbnail) }
            itemView.setOnClickListener { handlePageTap(this, itemView) }
        }
    }

    private var lastClickTime = 0L
    private var lastClickPageId: PageId? = null
    private val DOUBLE_TAP_TIMEOUT_MS = 500L

    private fun handlePageTap(holder: Holder, view: View) {
        if (!enabled()) return
        val pos = holder.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION || pos !in items.indices) return
        val item = items[pos]
        val page = item.page
        val now = android.os.SystemClock.uptimeMillis()
        val isDoubleTap = page.pageId == lastClickPageId && (now - lastClickTime) < DOUBLE_TAP_TIMEOUT_MS
        val isAlreadyActive = item.active

        lastClickTime = now
        lastClickPageId = page.pageId

        if (isDoubleTap || isAlreadyActive) {
            activity.uiHapticFeedback(view)
            onPageClick(page)
        } else {
            activity.uiHapticFeedback(view)
            onPageActive?.invoke(page)
        }
    }

    companion object {
        const val HOME_THUMBNAIL_TAG_PREFIX = "docscan-home-page:"
        const val HOME_SELECTION_TAG_PREFIX = "docscan-home-selection:"

        internal fun stableIdFor(pageId: PageId): Long = runCatching {
            UUID.fromString(pageId.value).mostSignificantBits
        }.getOrElse { pageId.value.hashCode().toLong() }
    }
}

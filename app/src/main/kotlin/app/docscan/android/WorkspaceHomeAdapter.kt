package app.docscan.android

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.docscan.core.model.PageId
import app.docscan.core.store.StoredPage
import java.util.UUID

/** Stable-identity camera rail. Only attached RecyclerView holders request a bitmap. */
internal class WorkspaceHomeAdapter(
    private val activity: ProductActivity,
    private val loader: WorkspaceThumbnailLoader,
    private val enabled: () -> Boolean,
    private val onPageClick: (StoredPage) -> Unit,
    private val onSelectionChanged: (StoredPage, Boolean) -> Unit,
    private val onLongClick: () -> Unit,
) : ListAdapter<WorkspaceHomeAdapter.Item, WorkspaceHomeAdapter.Holder>(DIFF) {
    internal data class Item(val page: StoredPage, val index: Int, val active: Boolean)

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = stableIdFor(getItem(position).page.pageId)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(activity).also { holder ->
        holder.thumbnail.layoutParams = LinearLayout.LayoutParams(activity.dpForThumbnail(56), activity.dpForThumbnail(64))
        holder.selection.layoutParams = LinearLayout.LayoutParams(activity.dpForThumbnail(56), activity.dpForThumbnail(48))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = bind(holder, getItem(position))

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
        holder.boundItem = item
        holder.thumbnail.tag = HOME_THUMBNAIL_TAG_PREFIX + item.page.pageId.value
        holder.thumbnail.contentDescription = activity.getString(
            if (item.active) R.string.active_page else R.string.page_image_description,
            item.index + 1,
        )
        holder.thumbnail.background = GradientDrawable().apply {
            setColor(0xff303030.toInt())
            setStroke(activity.dpForThumbnail(if (item.active) 3 else 1), if (item.active) Color.WHITE else 0xff777777.toInt())
        }
        holder.thumbnail.setOnClickListener { if (enabled()) onPageClick(item.page) }
        holder.thumbnail.setOnLongClickListener {
            if (enabled()) onLongClick()
            true
        }

        holder.selection.tag = HOME_SELECTION_TAG_PREFIX + item.page.pageId.value
        holder.selection.contentDescription = activity.getString(R.string.include_page_in_save, item.index + 1)
        holder.selection.minimumWidth = activity.dpForThumbnail(48)
        holder.selection.minimumHeight = activity.dpForThumbnail(48)
        holder.selection.isEnabled = enabled()
        holder.selection.setOnCheckedChangeListener(null)
        holder.selection.isChecked = item.page.selected
        var restoringSelection = false
        holder.selection.setOnCheckedChangeListener { checkbox, checked ->
            if (restoringSelection) return@setOnCheckedChangeListener
            if (!enabled()) {
                restoringSelection = true
                checkbox.isChecked = item.page.selected
                restoringSelection = false
                return@setOnCheckedChangeListener
            }
            onSelectionChanged(item.page, checked)
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
                targetWidthPx = activity.dpForThumbnail(56) * 2,
                targetHeightPx = activity.dpForThumbnail(64) * 2,
            ),
            file,
        )
    }

    internal fun submitPages(pages: List<StoredPage>, activePageId: PageId?) {
        submitList(pages.mapIndexed { index, page -> Item(page, index, page.pageId == activePageId) })
    }

    internal fun currentPages(): List<StoredPage> = currentList.map { it.page }

    /** Rebind attached checkboxes after the workspace busy state changes without a page diff. */
    internal fun refreshBoundItems() {
        notifyDataSetChanged()
    }

    internal class Holder(activity: ProductActivity) : RecyclerView.ViewHolder(
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
            (itemView as LinearLayout).apply {
                addView(thumbnail)
                addView(selection)
                layoutParams = RecyclerView.LayoutParams(activity.dpForThumbnail(68), ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }
    }

    companion object {
        const val HOME_THUMBNAIL_TAG_PREFIX = "docscan-home-page:"
        const val HOME_SELECTION_TAG_PREFIX = "docscan-home-selection:"

        private val DIFF = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(oldItem: Item, newItem: Item): Boolean = oldItem.page.pageId == newItem.page.pageId
            override fun areContentsTheSame(oldItem: Item, newItem: Item): Boolean = oldItem == newItem
        }

        internal fun stableIdFor(pageId: PageId): Long = runCatching {
            UUID.fromString(pageId.value).mostSignificantBits
        }.getOrElse { pageId.value.hashCode().toLong() }
    }
}

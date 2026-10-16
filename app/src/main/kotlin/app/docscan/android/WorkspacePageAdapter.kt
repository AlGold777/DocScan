package app.docscan.android

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.docscan.core.store.StoredPage
import app.docscan.core.store.WorkspaceProjection

/** Lazily creates only attached detailed workspace rows and preserves Page identity across updates. */
internal class WorkspacePageAdapter(
    private val loader: WorkspaceThumbnailLoader,
    private val createRow: (WorkspaceProjection, Int) -> View,
    private val bindRowThumbnail: (View, WorkspaceProjection, Int) -> Unit,
) : ListAdapter<WorkspacePageAdapter.Item, WorkspacePageAdapter.Holder>(DIFF) {
    internal data class Item(
        val projection: WorkspaceProjection,
        val page: StoredPage,
        val index: Int,
    ) {
        val active: Boolean get() = projection.activePageId == page.pageId
    }

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = WorkspaceHomeAdapter.stableIdFor(getItem(position).page.pageId)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(FrameLayout(parent.context))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        clearThumbnails(holder.container)
        holder.item = item
        holder.container.removeAllViews()
        holder.container.addView(createRow(item.projection, item.index), ViewGroup.LayoutParams(-1, -2))
        if (holder.itemView.isAttachedToWindow) {
            bindRowThumbnail(holder.itemView, item.projection, item.index)
        }
    }

    override fun onViewAttachedToWindow(holder: Holder) {
        super.onViewAttachedToWindow(holder)
        holder.item?.let { bindRowThumbnail(holder.itemView, it.projection, it.index) }
    }

    override fun onViewDetachedFromWindow(holder: Holder) {
        clearThumbnails(holder.itemView)
        super.onViewDetachedFromWindow(holder)
    }

    override fun onViewRecycled(holder: Holder) {
        clearThumbnails(holder.itemView)
        holder.container.removeAllViews()
        holder.item = null
        super.onViewRecycled(holder)
    }

    fun submitProjection(projection: WorkspaceProjection) {
        submitList(projection.pages.mapIndexed { index, page -> Item(projection, page, index) })
    }

    /** Recreate attached command rows after a transient workspace busy-state transition. */
    fun refreshBoundItems() {
        notifyDataSetChanged()
    }

    private fun clearThumbnails(view: View) {
        if (view is ImageView && view.tag is String && (view.tag as String).startsWith(ProductActivity.WORKSPACE_PAGE_THUMBNAIL_TAG_PREFIX)) {
            loader.unbind(view)
        } else if (view is ViewGroup) {
            for (index in 0 until view.childCount) clearThumbnails(view.getChildAt(index))
        }
    }

    internal class Holder(val container: FrameLayout) : RecyclerView.ViewHolder(container) {
        var item: Item? = null
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(oldItem: Item, newItem: Item): Boolean = oldItem.page.pageId == newItem.page.pageId
            override fun areContentsTheSame(oldItem: Item, newItem: Item): Boolean =
                oldItem.page == newItem.page && oldItem.index == newItem.index && oldItem.active == newItem.active &&
                    oldItem.projection.orderRevision == newItem.projection.orderRevision
        }
    }
}

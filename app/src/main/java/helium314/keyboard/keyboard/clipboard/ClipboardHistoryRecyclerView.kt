// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.latin.ClipboardHistoryManager
import androidx.core.view.isVisible
import androidx.core.view.isInvisible

class ClipboardHistoryRecyclerView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    var placeholderView: View? = null
    val historyManager: ClipboardHistoryManager? get() = (adapter as? ClipboardAdapter?)?.clipboardHistoryManager
    @Suppress("unused")
    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
        override fun onMove(recyclerView: RecyclerView, viewHolder: ViewHolder, target: ViewHolder) = false
        override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: ViewHolder): Int {
            // Murmur: only recent clips swipe away; headers, the pinned row and pinned clips stay
            val entry = (adapter as? ClipboardAdapter)?.entryAt(viewHolder.absoluteAdapterPosition)
            if (entry == null || entry.isPinned) return 0
            return super.getSwipeDirs(recyclerView, viewHolder)
        }
        override fun onSwiped(viewHolder: ViewHolder, dir: Int) {
            val a = adapter as? ClipboardAdapter ?: return
            val entry = a.entryAt(viewHolder.absoluteAdapterPosition) ?: return
            val m = historyManager ?: return
            val index = (0 until m.getHistorySize()).firstOrNull { m.getHistoryEntry(it)?.id == entry.id } ?: return
            m.removeEntry(index)
            a.refresh()
        }
    }).attachToRecyclerView(this)

    private val adapterDataObserver: AdapterDataObserver = object : AdapterDataObserver() {
        override fun onChanged() {
            checkAdapterContentChange()
        }

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
            checkAdapterContentChange()
        }

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            checkAdapterContentChange()
        }

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            checkAdapterContentChange()
        }
    }

    private fun checkAdapterContentChange() {
        if (placeholderView == null) return
        val adapterIsEmpty = adapter == null || adapter?.itemCount == 0
        if (isVisible && adapterIsEmpty) {
            placeholderView!!.visibility = VISIBLE
            visibility = INVISIBLE
        } else if (isInvisible && !adapterIsEmpty) {
            placeholderView!!.visibility = INVISIBLE
            visibility = VISIBLE
        }
    }

    override fun setAdapter(adapter: Adapter<*>?) {
        this.adapter?.unregisterAdapterDataObserver(adapterDataObserver)
        super.setAdapter(adapter)
        checkAdapterContentChange()
        adapter?.registerAdapterDataObserver(adapterDataObserver)
    }

}

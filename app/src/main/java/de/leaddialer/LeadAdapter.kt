package de.leaddialer

import android.annotation.SuppressLint
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import de.leaddialer.databinding.ItemLeadBinding
import java.util.Collections

class LeadAdapter(
    private val onClick: (Lead) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
) : RecyclerView.Adapter<LeadAdapter.VH>() {

    class VH(val b: ItemLeadBinding) : RecyclerView.ViewHolder(b.root)

    /** Mutable so drag and swipe can change it in place before the database catches up. */
    val items: MutableList<Lead> = ArrayList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(leads: List<Lead>) {
        items.clear()
        items.addAll(leads)
        notifyDataSetChanged()
    }

    fun move(from: Int, to: Int) {
        if (from == to) return
        if (from < to) for (i in from until to) Collections.swap(items, i, i + 1)
        else for (i in from downTo to + 1) Collections.swap(items, i, i - 1)
        notifyItemMoved(from, to)
    }

    fun removeAt(position: Int): Lead {
        val lead = items.removeAt(position)
        notifyItemRemoved(position)
        return lead
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemLeadBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(h: VH, position: Int) {
        val l = items[position]
        h.b.name.text = l.name.ifBlank { l.phone }
        val tries = if (l.attempts > 0) " · ${l.attempts}× angerufen" else ""
        h.b.details.text = listOf(l.phone, l.company).filter { it.isNotBlank() }.joinToString(" · ") + tries
        h.b.status.text = l.status.label
        h.b.status.background = GradientDrawable().apply {
            setColor(l.status.color)
            cornerRadius = 40f
        }
        h.b.root.setOnClickListener { onClick(items[h.bindingAdapterPosition]) }
        // The handle starts a drag on touch, without the long-press delay.
        h.b.dragHandle.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(h)
            false
        }
    }
}

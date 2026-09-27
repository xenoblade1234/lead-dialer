package de.leaddialer

import android.annotation.SuppressLint
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import de.leaddialer.databinding.ItemLeadBinding

class LeadAdapter(private val onClick: (Lead) -> Unit) : RecyclerView.Adapter<LeadAdapter.VH>() {

    class VH(val b: ItemLeadBinding) : RecyclerView.ViewHolder(b.root)

    var items: List<Lead> = emptyList()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemLeadBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

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
        h.b.root.setOnClickListener { onClick(l) }
    }
}

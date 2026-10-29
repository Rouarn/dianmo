package com.woyuxiaji.dianmo.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.woyuxiaji.dianmo.databinding.ItemProcessBinding
import com.woyuxiaji.dianmo.model.ProcessInfo

class ProcessAdapter(
    private val onClick: (ProcessInfo) -> Unit,
) : RecyclerView.Adapter<ProcessAdapter.VH>() {

    private var items: List<ProcessInfo> = emptyList()

    val size: Int
        get() = items.size

    class VH(val binding: ItemProcessBinding) : RecyclerView.ViewHolder(binding.root)

    fun submit(list: List<ProcessInfo>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemProcessBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        val b = holder.binding

        if (p.icon != null) {
            b.ivIcon.setImageDrawable(p.icon)
        } else {
            b.ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
        }

        b.tvProcessName.text = if (p.isSubProcess) {
            "${p.displayName}  (${p.rawName.substringAfter(':')})"
        } else {
            p.displayName
        }

        b.tvPackage.text = p.rawName
        b.tvPid.text = "pid ${p.pid}"
        b.root.setOnClickListener { onClick(p) }
    }
}

package com.woyuxiaji.dianmo.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.woyuxiaji.dianmo.databinding.ItemHitBinding
import com.woyuxiaji.dianmo.model.ScanHit

class HitAdapter(
    private val onWrite: (ScanHit) -> Unit,
    private val onCheckedChanged: (() -> Unit)? = null,
) : RecyclerView.Adapter<HitAdapter.VH>() {

    private val items = ArrayList<ScanHit>()
    private val checked = HashSet<Long>()

    class VH(val binding: ItemHitBinding) : RecyclerView.ViewHolder(binding.root)

    fun submit(list: List<ScanHit>) {
        items.clear()
        items.addAll(list)
        // 清理已经不在结果中的勾选
        val addresses = list.map { it.address }.toHashSet()
        checked.retainAll(addresses)
        notifyDataSetChanged()
    }

    fun currentItems(): List<ScanHit> = items.toList()

    fun checkedItems(): List<ScanHit> = items.filter { checked.contains(it.address) }

    fun checkedCount(): Int = checked.size

    fun isAllChecked(): Boolean = items.isNotEmpty() && checked.size == items.size

    fun setAllChecked(value: Boolean) {
        checked.clear()
        if (value) items.forEach { checked.add(it.address) }
        notifyDataSetChanged()
        onCheckedChanged?.invoke()
    }

    fun clearChecked() {
        checked.clear()
        notifyDataSetChanged()
        onCheckedChanged?.invoke()
    }

    fun updateValue(index: Int, hit: ScanHit) {
        if (index < 0 || index >= items.size) return
        items[index] = hit
        notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemHitBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val hit = items[position]
        val b = holder.binding

        b.tvAddress.text = "0x${hit.address.toString(16)}"
        b.tvValue.text = "= ${hit.value}"
        b.btnWrite.setOnClickListener { onWrite(hit) }

        b.cbHit.setOnCheckedChangeListener(null)
        b.cbHit.isChecked = checked.contains(hit.address)
        b.cbHit.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) checked.add(hit.address) else checked.remove(hit.address)
            onCheckedChanged?.invoke()
        }
    }
}

package com.woyuxiaji.dianmo.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.woyuxiaji.dianmo.core.SavedStore
import com.woyuxiaji.dianmo.databinding.ItemSavedBinding

class SavedAdapter(
    private val onWrite: (SavedStore.Item) -> Unit,
    private val onChecked: () -> Unit,
) : RecyclerView.Adapter<SavedAdapter.VH>() {

    private val items = ArrayList<SavedStore.Item>()
    private val checked = HashSet<String>()

    class VH(val binding: ItemSavedBinding) : RecyclerView.ViewHolder(binding.root)

    private fun key(item: SavedStore.Item) = "${item.pkg}|${item.type}|${item.addr}"

    fun submit(list: List<SavedStore.Item>) {
        items.clear()
        items.addAll(list)
        val keys = list.map { key(it) }.toHashSet()
        checked.retainAll(keys)
        notifyDataSetChanged()
    }

    fun items(): List<SavedStore.Item> = items.toList()

    fun checkedItems(): List<SavedStore.Item> = items.filter { checked.contains(key(it)) }

    fun checkedCount(): Int = checked.size

    fun isAllChecked(): Boolean = items.isNotEmpty() && checked.size == items.size

    fun setAllChecked(value: Boolean) {
        checked.clear()
        if (value) items.forEach { checked.add(key(it)) }
        notifyDataSetChanged()
        onChecked()
    }

    fun clearChecked() {
        checked.clear()
        notifyDataSetChanged()
        onChecked()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSavedBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val b = holder.binding
        b.tvSavedName.text = item.note.ifEmpty { "未命名" }
        b.tvSavedAddr.text = "0x${item.addr.toString(16)}  [${item.type}]"
        b.tvSavedValue.text = "= ${item.value}"
        b.btnSavedWrite.setOnClickListener { onWrite(item) }

        b.cbSaved.setOnCheckedChangeListener(null)
        b.cbSaved.isChecked = checked.contains(key(item))
        b.cbSaved.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) checked.add(key(item)) else checked.remove(key(item))
            onChecked()
        }
    }
}

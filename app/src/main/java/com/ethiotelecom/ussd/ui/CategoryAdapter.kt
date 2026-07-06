package com.ethiotelecom.ussd.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ethiotelecom.ussd.databinding.ItemCategoryBinding
import com.ethiotelecom.ussd.model.UssdCategory

class CategoryAdapter(
    private val onClick: (UssdCategory) -> Unit
) : ListAdapter<UssdCategory, CategoryAdapter.VH>(DIFF) {

    private var selectedPos = -1

    inner class VH(val binding: ItemCategoryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: UssdCategory, isSelected: Boolean) {
            binding.tvCategoryIcon.text = item.icon
            binding.tvCategoryName.text = item.name
            binding.tvCodeCount.text    = "${item.codes.size}"
            binding.root.isSelected     = isSelected
            binding.root.setOnClickListener {
                val prev    = selectedPos
                selectedPos = adapterPosition
                notifyItemChanged(prev)
                notifyItemChanged(selectedPos)
                onClick(item)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(getItem(position), position == selectedPos)

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<UssdCategory>() {
            override fun areItemsTheSame(a: UssdCategory, b: UssdCategory) = a.name == b.name
            override fun areContentsTheSame(a: UssdCategory, b: UssdCategory) = a == b
        }
    }
}

package com.ethiotelecom.ussd.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ethiotelecom.ussd.R
import com.ethiotelecom.ussd.databinding.ItemUssdCodeBinding
import com.ethiotelecom.ussd.model.UssdCode
import com.ethiotelecom.ussd.utils.UssdDialer

class UssdCodeAdapter(
    private val onDial: (UssdCode) -> Unit,
    private val onPin:  (UssdCode) -> Unit,
    private val onInfo: (UssdCode) -> Unit
) : ListAdapter<UssdCode, UssdCodeAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemUssdCodeBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: UssdCode) {
            binding.tvCodeLabel.text    = item.label
            binding.tvCodeValue.text    = UssdDialer.normalizeForDisplay(item.code)
            binding.tvCodeCategory.text = item.subcategory ?: item.category
            binding.btnPin.setImageResource(
                if (item.isPinned) R.drawable.ic_pin_filled else R.drawable.ic_pin_outline
            )
            binding.tvInputRequired.visibility =
                if (item.requiresInput) View.VISIBLE else View.GONE

            binding.btnDial.setOnClickListener { onDial(item) }
            binding.btnPin.setOnClickListener  {
                onPin(item)
                notifyItemChanged(adapterPosition)
            }
            binding.root.setOnLongClickListener { onInfo(item); true }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemUssdCodeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<UssdCode>() {
            override fun areItemsTheSame(a: UssdCode, b: UssdCode) = a.id == b.id
            override fun areContentsTheSame(a: UssdCode, b: UssdCode) = a == b
        }
    }
}

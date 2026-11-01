package com.romaster.launcher.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.romaster.launcher.R
import com.romaster.launcher.data.AppInfo

class AppIconAdapter(
    private val onClick: (AppInfo) -> Unit,
    private val onLongClick: ((AppInfo, View) -> Unit)? = null
) : ListAdapter<AppInfo, AppIconAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_icon, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconImage: ImageView = itemView.findViewById(R.id.iconImage)
        private val iconLabel: TextView = itemView.findViewById(R.id.iconLabel)

        fun bind(app: AppInfo) {
            iconImage.setImageDrawable(app.icon)
            iconLabel.text = app.label

            itemView.setOnClickListener { onClick(app) }
            itemView.setOnLongClickListener {
                onLongClick?.invoke(app, itemView)
                true
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(old: AppInfo, new: AppInfo) = old.uniqueId == new.uniqueId
        override fun areContentsTheSame(old: AppInfo, new: AppInfo) =
            old.label == new.label && old.icon == new.icon
    }
}

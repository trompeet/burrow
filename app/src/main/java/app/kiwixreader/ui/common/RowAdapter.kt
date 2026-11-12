package app.kiwixreader.ui.common

import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.kiwixreader.databinding.ItemHeaderBinding
import app.kiwixreader.databinding.ItemRowBinding
import com.google.android.material.color.MaterialColors

/** One flat list row (icon, title, subtitle, detail, status, progress) or a section header. */
data class Row(
    val key: String,
    val title: CharSequence,
    val subtitle: CharSequence? = null,
    val detail: CharSequence? = null,
    @DrawableRes val icon: Int = 0,
    val iconBytes: ByteArray? = null,
    val status: CharSequence? = null,
    @AttrRes val statusColor: Int = 0,
    /** 0..100, or -1 for indeterminate; null hides the bar. */
    val progress: Int? = null,
    val indent: Int = 0,
    val isHeader: Boolean = false,
    val payload: Any? = null,
) {
    companion object {
        fun header(key: String, title: CharSequence) = Row(key = "header:$key", title = title, isHeader = true)
    }
}

class RowAdapter(
    private val onClick: (Row) -> Unit,
    private val onLongClick: ((Row) -> Unit)? = null,
) : ListAdapter<Row, RecyclerView.ViewHolder>(Diff) {

    override fun getItemViewType(position: Int) = if (getItem(position).isHeader) HEADER else ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == HEADER) {
            HeaderHolder(ItemHeaderBinding.inflate(inflater, parent, false))
        } else {
            ItemHolder(ItemRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = getItem(position)
        when (holder) {
            is HeaderHolder -> holder.binding.title.text = row.title
            is ItemHolder -> holder.bind(row)
        }
    }

    private class HeaderHolder(val binding: ItemHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    private inner class ItemHolder(val binding: ItemRowBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row) = with(binding) {
            val bitmap = row.iconBytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
            when {
                bitmap != null -> {
                    icon.imageTintList = null
                    icon.setImageBitmap(bitmap)
                }
                row.icon != 0 -> {
                    icon.imageTintList = ColorStateList.valueOf(
                        MaterialColors.getColor(icon, android.R.attr.textColorSecondary),
                    )
                    icon.setImageResource(row.icon)
                }
                else -> icon.setImageDrawable(null)
            }
            icon.isVisible = bitmap != null || row.icon != 0
            root.setPaddingRelative(
                root.paddingEnd + row.indent * root.resources.getDimensionPixelSize(app.kiwixreader.R.dimen.indent_step),
                root.paddingTop,
                root.paddingEnd,
                root.paddingBottom,
            )
            title.text = row.title
            subtitle.text = row.subtitle
            subtitle.isVisible = !row.subtitle.isNullOrEmpty()
            detail.text = row.detail
            detail.isVisible = !row.detail.isNullOrEmpty()
            status.text = row.status
            status.isVisible = !row.status.isNullOrEmpty()
            if (row.statusColor != 0) status.setTextColor(MaterialColors.getColor(status, row.statusColor))
            progress.isVisible = row.progress != null
            row.progress?.let {
                progress.isIndeterminate = it < 0
                if (it >= 0) progress.setProgressCompat(it, false)
            }
            root.setOnClickListener { onClick(row) }
            if (onLongClick != null) {
                root.setOnLongClickListener {
                    onLongClick.invoke(row)
                    true
                }
            } else {
                root.setOnLongClickListener(null)
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(oldItem: Row, newItem: Row) = oldItem.key == newItem.key
        override fun areContentsTheSame(oldItem: Row, newItem: Row) =
            oldItem.copy(iconBytes = null, payload = null) == newItem.copy(iconBytes = null, payload = null) &&
                oldItem.iconBytes.contentEquals(newItem.iconBytes)
    }

    private companion object {
        const val HEADER = 0
        const val ITEM = 1
    }
}

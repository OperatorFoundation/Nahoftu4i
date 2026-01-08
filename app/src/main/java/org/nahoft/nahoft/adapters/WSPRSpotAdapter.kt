package org.nahoft.nahoft.adapters

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.nahoft.nahoft.R
import org.nahoft.nahoft.databinding.ItemWsprSpotBinding
import org.nahoft.nahoft.models.GroupPosition
import org.nahoft.nahoft.models.NahoftSpotStatus
import org.nahoft.nahoft.models.WSPRSpotItem
import java.text.SimpleDateFormat
import java.util.*

/**
 * Adapter for displaying WSPR spots in a RecyclerView.
 */
class WSPRSpotAdapter : ListAdapter<WSPRSpotItem, WSPRSpotAdapter.SpotViewHolder>(SpotDiffCallback()) {

    // Time formatter for displaying decode timestamp
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SpotViewHolder
    {
        val binding = ItemWsprSpotBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )

        return SpotViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SpotViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SpotViewHolder(private val binding: ItemWsprSpotBinding) : RecyclerView.ViewHolder(binding.root)
    {
        /**
         * Binds a WSPRSpotItem to the view.
         */
        fun bind(spot: WSPRSpotItem) {
            val context = binding.root.context

            // Core WSPR data (always displayed)
            binding.tvCallsign.text = spot.callsign
            binding.tvGrid.text = spot.gridSquare
            binding.tvPower.text = "${spot.powerDbm}dBm"
            binding.tvSnr.text = "%+.0fdB".format(spot.snrDb)
            binding.tvTime.text = timeFormat.format(Date(spot.timestamp))

            // Nahoft-specific elements
            if (spot.isNahoftSpot)
            {
                // Determine accent color based on status
                val accentColorRes = when (spot.nahoftStatus)
                {
                    is NahoftSpotStatus.Failed -> R.color.madderLake  // Red/orange for failures
                    else -> R.color.caribbeanGreen                    // Green for pending/decrypted
                }

                val accentColor = ContextCompat.getColor(context, accentColorRes)

                // Show accent bar with appropriate color
                binding.viewAccentBar.visibility = View.VISIBLE
                binding.viewAccentBar.setBackgroundColor(accentColor)

                // Show part number annotation
                binding.tvPartNumber.visibility = View.VISIBLE
                binding.tvPartNumber.text = spot.partNumberDisplay
                binding.tvPartNumber.setTextColor(accentColor)

                // Show status annotation with appropriate color
                binding.tvStatus.visibility = View.VISIBLE
                binding.tvStatus.text = spot.statusDisplay
                binding.tvStatus.setTextColor(
                    when (spot.nahoftStatus) {
                        is NahoftSpotStatus.Decrypted -> ContextCompat.getColor(context, R.color.caribbeanGreen)
                        is NahoftSpotStatus.Failed -> ContextCompat.getColor(context, R.color.madderLake)
                        else -> ContextCompat.getColor(context, R.color.coolGrey)
                    }
                )

                // Draw group connector based on position
                drawGroupConnector(spot.groupPosition, accentColor)
            }
            else
            {
                // Regular WSPR spot - hide Nahoft elements
                binding.viewAccentBar.visibility = View.GONE
                binding.tvPartNumber.visibility = View.GONE
                binding.tvStatus.visibility = View.GONE
                binding.viewGroupConnector.background = null
            }
        }

        /**
         * Draws the appropriate connector line/bracket based on group position.
         *
         * Visual representation:
         * - FIRST:  ┌  (top corner)
         * - MIDDLE: │  (vertical line)
         * - LAST:   └  (bottom corner)
         * - SINGLE: [  (left bracket, might grow)
         * - NONE:      (no connector)
         *
         * @param position The spot's position within its group
         * @param color The color to use for the connector (matches accent bar)
         */
        private fun drawGroupConnector(position: GroupPosition, color: Int)
        {
            val context = binding.root.context
            val strokeWidth = 2f.dpToPx(context)

            val drawable = when (position) {
                GroupPosition.NONE -> null

                GroupPosition.FIRST -> {
                    // ┌ shape: top-right corner going down
                    createCornerDrawable(color, strokeWidth, isTop = true)
                }

                GroupPosition.MIDDLE -> {
                    // │ shape: vertical line on the right side
                    createVerticalLineDrawable(color, strokeWidth)
                }

                GroupPosition.LAST -> {
                    // └ shape: bottom-right corner going up
                    createCornerDrawable(color, strokeWidth, isTop = false)
                }

                GroupPosition.SINGLE -> {
                    // [ shape: bracket that might grow (show as top corner for now)
                    createCornerDrawable(color, strokeWidth, isTop = true)
                }
            }

            binding.viewGroupConnector.background = drawable
        }

        /**
         * Creates a corner bracket drawable (┌ or └).
         */
        private fun createCornerDrawable(color: Int, strokeWidth: Int, isTop: Boolean): LayerDrawable {
            val context = binding.root.context

            // Horizontal line at top or bottom
            val horizontal = GradientDrawable().apply {
                setColor(color)
            }

            // Vertical line on the right side
            val vertical = GradientDrawable().apply {
                setColor(color)
            }

            return LayerDrawable(arrayOf(horizontal, vertical)).apply {
                val viewWidth = 16f.dpToPx(context)
                val halfHeight = 12f.dpToPx(context) // Approximate half item height

                // Horizontal line: positioned at top or bottom
                setLayerInset(0,
                    (viewWidth - strokeWidth * 4).toInt(), // left inset (leave space on left)
                    if (isTop) 0 else (halfHeight * 2 - strokeWidth).toInt(), // top
                    0, // right
                    if (isTop) (halfHeight * 2 - strokeWidth).toInt() else 0  // bottom
                )

                // Vertical line: on right edge, from corner to center (or beyond)
                setLayerInset(1,
                    (viewWidth - strokeWidth).toInt(), // left (positions on right edge)
                    if (isTop) 0 else 0, // top
                    0, // right
                    if (isTop) 0 else 0  // bottom - full height to connect
                )
            }
        }

        /**
         * Creates a vertical line drawable (│).
         */
        private fun createVerticalLineDrawable(color: Int, strokeWidth: Int): GradientDrawable
        {
            val context = binding.root.context
            val viewWidth = 16f.dpToPx(context)

            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(color)
                setSize(strokeWidth.toInt(), 1)
            }
        }

        /**
         * Converts dp to pixels.
         */
        private fun Float.dpToPx(context: android.content.Context): Int
        {
            return (this * context.resources.displayMetrics.density).toInt()
        }
    }

    class SpotDiffCallback : DiffUtil.ItemCallback<WSPRSpotItem>()
    {

        override fun areItemsTheSame(oldItem: WSPRSpotItem, newItem: WSPRSpotItem): Boolean
        {
            // Items are the same if they have matching callsign and timestamp
            return oldItem.callsign == newItem.callsign &&
                    oldItem.timestamp == newItem.timestamp
        }

        override fun areContentsTheSame(oldItem: WSPRSpotItem, newItem: WSPRSpotItem): Boolean
        {
            return oldItem == newItem
        }
    }
}
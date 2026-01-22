package org.nahoft.nahoft.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.nahoft.nahoft.R
import org.nahoft.nahoft.databinding.ItemWsprSpotBinding
import org.nahoft.nahoft.models.NahoftSpotStatus
import org.nahoft.nahoft.models.WSPRSpotItem
import java.text.SimpleDateFormat
import java.util.*

/**
 * Adapter for displaying WSPR spots in a RecyclerView.
 */
class WSPRSpotAdapter : ListAdapter<WSPRSpotItem, WSPRSpotAdapter.SpotViewHolder>(SpotDiffCallback())
{
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

    override fun onBindViewHolder(holder: SpotViewHolder, position: Int)
    {
        holder.bind(getItem(position))
    }

    inner class SpotViewHolder(
        private val binding: ItemWsprSpotBinding
    ) : RecyclerView.ViewHolder(binding.root)
    {
        fun bind(spot: WSPRSpotItem)
        {
            val context = binding.root.context

            // Row 1: Callsign and Time
            binding.tvCallsign.text = spot.callsign
            binding.tvTime.text = timeFormat.format(Date(spot.timestamp))

            // Row 2: Grid, Power, SNR
            binding.tvGrid.text = spot.gridSquare
            binding.tvPower.text = "${spot.powerDbm} dBm"
            binding.tvSnr.text = "%+.0f dB".format(spot.snrDb)

            // Set background and Nahoft-specific elements
            when {
                spot.nahoftStatus is NahoftSpotStatus.Failed -> {
                    // Failed Nahoft spot - red accent
                    binding.spotCard.setBackgroundResource(R.drawable.spot_card_background_failed)
                    showNahoftStatus(spot, R.color.madderLake)
                }
                spot.isNahoftSpot -> {
                    // Successful/pending Nahoft spot - green accent
                    binding.spotCard.setBackgroundResource(R.drawable.spot_card_background_nahoft)
                    showNahoftStatus(spot, R.color.caribbeanGreen)
                }
                else -> {
                    // Regular WSPR spot - no accent
                    binding.spotCard.setBackgroundResource(R.drawable.spot_card_background)
                    binding.rowNahoftStatus.visibility = View.GONE
                }
            }
        }

        private fun showNahoftStatus(spot: WSPRSpotItem, accentColorRes: Int)
        {
            val context = binding.root.context
            val accentColor = ContextCompat.getColor(context, accentColorRes)

            binding.rowNahoftStatus.visibility = View.VISIBLE

            // Part number
            binding.tvPartNumber.text = formatPartNumber(spot)
            binding.tvPartNumber.setTextColor(accentColor)

            // Status text
            binding.tvStatus.text = spot.statusDisplay
            binding.tvStatus.setTextColor(
                when (spot.nahoftStatus)
                {
                    is NahoftSpotStatus.Decrypted -> ContextCompat.getColor(context, R.color.caribbeanGreen)
                    is NahoftSpotStatus.Failed -> ContextCompat.getColor(context, R.color.madderLake)
                    else -> ContextCompat.getColor(context, android.R.color.darker_gray)
                }
            )
        }

        private fun formatPartNumber(spot: WSPRSpotItem): String
        {
            return when (val status = spot.nahoftStatus)
            {
                is NahoftSpotStatus.Pending -> "Part ${status.partNumber}"
                is NahoftSpotStatus.Decrypted -> "Part ${status.partNumber} of ${status.totalParts}"
                is NahoftSpotStatus.Failed -> "Part ${status.partNumber}"
                else -> ""
            }
        }
    }

    class SpotDiffCallback : DiffUtil.ItemCallback<WSPRSpotItem>()
    {
        override fun areItemsTheSame(oldItem: WSPRSpotItem, newItem: WSPRSpotItem): Boolean
        {
            return oldItem.callsign == newItem.callsign &&
                    oldItem.timestamp == newItem.timestamp
        }

        override fun areContentsTheSame(oldItem: WSPRSpotItem, newItem: WSPRSpotItem): Boolean
        {
            return oldItem == newItem
        }
    }
}
package com.github.pwittchen.reactivebeacons.kotlinapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.github.pwittchen.reactivebeacons.library.rx2.Beacon

class BeaconRecyclerAdapter(private val beaconList: List<Beacon>,
                            private val onItemClick: ((FirebaseBeacon) -> Unit)) :
        RecyclerView.Adapter<BeaconRecyclerAdapter.ViewHolder>() {
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val beaconNameText = itemView.findViewById<TextView>(R.id.tvBeaconName)
        private val beaconMacText = itemView.findViewById<TextView>(R.id.tvBeaconMac)
        private val beaconDistanceText = itemView.findViewById<TextView>(R.id.tvBeaconDistance)
        private val beaconProximityText = itemView.findViewById<TextView>(R.id.tvBeaconProximity)
        private val beaconRssiText = itemView.findViewById<TextView>(R.id.beaconRssi)
        val pushToDbButton = itemView.findViewById<ImageButton>(R.id.pushToDbButton)

        fun bind(beacon: Beacon){
            beacon.apply {
                beaconNameText.text = device.name
                beaconMacText.text = device.address
                beaconDistanceText.text = itemView.context.getString(R.string.distance, distance)
                beaconProximityText.text =
                        itemView.context.getString(R.string.proximity, proximity.description.toUpperCase())
                beaconRssiText.text = itemView.context.getString(R.string.rssi, rssi)

            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.layout_single_beacon_item, parent, false)

        return ViewHolder(view)
    }

    override fun getItemCount(): Int {
        return beaconList?.size
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val singleBeacon = beaconList[position]
        holder.bind(singleBeacon)
        holder.pushToDbButton.setOnClickListener {
            android.util.Log.e("Adapter", "single beacon is: $singleBeacon")

            onItemClick.invoke(FirebaseBeacon.fromBeacon(singleBeacon))
        }
    }
}
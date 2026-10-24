package com.regaim.app

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "אבני דרך": the "first time" moments of the kids, on a timeline (oldest first, like a baby book). */
class MilestonesActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_milestones)
        list = findViewById(R.id.milestoneList)
        empty = findViewById(R.id.milestoneEmpty)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        Nav.setup(this, Nav.Tab.MEMORIES)
    }

    override fun onResume() {
        super.onResume()
        val items = MemoryStore.kept(this).filter { it.milestone.isNotEmpty() }.sortedBy { it.time }
        adapter.items = items
        adapter.notifyDataSetChanged()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        var items: List<Memory> = emptyList()

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val image: ImageView = v.findViewById(R.id.msImage)
            val title: TextView = v.findViewById(R.id.msTitle)
            val date: TextView = v.findViewById(R.id.msDate)
            val desc: TextView = v.findViewById(R.id.msDesc)
            val lineTop: View = v.findViewById(R.id.msLineTop)
            val lineBottom: View = v.findViewById(R.id.msLineBottom)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_milestone, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val m = items[position]
            val bmp = if (m.thumb.isNotEmpty()) BitmapFactory.decodeFile(m.thumb) else null
            if (bmp != null) h.image.setImageBitmap(bmp) else h.image.setImageResource(R.drawable.bubble_placeholder)
            h.title.text = m.milestone
            h.date.text = SimpleDateFormat("d.M.yyyy", Locale.US).format(Date(m.time)) +
                if (m.place.isNotEmpty()) " · ${m.place}" else ""
            h.desc.text = m.description
            h.desc.visibility = if (m.description.isEmpty()) View.GONE else View.VISIBLE
            h.lineTop.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE
            h.lineBottom.visibility = if (position == items.size - 1) View.INVISIBLE else View.VISIBLE
            h.itemView.setOnClickListener { MomentActivity.open(this@MilestonesActivity, m.id) }
        }
    }
}

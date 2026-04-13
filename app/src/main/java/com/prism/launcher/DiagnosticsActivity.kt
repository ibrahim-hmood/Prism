package com.prism.launcher

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class DiagnosticsActivity : PrismBaseActivity() {

    private lateinit var adapter: LogAdapter
    private lateinit var recyclerView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)

        recyclerView = findViewById(R.id.log_recycler)
        recyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        adapter = LogAdapter()
        recyclerView.adapter = adapter

        lifecycleScope.launch {
            PrismLogger.logFlow.collect { entry ->
                adapter.addEntry(entry)
                recyclerView.scrollToPosition(adapter.itemCount - 1)
            }
        }
    }

    class LogAdapter : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {
        private val logs = mutableListOf<PrismLogger.LogEntry>()

        fun addEntry(entry: PrismLogger.LogEntry) {
            logs.add(entry)
            notifyItemInserted(logs.size - 1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 10f
                setPadding(0, 2, 0, 2)
            }
            return LogViewHolder(tv)
        }


        override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
            val entry = logs[position]
            val color = when (entry.level) {
                PrismLogger.Level.ERROR -> Color.parseColor("#EF2929")   // Ubuntu Red
                PrismLogger.Level.WARN -> Color.parseColor("#FCE94F")    // Yellow
                PrismLogger.Level.SUCCESS -> Color.parseColor("#8AE234") // Green
                PrismLogger.Level.INFO -> Color.WHITE
            }
            
            // Build stylized Ubuntu-like prompt: prism@mesh:~$ message
            val timestamp = entry.timestamp.split(" ").last()
            
            val span = android.text.SpannableStringBuilder()
            
            // 1. [Time] (Grey)
            span.append("[$timestamp] ", android.text.style.ForegroundColorSpan(Color.GRAY), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            
            // 2. prism@mesh (Green)
            span.append("prism@mesh", android.text.style.ForegroundColorSpan(Color.parseColor("#8AE234")), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            
            // 3. : (White)
            span.append(":", android.text.style.ForegroundColorSpan(Color.WHITE), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            
            // 4. ~ (Blue)
            span.append("~", android.text.style.ForegroundColorSpan(Color.parseColor("#729FCF")), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            
            // 5. $ (White)
            span.append("$ ", android.text.style.ForegroundColorSpan(Color.WHITE), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            
            // 6. Message (Level Color)
            span.append(entry.message, android.text.style.ForegroundColorSpan(color), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            
            holder.textView.text = span
        }

        override fun getItemCount() = logs.size

        class LogViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
    }
}

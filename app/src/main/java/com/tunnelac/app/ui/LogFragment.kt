package com.tunnelac.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tunnelac.app.BuildConfig
import com.tunnelac.app.R
import com.tunnelac.app.data.db.AppDatabase
import com.tunnelac.app.export.CsvExporter
import kotlinx.coroutines.launch

class LogFragment : Fragment(R.layout.fragment_log) {

    private val adapter = LogAdapter()
    private var lastEvents: List<com.tunnelac.app.data.db.EventEntity> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rv = view.findViewById<RecyclerView>(R.id.rv_events)
        val tvEmpty = view.findViewById<TextView>(R.id.tv_log_empty)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        val eventDao = AppDatabase.getInstance(requireContext().applicationContext).eventDao()

        viewLifecycleOwner.lifecycleScope.launch {
            eventDao.observeAll().collect { events ->
                lastEvents = events
                adapter.submitList(events)
                tvEmpty.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
                rv.visibility = if (events.isEmpty()) View.GONE else View.VISIBLE
            }
        }

        view.findViewById<Button>(R.id.btn_export_csv).setOnClickListener {
            if (lastEvents.isEmpty()) {
                Toast.makeText(requireContext(), "내보낼 이벤트가 없습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val file = CsvExporter.export(requireContext(), lastEvents)
            val uri = FileProvider.getUriForFile(requireContext(), "${BuildConfig.APPLICATION_ID}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "CSV 내보내기"))
        }

        view.findViewById<Button>(R.id.btn_clear_log).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.dialog_clear_log_title)
                .setMessage(R.string.dialog_clear_log_message)
                .setPositiveButton(R.string.dialog_delete) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        eventDao.clearAll()
                        Toast.makeText(requireContext(), R.string.toast_log_cleared, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }
}

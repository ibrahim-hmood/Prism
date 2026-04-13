package com.prism.launcher.browser

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.PrismDialogFactory
import com.prism.launcher.R
import com.prism.launcher.PrismSettings
import com.prism.launcher.databinding.ActivityP2pDnsBinding
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class P2pDnsActivity : PrismBaseActivity() {

    private lateinit var binding: ActivityP2pDnsBinding
    private var isRemoteMode = false
    private var activeServer: PrismSettings.PrismServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityP2pDnsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.dnsToolbar.setNavigationIcon(R.drawable.ic_back_24)
        binding.dnsToolbar.setNavigationOnClickListener { finish() }

        binding.dnsList.layoutManager = LinearLayoutManager(this)
        refreshList()

        binding.addDnsBtn.setOnClickListener { showAddDialog() }
        binding.switchTargetBtn.setOnClickListener { toggleTarget() }
    }

    private fun refreshList() {
        val records = if (isRemoteMode) {
            // In a real implementation, we'd fetch these from the server.
            // For now, we show a 'Syncing...' state or the local cache of peer records.
            P2pDnsManager.getRecords() 
        } else {
            P2pDnsManager.getRecords()
        }

        binding.dnsList.adapter = DnsAdapter(records.toList()) { domain ->
            confirmDelete(domain)
        }
    }

    private fun toggleTarget() {
        if (!isRemoteMode) {
            val servers = PrismSettings.getPrismServers(this)
            if (servers.isEmpty()) {
                Toast.makeText(this, "No Prism Servers configured in settings", Toast.LENGTH_SHORT).show()
                return
            }
            activeServer = servers.find { it.isActive } ?: servers.first()
            isRemoteMode = true
            binding.targetText.text = "Target: ${activeServer?.name?.uppercase()}"
            binding.switchTargetBtn.text = "Switch to Local"
        } else {
            isRemoteMode = false
            activeServer = null
            binding.targetText.text = "Target: Local Device"
            binding.switchTargetBtn.text = "Connect to Server"
        }
        refreshList()
    }

    private fun showAddDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        val domainInput = EditText(this).apply { hint = "Domain (e.g. blog.p2p or google.com)" }
        val ipInput = EditText(this).apply { hint = "IP Address" }
        layout.addView(domainInput)
        layout.addView(ipInput)

        PrismDialogFactory.show(this, "Add P2P Mapping", "Enter the domain and target IP.", onPositive = {
            val domain = domainInput.text.toString().trim()
            val ip = ipInput.text.toString().trim()
            if (domain.isNotEmpty() && ip.isNotEmpty()) {
                if (isRemoteMode) sendRemoteCommand("ADD", domain, ip)
                else P2pDnsManager.updateRecord(this, domain, ip)
                refreshList()
            }
        }, customView = layout)
    }

    private fun confirmDelete(domain: String) {
        PrismDialogFactory.show(this, "Delete Mapping?", "Remove $domain from the P2P ledger?", onPositive = {
            if (isRemoteMode) sendRemoteCommand("DEL", domain, null)
            else P2pDnsManager.deleteRecord(this, domain)
            refreshList()
        })
    }

    private fun sendRemoteCommand(action: String, domain: String, ip: String?) {
        val server = activeServer ?: return
        thread {
            try {
                val socket = DatagramSocket()
                val payload = JSONObject().apply {
                    put("action", action)
                    put("domain", domain)
                    if (ip != null) put("ip", ip)
                }.toString().toByteArray()
                
                val packetData = ByteArray(6 + payload.size)
                "PRISM".toByteArray().copyInto(packetData)
                packetData[5] = 0x05 // DNS_WRITE_CMD
                payload.copyInto(packetData, 6)

                val packet = DatagramPacket(packetData, packetData.size, InetAddress.getByName(server.address), server.port)
                socket.send(packet)
                socket.close()
                
                runOnUiThread {
                    Toast.makeText(this, "Remote $action command sent to ${server.name}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Failed to reach server: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private inner class DnsAdapter(val items: List<Pair<String, P2pDnsManager.DnsRecord>>, val onDelete: (String) -> Unit) : RecyclerView.Adapter<DnsVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DnsVH {
            val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
            return DnsVH(view)
        }
        override fun onBindViewHolder(holder: DnsVH, position: Int) {
            val (domain, record) = items[position]
            holder.t1.text = domain
            holder.t1.setTextColor(if (record.isVerified) PrismSettings.getGlowColor(this@P2pDnsActivity) else 0xFFFFFFFF.toInt())
            holder.t2.text = "${record.ip} • ${if (record.isVerified) "Verified" else "Auto-Seeded"}"
            holder.view.setOnLongClickListener {
                onDelete(domain)
                true
            }
        }
        override fun getItemCount() = items.size
    }

    private class DnsVH(val view: View) : RecyclerView.ViewHolder(view) {
        val t1 = view.findViewById<TextView>(android.R.id.text1)
        val t2 = view.findViewById<TextView>(android.R.id.text2)
    }
}

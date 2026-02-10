package com.banknotify.telegram

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogAdapter(
    private var logs: List<LogEntry>,
    private val onResend: ((LogEntry) -> Unit)? = null
) : RecyclerView.Adapter<LogAdapter.ViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTimestamp: TextView = view.findViewById(R.id.tvTimestamp)
        val tvTransactionType: TextView = view.findViewById(R.id.tvTransactionType)
        val tvBankName: TextView = view.findViewById(R.id.tvBankName)
        val tvPaymentMethod: TextView = view.findViewById(R.id.tvPaymentMethod)
        val tvAmount: TextView = view.findViewById(R.id.tvAmount)
        val tvSender: TextView = view.findViewById(R.id.tvSender)
        val tvOriginal: TextView = view.findViewById(R.id.tvOriginal)
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        val tvDevice: TextView = view.findViewById(R.id.tvDevice)
        val tvSource: TextView = view.findViewById(R.id.tvSource)
        val tvTransactionStatus: TextView = view.findViewById(R.id.tvTransactionStatus)
        val tvIgnoreReason: TextView = view.findViewById(R.id.tvIgnoreReason)
        val tvTelegramStatus: TextView = view.findViewById(R.id.tvTelegramStatus)
        val btnResend: Button = view.findViewById(R.id.btnResend)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val log = logs[position]
        val ctx = holder.itemView.context

        holder.tvTimestamp.text = dateFormat.format(Date(log.timestamp))

        // 디바이스 번호
        val deviceText = if (log.deviceName.isNotBlank()) {
            "${log.deviceNumber}번-${log.deviceName}"
        } else if (log.deviceNumber > 0) {
            "${log.deviceNumber}번"
        } else ""
        holder.tvDevice.text = deviceText
        holder.tvDevice.visibility = if (deviceText.isNotBlank()) View.VISIBLE else View.GONE

        // 감지 방식 (SMS / 알림)
        val source = log.source
        if (source.isNotBlank() && source != "알림") {
            holder.tvSource.text = source
            val bgColor = if (source == "SMS") Color.parseColor("#00897B") else Color.parseColor("#5C6BC0")
            val bg = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = 8f
            }
            holder.tvSource.background = bg
            holder.tvSource.visibility = View.VISIBLE
        } else {
            holder.tvSource.visibility = View.GONE
        }

        // 입금/출금
        val isDeposit = log.transactionType == "입금"
        val isIgnored = log.transactionStatus == "무시"
        if (isIgnored) {
            holder.tvTransactionType.text = "무시됨"
            holder.tvTransactionType.setTextColor(Color.parseColor("#9E9E9E"))
        } else {
            val typeEmoji = if (isDeposit) "\uD83D\uDCB0" else "\uD83D\uDCB8"
            holder.tvTransactionType.text = "$typeEmoji ${log.transactionType}"
            holder.tvTransactionType.setTextColor(
                ctx.getColor(if (isDeposit) android.R.color.holo_blue_dark else android.R.color.holo_orange_dark)
            )
        }

        holder.tvBankName.text = log.bankName
        holder.tvPaymentMethod.text = log.paymentMethod
        holder.tvPaymentMethod.visibility = if (log.paymentMethod.isNotBlank()) View.VISIBLE else View.GONE

        holder.tvAmount.text = if (log.amount.isNotBlank()) log.amount else "-"
        holder.tvSender.text = if (log.senderName.isNotBlank()) log.senderName else "-"
        holder.tvOriginal.text = log.originalText

        // 거래 상태 (정상/실패/취소)
        when (log.transactionStatus) {
            "실패" -> {
                holder.tvTransactionStatus.text = "\u274C 실패"
                holder.tvTransactionStatus.setTextColor(Color.parseColor("#D32F2F"))
                holder.tvTransactionStatus.visibility = View.VISIBLE
            }
            "취소" -> {
                holder.tvTransactionStatus.text = "\uD83D\uDEAB 취소"
                holder.tvTransactionStatus.setTextColor(Color.parseColor("#F57C00"))
                holder.tvTransactionStatus.visibility = View.VISIBLE
            }
            "무시" -> {
                holder.tvTransactionStatus.text = "무시"
                holder.tvTransactionStatus.setTextColor(Color.parseColor("#9E9E9E"))
                holder.tvTransactionStatus.visibility = View.VISIBLE
            }
            else -> {
                holder.tvTransactionStatus.visibility = View.GONE
            }
        }

        // 무시 사유
        if (log.ignoreReason.isNotBlank()) {
            holder.tvIgnoreReason.text = "사유: ${log.ignoreReason}"
            holder.tvIgnoreReason.visibility = View.VISIBLE
        } else {
            holder.tvIgnoreReason.visibility = View.GONE
        }

        // 텔레그램 전송 상태
        when (log.telegramStatus) {
            "성공" -> {
                holder.tvTelegramStatus.text = "\u2705 전송완료"
                holder.tvTelegramStatus.setTextColor(ctx.getColor(android.R.color.holo_green_dark))
            }
            "실패" -> {
                holder.tvTelegramStatus.text = "\u274C 전송실패"
                holder.tvTelegramStatus.setTextColor(Color.parseColor("#D32F2F"))
            }
            "대기" -> {
                holder.tvTelegramStatus.text = "\u23F3 대기중"
                holder.tvTelegramStatus.setTextColor(Color.parseColor("#F57C00"))
            }
            else -> {
                holder.tvTelegramStatus.text = ""
            }
        }
        holder.tvTelegramStatus.visibility =
            if (log.telegramStatus.isNotBlank()) View.VISIBLE else View.GONE

        // 기존 tvStatus 숨김 (tvTelegramStatus로 대체)
        holder.tvStatus.visibility = View.GONE

        // 재전송 버튼
        if ((log.telegramStatus == "실패" || log.telegramStatus == "대기") &&
            log.transactionStatus != "무시" && onResend != null) {
            holder.btnResend.visibility = View.VISIBLE
            holder.btnResend.setOnClickListener { onResend.invoke(log) }
        } else {
            holder.btnResend.visibility = View.GONE
        }
    }

    override fun getItemCount() = logs.size

    fun updateLogs(newLogs: List<LogEntry>) {
        logs = newLogs
        notifyDataSetChanged()
    }
}

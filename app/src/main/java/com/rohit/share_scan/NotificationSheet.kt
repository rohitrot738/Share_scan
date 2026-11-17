package com.rohit.share_scan

import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rohit.share_scan.databinding.ItemHelpBinding
import com.rohit.share_scan.databinding.SheetListBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Menu → सूचनाएँ: everything the app told the user ([NotificationInbox]), newest first, the ones since the list was last
 * opened marked new. A tap opens what the alert was about: the share (on its news for a filing), Paper Trading, बड़े
 * पैसे की चाल, or a crash's report to send or copy again.
 */
class NotificationSheet(
    private val activity: AppCompatActivity,
    private val onOpen: (NotificationLog.Entry) -> Unit,
    private val onBack: () -> Unit,
) {
    private val ui = SheetListBinding.inflate(activity.layoutInflater)
    private lateinit var dialog: BottomSheetDialog

    fun show(): BottomSheetDialog {
        ui.listTitle.setText(R.string.inbox_title)
        ui.listSubtitle.setText(R.string.inbox_subtitle)
        dialog = activity.showSheet(ui.root, ui.sheetTopBar, onBack, fullHeight = true)
        val app = activity.applicationContext
        activity.lifecycleScope.launch {
            val (entries, seenAt) = withContext(Dispatchers.IO) {
                val seen = NotificationInbox.seenAt(app)
                NotificationInbox.markSeen(app)
                NotificationInbox.load(app) to seen
            }
            render(entries, seenAt)
        }
        return dialog
    }

    private fun render(entries: List<NotificationLog.Entry>, seenAt: Long) {
        ui.listItems.removeAllViews()
        if (entries.isEmpty()) {
            val card = ItemHelpBinding.inflate(activity.layoutInflater, ui.listItems, true)
            card.helpTitle.setText(R.string.inbox_empty_title)
            card.helpBody.setText(R.string.inbox_empty_body)
            card.helpBody.isVisible = true
            return
        }
        entries.forEach { entry -> card(entry, fresh = entry.at > seenAt) }
        val clear = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.inbox_clear)
            setOnClickListener { confirmClear() }
        }
        ui.listItems.addView(
            clear,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (16 * activity.resources.displayMetrics.density).toInt()
            },
        )
    }

    private fun card(entry: NotificationLog.Entry, fresh: Boolean) {
        val card = ItemHelpBinding.inflate(activity.layoutInflater, ui.listItems, true)
        card.helpTitle.text = entry.title
        if (fresh) card.helpTitle.setTextColor(ContextCompat.getColor(activity, R.color.bull))
        // A crash's report runs long: its first lines here, the whole of it a tap away.
        val text = if (entry.kind == NotificationLog.Kind.CRASH) {
            entry.text.lines().take(CRASH_LINES).joinToString("\n") + "\n" + activity.getString(R.string.inbox_crash_hint)
        } else {
            entry.text
        }
        val stamp = listOfNotNull(
            activity.getString(R.string.inbox_new).takeIf { fresh },
            whenShown(entry.at),
        ).joinToString(" · ")
        card.helpBody.text = "$stamp\n$text"
        card.helpBody.isVisible = true
        if (entry.opens != NotificationLog.Opens.NOTHING) {
            card.root.setOnClickListener {
                dialog.dismiss()
                onOpen(entry)
            }
        }
    }

    /** "आज 14:58", or the day as well: "शुक्र 13 नव 14:58". */
    private fun whenShown(at: Long): String {
        val time = MarketClock.now(at)
        val clock = CLOCK.format(time)
        return if (time.toLocalDate() == MarketClock.now().toLocalDate()) {
            activity.getString(R.string.time_today, clock)
        } else {
            DAY_TIME.withLocale(activity.dateLocale()).format(Instant.ofEpochMilli(at).atZone(MarketClock.INDIA))
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.inbox_clear_title)
            .setMessage(R.string.inbox_clear_body)
            .setPositiveButton(R.string.inbox_clear) { _, _ ->
                val app = activity.applicationContext
                activity.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { NotificationInbox.clear(app) }
                    render(emptyList(), Long.MAX_VALUE)
                }
            }
            .setNegativeButton(R.string.sheet_close, null)
            .show()
    }

    private companion object {
        const val CRASH_LINES = 3
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.US)
        val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")
    }
}

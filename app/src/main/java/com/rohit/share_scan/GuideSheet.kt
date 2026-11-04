package com.rohit.share_scan

import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.rohit.share_scan.databinding.SheetGuideBinding

/**
 * The beginner's guide (Menu → शुरुआती गाइड): every part of the app explained step by step in Hindi, with
 * screenshots. The page and its pictures ship in the app's assets (assets/guide), so it opens without internet; a
 * link out of it (the download link) opens in the browser.
 */
class GuideSheet(
    private val activity: AppCompatActivity,
    private val onBack: () -> Unit,
) {
    private val ui = SheetGuideBinding.inflate(activity.layoutInflater)

    fun show(): BottomSheetDialog {
        ui.guideWeb.settings.allowFileAccess = false // the assets stay readable; the phone's files do not
        ui.guideWeb.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.scheme == "file") return false
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    .onFailure { Toast.makeText(activity, R.string.about_open_failed, Toast.LENGTH_SHORT).show() }
                return true
            }
        }
        ui.guideWeb.loadUrl(PAGE)
        val dialog = activity.showSheet(ui.root, ui.sheetTopBar, onBack, fullHeight = true)
        // The page scrolls on its own; a sheet that also dragged would fight it, so ✕ and Back close it.
        dialog.behavior.isDraggable = false
        dialog.setOnDismissListener { ui.guideWeb.destroy() }
        return dialog
    }

    private companion object {
        const val PAGE = "file:///android_asset/guide/index.html"
    }
}

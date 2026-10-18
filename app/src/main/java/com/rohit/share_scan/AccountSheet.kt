package com.rohit.share_scan

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Patterns
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rohit.share_scan.databinding.DialogAccountNameBinding
import com.rohit.share_scan.databinding.DialogAccountPasswordBinding
import com.rohit.share_scan.databinding.SheetAccountBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The menu's Account sheet. Without a Firebase config it only explains that login is not set up; signed out it
 * offers Google, GitHub and email + password; signed in it shows the profile and the account's actions.
 * Requests run one at a time through [AccountManager], with the progress bar up and the buttons off meanwhile.
 */
class AccountSheet(
    private val activity: AppCompatActivity,
    private val onBack: () -> Unit,
) {
    private val ui = SheetAccountBinding.inflate(activity.layoutInflater)
    private var childDialog: AlertDialog? = null
    private var busy = false

    /** The photo already in the avatar, so a re-render does not download it again. */
    private var shownPhoto: String? = null

    private val controls
        get() = listOf(
            ui.accountGoogle, ui.accountGithub, ui.accountEmail, ui.accountPassword, ui.accountSignIn,
            ui.accountCreate, ui.accountForgot, ui.accountEditName, ui.accountSendVerification,
            ui.accountChangePassword, ui.accountLinkGoogle, ui.accountLinkGithub, ui.accountSignOut, ui.accountDelete,
        )

    fun show(): BottomSheetDialog {
        bindSignedOut()
        bindSignedIn()
        render()
        val sheet = activity.showSheet(ui.root, ui.sheetTopBar, onBack)
        // Closing the sheet (or the screen being re-created) takes an open dialog with it.
        sheet.setOnDismissListener { childDialog?.dismiss() }
        keepFieldsAboveKeyboard(sheet)
        if (AccountManager.hasPendingResult(activity)) {
            request(text(R.string.account_signed_in_toast)) { AccountManager.finishPendingResult(activity) }
        } else if (AccountManager.profile(activity) != null) {
            refreshQuietly()
        }
        return sheet
    }

    private fun bindSignedOut() {
        ui.accountGoogle.setOnClickListener {
            request(text(R.string.account_signed_in_toast)) { AccountManager.signInWithGoogle(activity) }
        }
        ui.accountGithub.setOnClickListener {
            request(text(R.string.account_signed_in_toast)) { AccountManager.signInWithGitHub(activity) }
        }
        ui.accountSignIn.setOnClickListener { signInWithEmail() }
        ui.accountPassword.setOnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_DONE).also { done -> if (done) signInWithEmail() }
        }
        ui.accountCreate.setOnClickListener {
            val (email, password) = emailForm(needPassword = true) ?: return@setOnClickListener
            if (password.length < 6) {
                ui.accountPasswordLayout.error = text(R.string.account_error_weak_password)
                return@setOnClickListener
            }
            request(text(R.string.account_created)) { AccountManager.createAccount(activity, email, password) }
        }
        ui.accountForgot.setOnClickListener {
            val (email, _) = emailForm(needPassword = false) ?: return@setOnClickListener
            request(text(R.string.account_reset_sent, email)) { AccountManager.sendPasswordReset(activity, email) }
        }
    }

    private fun signInWithEmail() {
        val (email, password) = emailForm(needPassword = true) ?: return
        request(text(R.string.account_signed_in_toast)) { AccountManager.signInWithEmail(activity, email, password) }
    }

    /** The typed email and password, or null after marking the field that needs fixing. */
    private fun emailForm(needPassword: Boolean): Pair<String, String>? {
        val email = ui.accountEmail.text?.toString()?.trim().orEmpty()
        val password = ui.accountPassword.text?.toString().orEmpty()
        ui.accountEmailLayout.error = when {
            email.isEmpty() -> text(R.string.account_email_needed)
            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> text(R.string.account_error_bad_email)
            else -> null
        }
        ui.accountPasswordLayout.error =
            if (needPassword && password.isEmpty()) text(R.string.account_password_needed) else null
        if (ui.accountEmailLayout.error != null || ui.accountPasswordLayout.error != null) return null
        activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(ui.root.windowToken, 0)
        return email to password
    }

    private fun bindSignedIn() {
        ui.accountCopyUid.setOnClickListener { copyUid() }
        ui.accountEditName.setOnClickListener { editName() }
        ui.accountSendVerification.setOnClickListener {
            val email = AccountManager.profile(activity)?.email.orEmpty()
            request(text(R.string.account_verification_sent, email)) { AccountManager.sendVerification(activity) }
        }
        ui.accountChangePassword.setOnClickListener { changePassword() }
        ui.accountLinkGoogle.setOnClickListener {
            request(text(R.string.account_linked, text(R.string.account_provider_google))) {
                AccountManager.linkGoogle(activity)
            }
        }
        ui.accountLinkGithub.setOnClickListener {
            request(text(R.string.account_linked, text(R.string.account_provider_github))) {
                AccountManager.linkGitHub(activity)
            }
        }
        ui.accountSignOut.setOnClickListener {
            request(text(R.string.account_signed_out_toast)) { AccountManager.signOut(activity) }
        }
        ui.accountDelete.setOnClickListener { confirmDelete() }
    }

    private fun render() {
        val configured = AccountManager.auth(activity) != null
        val profile = AccountManager.profile(activity)
        ui.accountUnconfigured.isVisible = !configured
        ui.accountSignedOut.isVisible = configured && profile == null
        ui.accountSignedIn.isVisible = profile != null
        ui.accountSubtitle.setText(
            when {
                !configured -> R.string.account_subtitle_unconfigured
                profile == null -> R.string.account_subtitle_signed_out
                else -> R.string.account_subtitle_signed_in
            },
        )
        ui.accountGoogle.isVisible = AccountManager.hasGoogle
        ui.accountProgress.isVisible = busy
        controls.forEach { it.isEnabled = !busy }
        if (profile != null) {
            ui.accountPassword.text?.clear()
            renderProfile(profile)
        }
    }

    private fun renderProfile(profile: AccountManager.Profile) {
        ui.accountName.text = profile.name ?: text(R.string.account_no_name)
        ui.accountInitial.text = AccountFormat.initial(profile.name, profile.email)
        ui.accountEmailText.isVisible = profile.email != null
        ui.accountEmailText.text = profile.email
        ui.accountVerified.isVisible = profile.email != null
        val (label, ink, fill) = if (profile.emailVerified) {
            Triple(R.string.account_verified, R.color.bull, R.color.bull_container)
        } else {
            Triple(R.string.account_unverified, R.color.warn, R.color.warn_container)
        }
        ui.accountVerified.setText(label)
        ui.accountVerified.setTextColor(color(ink))
        ui.accountVerified.backgroundTintList = ColorStateList.valueOf(color(fill))
        ui.accountPhone.isVisible = profile.phone != null
        ui.accountPhone.text = profile.phone

        ui.accountMethods.text = profile.providers.mapNotNull { providerName(it) }
            .joinToString(" • ") { text(it) }
            .ifEmpty { text(R.string.account_unknown) }
        val months = activity.resources.getStringArray(R.array.account_months).toList()
        ui.accountCreatedAt.text =
            profile.createdAt?.let { AccountFormat.dateTime(it, months) } ?: text(R.string.account_unknown)
        ui.accountLastSignIn.text =
            profile.lastSignInAt?.let { AccountFormat.dateTime(it, months) } ?: text(R.string.account_unknown)
        ui.accountUid.text = profile.uid

        ui.accountSendVerification.isVisible = profile.email != null && !profile.emailVerified
        ui.accountChangePassword.isVisible = AccountManager.PROVIDER_PASSWORD in profile.providers
        ui.accountLinkGoogle.isVisible =
            AccountManager.hasGoogle && AccountManager.PROVIDER_GOOGLE !in profile.providers
        ui.accountLinkGithub.isVisible = AccountManager.PROVIDER_GITHUB !in profile.providers
        showPhoto(profile.photoUrl)
    }

    @StringRes
    private fun providerName(id: String): Int? = when (id) {
        AccountManager.PROVIDER_GOOGLE -> R.string.account_provider_google
        AccountManager.PROVIDER_GITHUB -> R.string.account_provider_github
        AccountManager.PROVIDER_PASSWORD -> R.string.account_provider_password
        "phone" -> R.string.account_provider_phone
        else -> null
    }

    /** The account's photo over the initial once it has downloaded; the initial stays if there is none. */
    private fun showPhoto(url: String?) {
        if (url == shownPhoto) return
        shownPhoto = url
        ui.accountPhoto.isVisible = false
        ui.accountInitial.isVisible = true
        if (url == null) return
        activity.lifecycleScope.launch {
            val photo = download(url) ?: return@launch
            if (shownPhoto != url) return@launch
            ui.accountPhoto.setImageBitmap(photo)
            ui.accountPhoto.clipToOutline = true
            ui.accountPhoto.isVisible = true
            ui.accountInitial.isVisible = false
        }
    }

    /** A plain background-thread fetch, since one small avatar does not justify an image library. Null on failure. */
    private suspend fun download(url: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as? HttpURLConnection ?: return@withContext null
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            try {
                connection.inputStream.use { BitmapFactory.decodeStream(it) }
            } finally {
                connection.disconnect()
            }
        } catch (error: IOException) {
            null
        }
    }

    private fun copyUid() {
        val clipboard = activity.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(text(R.string.account_uid), ui.accountUid.text))
        // Android 13+ confirms copies itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast(text(R.string.account_uid_copied))
    }

    private fun editName() {
        val form = DialogAccountNameBinding.inflate(activity.layoutInflater)
        form.accountNameField.setText(AccountManager.profile(activity)?.name)
        showDialog(
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.account_edit_name)
                .setView(form.root)
                .setNegativeButton(R.string.account_cancel, null)
                .setPositiveButton(R.string.account_save) { _, _ ->
                    val name = form.accountNameField.text?.toString()?.trim().orEmpty()
                    if (name.isNotEmpty()) {
                        request(text(R.string.account_name_saved)) { AccountManager.updateName(activity, name) }
                    }
                },
        )
    }

    /** Email accounts: the current password plus a new one, or a reset email for a forgotten current one. */
    private fun changePassword() {
        val form = DialogAccountPasswordBinding.inflate(activity.layoutInflater)
        showDialog(
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.account_change_password)
                .setView(form.root)
                .setNegativeButton(R.string.account_cancel, null)
                .setNeutralButton(R.string.account_password_send_reset) { _, _ ->
                    val email = AccountManager.profile(activity)?.email ?: return@setNeutralButton
                    request(text(R.string.account_reset_sent, email)) {
                        AccountManager.sendPasswordReset(activity, email)
                    }
                }
                .setPositiveButton(R.string.account_change_password) { _, _ ->
                    val current = form.accountCurrentField.text?.toString().orEmpty()
                    val fresh = form.accountNewField.text?.toString().orEmpty()
                    when {
                        current.isEmpty() -> toast(text(R.string.account_password_needed))
                        fresh.length < 6 -> toast(text(R.string.account_error_weak_password))
                        else -> request(text(R.string.account_password_changed)) {
                            AccountManager.changePassword(activity, current, fresh)
                        }
                    }
                },
        )
    }

    private fun confirmDelete() {
        showDialog(
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.account_delete_title)
                .setMessage(R.string.account_delete_message)
                .setNegativeButton(R.string.account_cancel, null)
                .setPositiveButton(R.string.account_delete_yes) { _, _ ->
                    request(text(R.string.account_deleted)) { AccountManager.deleteAccount(activity) }
                },
        )
    }

    /** Firebase wants a fresh login before deleting the account; signing out lets the user log straight back in. */
    private fun askToSignInAgain() {
        showDialog(
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.account_relogin_title)
                .setMessage(R.string.account_relogin_message)
                .setNegativeButton(R.string.account_cancel, null)
                .setPositiveButton(R.string.account_relogin_yes) { _, _ ->
                    request(text(R.string.account_signed_out_toast)) { AccountManager.signOut(activity) }
                },
        )
    }

    private fun showDialog(builder: MaterialAlertDialogBuilder) {
        childDialog?.dismiss()
        childDialog = builder.show()
    }

    /** Runs one account request with the progress bar up and the buttons off; a failure becomes a Hindi toast. */
    private fun request(done: String?, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        render()
        activity.lifecycleScope.launch {
            val failure = try {
                action()
                null
            } catch (error: Exception) {
                // The screen going away ends the request quietly; a cancelled Firebase task is just a failure.
                if (error is CancellationException && !isActive) throw error
                error
            }
            busy = false
            render()
            when {
                failure == null -> done?.let { toast(it) }
                AccountManager.needsRecentLogin(failure) -> askToSignInAgain()
                else -> AccountManager.errorMessage(failure)?.let { toast(text(it)) }
            }
        }
    }

    /** Picks up changes made elsewhere, such as the link in the verification email having been opened. */
    private fun refreshQuietly() {
        activity.lifecycleScope.launch {
            try {
                AccountManager.refresh(activity)
            } catch (error: Exception) {
                if (error is CancellationException && !isActive) throw error
                // Offline or signed out elsewhere: the sheet keeps showing what it already knows.
            }
            if (!busy) render()
        }
    }

    /**
     * Pads the sheet by the keyboard's height so the email and password fields stay in view while typing; the sheet
     * is drawn edge to edge, so the window is not resized for the keyboard by itself.
     */
    private fun keepFieldsAboveKeyboard(sheet: BottomSheetDialog) {
        // Deprecated, but still what makes Android 8–10 report the keyboard as an inset.
        @Suppress("DEPRECATION")
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val base = ui.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(ui.root) { view, insets ->
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            view.updatePadding(bottom = base + (keyboard - bars).coerceAtLeast(0))
            insets
        }
    }

    private fun text(@StringRes id: Int, vararg args: Any): String = activity.getString(id, *args)

    private fun color(@ColorRes id: Int) = ContextCompat.getColor(activity, id)

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
}

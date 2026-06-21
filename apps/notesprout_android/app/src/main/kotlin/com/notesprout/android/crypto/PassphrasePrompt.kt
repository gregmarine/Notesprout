package com.notesprout.android.crypto

import android.app.Activity
import android.os.CountDownTimer
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.notesprout.android.R
import com.notesprout.android.databinding.DialogPassphraseBinding
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Reusable e-ink passphrase dialog — no Material Components.
 *
 * NEVER log the returned passphrase value. Callers in lifecycleScope (Main dispatcher) can
 * use the suspend form top-to-bottom without blocking the UI thread.
 */
object PassphrasePrompt {

    /**
     * Show a passphrase prompt and return the entered string, or null if the user cancelled.
     * When [confirm] is true a second field is shown; one eye toggle reveals/masks both fields.
     * When [lockedUntilMs] is in the future, the input is disabled with a live countdown until
     * it elapses (escalating lockout from AttemptLimiter). Must be called from the main thread.
     */
    suspend fun promptForPassphrase(
        activity: Activity,
        title: String,
        message: String,
        confirm: Boolean = false,
        lockedUntilMs: Long = 0L,
    ): String? = suspendCancellableCoroutine { cont ->
        val dialogBinding = DialogPassphraseBinding.inflate(activity.layoutInflater)

        if (message.isNotBlank()) {
            dialogBinding.tvPassphraseMessage.text = message
            dialogBinding.tvPassphraseMessage.visibility = View.VISIBLE
        }
        if (confirm) {
            dialogBinding.rowPassphraseConfirm.visibility = View.VISIBLE
        }

        // Eye toggle: one button reveals/masks both fields simultaneously.
        var isVisible = false
        dialogBinding.btnTogglePassphrase.setOnClickListener {
            isVisible = !isVisible
            val inputType = if (isVisible)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

            dialogBinding.editPassphrase.inputType = inputType
            // Restore cursor position — changing inputType resets it to 0.
            dialogBinding.editPassphrase.setSelection(
                dialogBinding.editPassphrase.text?.length ?: 0
            )

            if (confirm) {
                dialogBinding.editPassphraseConfirm.inputType = inputType
                dialogBinding.editPassphraseConfirm.setSelection(
                    dialogBinding.editPassphraseConfirm.text?.length ?: 0
                )
            }

            dialogBinding.btnTogglePassphrase.setImageResource(
                if (isVisible) R.drawable.ic_eye else R.drawable.ic_eye_off
            )
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(dialogBinding.root)
            .setPositiveButton("OK", null) // listener set below to allow inline validation
            .setNegativeButton("Cancel") { _, _ ->
                val imm = activity.getSystemService(InputMethodManager::class.java)
                imm.hideSoftInputFromWindow(dialogBinding.editPassphrase.windowToken, 0)
                if (cont.isActive) cont.resume(null)
            }
            .create()

        dialog.setOnCancelListener {
            if (cont.isActive) cont.resume(null)
        }

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)

        val okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        okButton?.setOnClickListener {
            val passphrase = dialogBinding.editPassphrase.text?.toString().orEmpty()
            if (passphrase.isEmpty()) {
                showError(dialogBinding, "Passphrase cannot be empty")
                return@setOnClickListener
            }
            if (confirm) {
                val confirmText = dialogBinding.editPassphraseConfirm.text?.toString().orEmpty()
                if (passphrase != confirmText) {
                    showError(dialogBinding, "Passphrases do not match")
                    return@setOnClickListener
                }
            }
            val imm = activity.getSystemService(InputMethodManager::class.java)
            imm.hideSoftInputFromWindow(dialogBinding.editPassphrase.windowToken, 0)
            dialog.dismiss()
            if (cont.isActive) cont.resume(passphrase)
        }

        // Lockout countdown: disable input until the lockout expires, then re-enable.
        var countDownTimer: CountDownTimer? = null
        val lockRemaining = lockedUntilMs - System.currentTimeMillis()
        if (lockRemaining > 0L) {
            dialogBinding.editPassphrase.isEnabled = false
            if (confirm) dialogBinding.editPassphraseConfirm.isEnabled = false
            okButton?.isEnabled = false

            showError(dialogBinding, formatLockout(lockRemaining))

            countDownTimer = object : CountDownTimer(lockRemaining, 1000L) {
                override fun onTick(millisUntilFinished: Long) {
                    if (dialog.isShowing) showError(dialogBinding, formatLockout(millisUntilFinished))
                }
                override fun onFinish() {
                    if (!dialog.isShowing) return
                    dialogBinding.tvPassphraseError.visibility = View.GONE
                    dialogBinding.editPassphrase.isEnabled = true
                    if (confirm) dialogBinding.editPassphraseConfirm.isEnabled = true
                    okButton?.isEnabled = true
                    dialogBinding.editPassphrase.requestFocus()
                    ViewCompat.getWindowInsetsController(dialogBinding.editPassphrase)
                        ?.show(WindowInsetsCompat.Type.ime())
                        ?: run {
                            val imm = activity.getSystemService(InputMethodManager::class.java)
                            @Suppress("DEPRECATION")
                            imm.showSoftInput(dialogBinding.editPassphrase, InputMethodManager.SHOW_IMPLICIT)
                        }
                }
            }.start()
        } else {
            dialogBinding.editPassphrase.requestFocus()
            dialogBinding.editPassphrase.postDelayed({
                ViewCompat.getWindowInsetsController(dialogBinding.editPassphrase)
                    ?.show(WindowInsetsCompat.Type.ime())
                    ?: run {
                        val imm = activity.getSystemService(InputMethodManager::class.java)
                        @Suppress("DEPRECATION")
                        imm.showSoftInput(dialogBinding.editPassphrase, InputMethodManager.SHOW_IMPLICIT)
                    }
            }, 100)
        }

        cont.invokeOnCancellation {
            countDownTimer?.cancel()
            dialog.dismiss()
        }
    }

    private fun formatLockout(msRemaining: Long): String {
        val totalSec = maxOf(1L, (msRemaining + 999L) / 1000L)
        val min = totalSec / 60
        val sec = totalSec % 60
        return if (min > 0L) "Too many attempts. Try again in ${min}m ${sec}s."
               else "Too many attempts. Try again in ${sec}s."
    }

    /**
     * Shows a "Password-protect PDF?" three-button dialog, then (if yes) a confirm passphrase
     * prompt for the export password.
     * Returns: null = user cancelled the flow; "" = no password wanted; non-empty = export password.
     * NEVER log the returned value. This is a separate credential from the notebook passphrase.
     */
    suspend fun promptForPdfExportPassword(activity: Activity): String? {
        val wantsPassword = suspendCancellableCoroutine<Boolean?> { cont ->
            val d = AlertDialog.Builder(activity)
                .setTitle("Password-protect PDF?")
                .setMessage("Add a password to the exported PDF. The PDF reader will prompt for it when opened.")
                .setPositiveButton("Yes") { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNegativeButton("No") { _, _ -> if (cont.isActive) cont.resume(false) }
                .setNeutralButton("Cancel") { _, _ -> if (cont.isActive) cont.resume(null) }
                .setOnCancelListener { if (cont.isActive) cont.resume(null) }
                .create()
            d.show()
            d.window?.setElevation(0f)
            d.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
            cont.invokeOnCancellation { d.dismiss() }
        }
        return when (wantsPassword) {
            null -> null
            false -> ""
            true -> promptForPassphrase(
                activity,
                title = "Export password",
                message = "Set a password for the exported PDF.",
                confirm = true,
            )
        }
    }

    private fun showError(binding: DialogPassphraseBinding, message: String) {
        binding.tvPassphraseError.text = message
        binding.tvPassphraseError.visibility = View.VISIBLE
    }
}

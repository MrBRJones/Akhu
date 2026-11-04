package com.MrBRos.akhu

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Email verification: a new account is sent a link, and until it's tapped the account can browse
 * but not post, comment or like. Accounts made before this existed are let through as they are.
 */
object Verify {

    /** Accounts created before this (2026-11-04 UTC) never had to verify. */
    private const val REQUIRED_SINCE = 1793750400000L

    /**
     * True when the signed-in account may post, comment or like; otherwise asks it to verify and
     * returns false. Signed out counts as true here: those paths ask to sign in themselves.
     */
    @JvmStatic
    fun ok(context: Context): Boolean {
        val user = FirebaseHelper.currentUser(context) ?: return true
        if (user.isEmailVerified) return true
        val created = user.metadata?.creationTimestamp ?: Long.MAX_VALUE
        if (created < REQUIRED_SINCE) return true
        prompt(context)
        return false
    }

    /** Emails the signed-in account its verification link. */
    @JvmStatic
    fun send(context: Context) {
        val app = context.applicationContext
        val user = FirebaseHelper.currentUser(app) ?: return
        user.sendEmailVerification().addOnCompleteListener { t ->
            Toast.makeText(
                app,
                if (t.isSuccessful) app.getString(R.string.verify_sent, user.email) else app.getString(R.string.verify_send_failed),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun prompt(context: Context) {
        val user = FirebaseHelper.currentUser(context) ?: return
        val activity = activityOf(context)
        if (activity == null || activity.isFinishing) {
            Toast.makeText(context, R.string.verify_needed, Toast.LENGTH_LONG).show()
            return
        }
        val app = activity.applicationContext
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.verify_title)
            .setMessage(activity.getString(R.string.verify_message, user.email))
            // The link is tapped in the email app; this picks that up.
            .setPositiveButton(R.string.verify_done) { _, _ ->
                user.reload().addOnCompleteListener {
                    val done = FirebaseHelper.currentUser(app)?.isEmailVerified == true
                    Toast.makeText(app, if (done) R.string.verify_thanks else R.string.verify_not_yet, Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton(R.string.verify_resend) { _, _ -> send(app) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun activityOf(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}

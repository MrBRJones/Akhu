package com.MrBRos.akhu

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Blocking, hiding and reporting.
 *
 * - **Block an account**: `users/{me}/blocked/{uid}`. Their posts, themes, comments and notices
 *   are hidden from you, follows between you are removed, and the rules stop them following you,
 *   commenting on your posts or sending you notices. They aren't told.
 * - **Hide a post**: `users/{me}/hidden/{postId}`; only that post disappears.
 * - **Report** an account, post or comment: `reports/{id}`, which only the Firebase console reads.
 *
 * The lists are kept on the phone too, so they apply at once and offline; [version] changes when
 * they do, so screens can filter again.
 */
object Safety {

    const val KIND_POST = "post"
    const val KIND_USER = "user"
    const val KIND_COMMENT = "comment"

    private const val PREFS = "safety"
    private const val KEY_OWNER = "owner"
    private const val KEY_BLOCKED = "blocked"
    private const val KEY_HIDDEN = "hidden"

    /** Reasons offered when reporting, in the order shown; the IDs are what reports store. */
    private val REASONS = listOf(
        "sexual" to R.string.report_sexual,
        "harassment" to R.string.report_harassment,
        "hate" to R.string.report_hate,
        "violence" to R.string.report_violence,
        "spam" to R.string.report_spam,
        "stolen" to R.string.report_stolen,
        "other" to R.string.report_other,
    )

    /** Changes whenever the blocked or hidden lists do. */
    var version by mutableIntStateOf(0)
        private set

    private lateinit var app: Context
    private var blocked: Set<String> = emptySet()
    private var hidden: Set<String> = emptySet()

    private fun db() = FirebaseFirestore.getInstance()
    private fun me() = FirebaseHelper.currentUser(app)?.uid

    /** Reads the lists kept on the phone, then refreshes them from Firebase. Called from [App]. */
    @JvmStatic
    fun init(context: Context) {
        app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_OWNER, null) == me()) {
            blocked = prefs.getStringSet(KEY_BLOCKED, null).orEmpty().toSet()
            hidden = prefs.getStringSet(KEY_HIDDEN, null).orEmpty().toSet()
        }
        // Also runs once now, and again on every sign-in, sign-out or account switch.
        com.google.firebase.auth.FirebaseAuth.getInstance().addAuthStateListener { sync() }
    }

    /** Fetches the signed-in account's lists (another phone may have changed them), or clears them when signed out. */
    @JvmStatic
    fun sync() {
        val uid = me()
        if (uid == null) return store(null, emptySet(), emptySet())
        val user = db().collection("users").document(uid)
        val lists = listOf(user.collection("blocked").get(), user.collection("hidden").get())
        Tasks.whenAllSuccess<com.google.firebase.firestore.QuerySnapshot>(lists).addOnSuccessListener { (b, h) ->
            if (me() == uid) store(uid, b.documents.map { it.id }.toSet(), h.documents.map { it.id }.toSet())
        }
    }

    private fun store(owner: String?, blocked: Set<String>, hidden: Set<String>) {
        this.blocked = blocked
        this.hidden = hidden
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_OWNER, owner).putStringSet(KEY_BLOCKED, blocked).putStringSet(KEY_HIDDEN, hidden).apply()
        version++
    }

    @JvmStatic
    fun isBlocked(uid: String?): Boolean = uid != null && uid in blocked

    /** Whether [post] is hidden from this account (hidden itself, or by someone blocked). */
    @JvmStatic
    fun hides(post: CloudWallpaper): Boolean = post.id in hidden || post.uid in blocked

    /** As [hides], for a post or theme document not read into a [CloudWallpaper]. */
    @JvmStatic
    fun hides(doc: DocumentSnapshot): Boolean = doc.id in hidden || doc.getString("uid") in blocked

    fun block(uid: String, name: String): Task<Void> {
        val me = me() ?: return Tasks.forException(IllegalStateException("signed out"))
        val at = FieldValue.serverTimestamp()
        store(me, blocked + uid, hidden)
        // Following goes both ways first, while the rules still allow it, then the block itself.
        return Tasks.whenAllComplete(Social.setFollowing(me, uid, false), Social.removeFollower(me, uid)).continueWithTask {
            db().collection("users").document(me).collection("blocked").document(uid)
                .set(mapOf("name" to name.take(50), "at" to at))
        }.addOnFailureListener { store(me, blocked - uid, hidden) }
    }

    fun unblock(uid: String): Task<Void> {
        val me = me() ?: return Tasks.forException(IllegalStateException("signed out"))
        store(me, blocked - uid, hidden)
        return db().collection("users").document(me).collection("blocked").document(uid).delete()
    }

    fun hide(postId: String): Task<Void> {
        val me = me() ?: return Tasks.forException(IllegalStateException("signed out"))
        store(me, blocked, hidden + postId)
        return db().collection("users").document(me).collection("hidden").document(postId)
            .set(mapOf("at" to FieldValue.serverTimestamp()))
    }

    /** Brings every hidden post back. */
    fun unhideAll(): Task<Void> {
        val me = me() ?: return Tasks.forException(IllegalStateException("signed out"))
        val gone = hidden
        store(me, blocked, emptySet())
        val list = db().collection("users").document(me).collection("hidden")
        return Tasks.whenAll(gone.map { list.document(it).delete() })
    }

    fun hiddenCount(): Int = hidden.size

    /** The accounts this account blocked, with the names they had then, newest first. */
    fun blockedAccounts(): Task<List<Pair<String, String>>> {
        val me = me() ?: return Tasks.forResult(emptyList())
        return db().collection("users").document(me).collection("blocked")
            .orderBy("at", com.google.firebase.firestore.Query.Direction.DESCENDING).get()
            .continueWith { t -> t.result.documents.map { it.id to (it.getString("name") ?: "") } }
    }

    /** Files a report for review. [target] is the account, post or comment ID; [extra] says where it is. */
    private fun report(kind: String, target: String, reason: String, extra: Map<String, Any?>): Task<Void> {
        val me = me() ?: return Tasks.forException(IllegalStateException("signed out"))
        val doc = mapOf("by" to me, "kind" to kind, "target" to target, "reason" to reason, "at" to FieldValue.serverTimestamp()) + extra
        return db().collection("reports").document().set(doc)
    }

    // ---- What people tap ----

    /** Signed in, or sends them to sign in and returns false. */
    private fun signedIn(activity: Activity): Boolean {
        if (me() != null) return true
        Toast.makeText(activity, R.string.sign_in_required, Toast.LENGTH_SHORT).show()
        activity.startActivity(Intent(activity, AuthActivity::class.java))
        return false
    }

    private fun handle(context: Context, name: String) =
        if (name.isBlank()) context.getString(R.string.this_account) else "@" + name.removePrefix("@")

    /**
     * The "⋮" menu on someone else's post: report it, hide it, or block who posted it.
     * [onGone] runs once it's hidden or its poster is blocked (the screen should close or refilter).
     */
    @JvmStatic
    fun postMenu(activity: Activity, post: CloudWallpaper, collection: String, onGone: Runnable) {
        if (!signedIn(activity)) return
        val name = post.displayName ?: ""
        val items = arrayOf(
            activity.getString(R.string.report_post),
            activity.getString(R.string.hide_post),
            activity.getString(R.string.block_name, handle(activity, name)),
        )
        MaterialAlertDialogBuilder(activity).setItems(items) { _, which ->
            when (which) {
                0 -> askReason(activity, R.string.report_post) { reason ->
                    report(KIND_POST, post.id, reason, mapOf("collection" to collection, "owner" to post.uid))
                        .addOnCompleteListener(activity) { t -> reported(activity, t, post.uid, name, onGone) }
                }
                1 -> hide(post.id).addOnCompleteListener(activity) {
                    Toast.makeText(activity, R.string.post_hidden, Toast.LENGTH_SHORT).show()
                    onGone.run()
                }
                2 -> confirmBlock(activity, post.uid, name, onGone)
            }
        }.show()
    }

    /** The "⋮" menu on someone's profile: report the account, and block or unblock it. */
    @JvmStatic
    fun userMenu(activity: Activity, uid: String, name: String, onChanged: Runnable) {
        if (!signedIn(activity) || uid == me()) return
        val blockedNow = isBlocked(uid)
        val items = arrayOf(
            activity.getString(R.string.report_account),
            activity.getString(if (blockedNow) R.string.unblock_name else R.string.block_name, handle(activity, name)),
        )
        MaterialAlertDialogBuilder(activity).setItems(items) { _, which ->
            if (which == 0) {
                askReason(activity, R.string.report_account) { reason ->
                    report(KIND_USER, uid, reason, emptyMap()).addOnCompleteListener(activity) { t ->
                        reported(activity, t, uid, name, onChanged)
                    }
                }
            } else if (blockedNow) {
                unblock(uid).addOnCompleteListener(activity) {
                    Toast.makeText(activity, activity.getString(R.string.unblocked_name, handle(activity, name)), Toast.LENGTH_SHORT).show()
                    onChanged.run()
                }
            } else {
                confirmBlock(activity, uid, name, onChanged)
            }
        }.show()
    }

    /** Long press on someone else's comment: report it or block its writer, and delete it when allowed. */
    fun commentMenu(activity: Activity, postId: String, collection: String, comment: Comment, onDelete: Runnable?, onChanged: Runnable) {
        val own = comment.uid == me()
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (onDelete != null) items += activity.getString(R.string.delete) to { onDelete.run() }
        if (!own) {
            items += activity.getString(R.string.report_comment) to {
                if (signedIn(activity)) askReason(activity, R.string.report_comment) { reason ->
                    report(KIND_COMMENT, comment.id, reason, mapOf("post" to postId, "collection" to collection, "owner" to comment.uid, "text" to comment.text.take(500)))
                        .addOnCompleteListener(activity) { t -> reported(activity, t, comment.uid, comment.name, onChanged) }
                }
            }
            items += activity.getString(R.string.block_name, handle(activity, comment.name)) to {
                if (signedIn(activity)) confirmBlock(activity, comment.uid, comment.name, onChanged)
            }
        }
        if (items.isEmpty()) return
        MaterialAlertDialogBuilder(activity).setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }.show()
    }

    private fun askReason(activity: Activity, title: Int, onReason: (String) -> Unit) {
        var chosen = -1
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setSingleChoiceItems(REASONS.map { activity.getString(it.second) }.toTypedArray(), -1) { d, which ->
                chosen = which
                (d as androidx.appcompat.app.AlertDialog).getButton(android.content.DialogInterface.BUTTON_POSITIVE).isEnabled = true
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.report_send) { _, _ -> if (chosen >= 0) onReason(REASONS[chosen].first) }
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).isEnabled = false
    }

    /**
     * After a report: thanks them and offers to block the account too (as most apps do), or says
     * it couldn't be sent.
     */
    private fun reported(activity: Activity, t: Task<Void>, uid: String?, name: String, onBlocked: Runnable) {
        if (activity.isFinishing) return
        if (!t.isSuccessful) {
            Toast.makeText(activity, R.string.report_failed, Toast.LENGTH_LONG).show()
            return
        }
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.report_thanks_title)
            .setMessage(R.string.report_thanks)
        if (uid != null && uid != me() && !isBlocked(uid)) {
            builder.setNegativeButton(R.string.done, null)
                .setPositiveButton(activity.getString(R.string.block_name, handle(activity, name))) { _, _ -> confirmBlock(activity, uid, name, onBlocked) }
        } else {
            builder.setPositiveButton(R.string.done, null)
        }
        builder.show()
    }

    private fun confirmBlock(activity: Activity, uid: String, name: String, onBlocked: Runnable) {
        if (uid == me()) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.block_title, handle(activity, name)))
            .setMessage(R.string.block_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.block) { _, _ ->
                block(uid, name).addOnCompleteListener(activity) { t ->
                    if (t.isSuccessful) {
                        Toast.makeText(activity, activity.getString(R.string.blocked_name, handle(activity, name)), Toast.LENGTH_SHORT).show()
                        onBlocked.run()
                    } else {
                        Toast.makeText(activity, R.string.block_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    /** Settings → Blocked accounts: the list, each with Unblock, and a way to bring hidden posts back. */
    fun manage(activity: Activity) {
        if (!signedIn(activity)) return
        blockedAccounts().addOnCompleteListener(activity) { t ->
            val accounts = if (t.isSuccessful) t.result else emptyList()
            val builder = MaterialAlertDialogBuilder(activity).setTitle(R.string.blocked_accounts)
            if (accounts.isEmpty()) {
                builder.setMessage(R.string.blocked_none)
            } else {
                builder.setItems(accounts.map { activity.getString(R.string.unblock_name, handle(activity, it.second)) }.toTypedArray()) { _, which ->
                    val (uid, name) = accounts[which]
                    unblock(uid).addOnCompleteListener(activity) {
                        Toast.makeText(activity, activity.getString(R.string.unblocked_name, handle(activity, name)), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            val hiddenNow = hiddenCount()
            if (hiddenNow > 0) {
                builder.setNeutralButton(activity.resources.getQuantityString(R.plurals.show_hidden_posts, hiddenNow, hiddenNow)) { _, _ ->
                    unhideAll().addOnCompleteListener(activity) {
                        Toast.makeText(activity, R.string.hidden_posts_back, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            builder.setPositiveButton(R.string.done, null).show()
        }
    }
}

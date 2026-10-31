package com.MrBRos.akhu

import android.app.Activity
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.MrBRos.akhu.databinding.SheetCommentsBinding
import com.google.android.gms.tasks.Task
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query

/** One comment on a post. */
data class Comment(val id: String, val uid: String, val name: String, val text: String)

/**
 * Comments on cloud posts, kept at wallpapers/{post}/comments/{id}. Anyone who can see the post
 * can read them; signed-in people add their own and may delete their own, and the poster may
 * delete any on their post. The count is counted on the server, like follower counts.
 */
object Comments {

    const val MAX_LENGTH = 500
    private const val LIMIT = 200L

    private fun of(post: String) = FirebaseHelper.doc(post).collection("comments")

    @JvmStatic
    fun count(post: String): Task<Long> = of(post).count().get(AggregateSource.SERVER).continueWith { it.result.count }

    /** Oldest first, like a conversation. */
    fun list(post: String): Task<List<Comment>> =
        of(post).orderBy("createdAt", Query.Direction.ASCENDING).limit(LIMIT).get().continueWith { t ->
            t.result.documents.map {
                Comment(it.id, it.getString("uid") ?: "", it.getString("name") ?: "", it.getString("text") ?: "")
            }
        }

    fun add(post: String, uid: String, name: String, text: String): Task<Void> =
        of(post).document().set(
            mapOf("uid" to uid, "name" to name.take(50), "text" to text, "createdAt" to FieldValue.serverTimestamp())
        )

    fun delete(post: String, id: String): Task<Void> = of(post).document(id).delete()

    /**
     * Opens the comments as a bottom sheet. [poster] is the post's owner, who may delete any comment;
     * [onChanged] runs after one is added or deleted, to refresh the count.
     */
    @JvmStatic
    fun show(activity: Activity, post: String, poster: String?, onChanged: Runnable) {
        val sheet = BottomSheetDialog(activity)
        val b = SheetCommentsBinding.inflate(activity.layoutInflater)
        sheet.setContentView(b.root)
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val me = FirebaseHelper.currentUser(activity)

        fun load() {
            list(post).addOnCompleteListener(activity) { t ->
                b.list.removeAllViews()
                if (!t.isSuccessful) {
                    b.empty.setText(R.string.comments_load_failed)
                    b.empty.visibility = View.VISIBLE
                    return@addOnCompleteListener
                }
                val all = t.result
                b.empty.setText(R.string.comments_empty)
                b.empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
                for (c in all) b.list.addView(row(activity, c) {
                    if (me == null || (c.uid != me.uid && poster != me.uid)) return@row
                    MaterialAlertDialogBuilder(activity)
                        .setMessage(R.string.delete_comment)
                        .setPositiveButton(R.string.delete) { _, _ ->
                            delete(post, c.id).addOnCompleteListener(activity) { load(); onChanged.run() }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }.also { v ->
                    // Tap the name to see who wrote it.
                    v.setOnClickListener { activity.startActivity(UserProfileActivity.intent(activity, c.uid, c.name)) }
                })
                b.list.post { (b.list.parent as View).scrollTo(0, b.list.height) }
            }
        }

        b.send.setOnClickListener {
            val text = b.input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            if (me == null) {
                Toast.makeText(activity, R.string.sign_in_to_comment, Toast.LENGTH_SHORT).show()
                activity.startActivity(android.content.Intent(activity, AuthActivity::class.java))
                return@setOnClickListener
            }
            val own = Social.local(activity)
            val name = own.username.ifEmpty { own.name.ifEmpty { FirebaseHelper.displayNameOf(me) } }
            b.send.isEnabled = false
            add(post, me.uid, name, text.take(MAX_LENGTH)).addOnCompleteListener(activity) { t ->
                b.send.isEnabled = true
                if (t.isSuccessful) {
                    b.input.text = null
                    load()
                    onChanged.run()
                } else {
                    Toast.makeText(activity, R.string.comment_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
        load()
        sheet.show()
    }

    /** "name  text" with the name in bold; long press calls [onLongPress]. */
    private fun row(activity: Activity, c: Comment, onLongPress: () -> Unit): TextView {
        val text = SpannableStringBuilder(c.name).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("  ").append(c.text)
        }
        val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, activity.resources.displayMetrics).toInt()
        return TextView(activity).apply {
            this.text = text
            setPadding(0, pad, 0, pad)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setOnLongClickListener { onLongPress(); true }
        }
    }
}

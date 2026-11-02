package com.MrBRos.akhu

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.util.Date
import java.util.concurrent.TimeUnit

/** One like, comment or share someone left on the user's post, as stored for them. */
data class Notice(
    val id: String,
    val from: String,
    val name: String,
    /** [LIKE], [COMMENT] or [SHARE]. */
    val type: String,
    /** [FirebaseHelper.COLLECTION] or [FirebaseHelper.THEMES]. */
    val collection: String,
    val post: String,
    val thumb: String?,
    val text: String?,
    val time: Long,
)

/**
 * Tells people when their posts are liked, commented on or shared. Whoever does it writes a notice
 * to users/{owner}/notices; the owner's phone turns new ones into system notifications, at once
 * while the app is open and every ~15 minutes otherwise (Android's shortest background interval).
 */
object Notices {

    const val LIKE = "like"
    const val COMMENT = "comment"
    const val SHARE = "share"

    private const val CHANNEL = "activity"
    private const val PREFS = "notices"
    private const val KEY_LAST = "last"
    private const val JOB_ID = 4201
    private const val BATCH = 20L

    private fun of(uid: String) =
        FirebaseFirestore.getInstance().collection("users").document(uid).collection("notices")

    /** Tells [post]'s owner about a like, comment or share; does nothing for their own posts. */
    @JvmStatic
    @JvmOverloads
    fun send(context: Context, type: String, collection: String, post: CloudWallpaper, text: String? = null) =
        send(context, type, collection, post.uid, post.id, post.thumbnailUrl, text)

    private fun send(context: Context, type: String, collection: String, owner: String?, post: String, thumb: String?, text: String?) {
        val me = FirebaseHelper.currentUser(context) ?: return
        if (owner.isNullOrEmpty() || owner == me.uid) return
        val own = Social.local(context)
        val data = hashMapOf<String, Any>(
            "from" to me.uid, "name" to own.username.ifEmpty { own.name }.take(50), "type" to type,
            "collection" to collection, "post" to post, "at" to FieldValue.serverTimestamp(),
        )
        thumb?.let { data["thumb"] = it }
        text?.let { data["text"] = it.take(120) }
        // One like notice per person per post, so liking again after unliking doesn't ping twice
        // (the rules refuse the repeat).
        val ref = if (type == LIKE) of(owner).document("like_" + post + "_" + me.uid) else of(owner).document()
        ref.set(data)
    }

    /** The user's latest notices, newest first, for the Inbox. */
    fun recent(uid: String, limit: Long): Task<List<Notice>> =
        of(uid).orderBy("at", Query.Direction.DESCENDING).limit(limit).get()
            .continueWith { t -> t.result.documents.mapNotNull(::read) }

    private fun read(d: DocumentSnapshot): Notice? {
        val at = d.getTimestamp("at") ?: return null
        return Notice(
            d.id, d.getString("from") ?: return null, d.getString("name") ?: "", d.getString("type") ?: return null,
            d.getString("collection") ?: FirebaseHelper.COLLECTION, d.getString("post") ?: return null,
            d.getString("thumb"), d.getString("text"), at.toDate().time,
        )
    }

    /** While the app is open: shows each new notice as it arrives. Remove the result when done. */
    @JvmStatic
    fun watch(context: Context): ListenerRegistration? {
        val app = context.applicationContext
        val me = FirebaseHelper.currentUser(app) ?: return null
        return since(app, me.uid).addSnapshotListener { snap, _ ->
            if (snap != null) notify(app, snap.documents.mapNotNull(::read))
        }
    }

    /** Checks once for new notices and shows them; [done] runs when finished. */
    private fun check(context: Context, done: () -> Unit) {
        val me = FirebaseHelper.currentUser(context) ?: return done()
        since(context, me.uid).get().addOnCompleteListener { t ->
            if (t.isSuccessful) notify(context, t.result.documents.mapNotNull(::read), done) else done()
        }
    }

    /** Notices newer than the last one shown. The first time, starts from now rather than the backlog. */
    private fun since(context: Context, uid: String): Query {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var last = prefs.getLong(KEY_LAST, 0L)
        if (last == 0L) {
            last = System.currentTimeMillis()
            prefs.edit().putLong(KEY_LAST, last).apply()
        }
        return of(uid).whereGreaterThan("at", Timestamp(Date(last)))
            .orderBy("at", Query.Direction.ASCENDING).limit(BATCH)
    }

    /** Posts a notification for each notice not shown yet, off the main thread (thumbnails load). */
    private fun notify(context: Context, notices: List<Notice>, done: () -> Unit = {}) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fresh = notices.filter { it.time > prefs.getLong(KEY_LAST, 0L) }
        if (fresh.isEmpty()) return done()
        prefs.edit().putLong(KEY_LAST, fresh.maxOf { it.time }).apply()
        if (!canNotify(context)) return done()
        Thread({
            createChannel(context)
            val manager = NotificationManagerCompat.from(context)
            for (n in fresh) {
                val open = PendingIntent.getActivity(
                    context, n.id.hashCode(),
                    Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_INBOX)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val notification = NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_nav_inbox)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(message(context, n))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message(context, n)))
                    .setLargeIcon(thumbnail(context, n.thumb))
                    .setWhen(n.time)
                    .setShowWhen(true)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                    .build()
                try {
                    manager.notify(n.id.hashCode(), notification)
                } catch (e: SecurityException) {
                    // Permission taken away meanwhile.
                }
            }
            done()
        }, "Notices").start()
    }

    /** "@name liked your wallpaper", "@name commented: …", "@name shared your theme". */
    @JvmStatic
    fun message(context: Context, n: Notice): String {
        val who = if (n.name.isEmpty()) context.getString(R.string.notice_someone) else "@" + n.name
        val theme = n.collection == FirebaseHelper.THEMES
        return when (n.type) {
            COMMENT -> context.getString(R.string.notice_commented, who, n.text ?: "")
            SHARE -> context.getString(if (theme) R.string.notice_shared_theme else R.string.notice_shared, who)
            else -> context.getString(if (theme) R.string.notice_liked_theme else R.string.notice_liked, who)
        }
    }

    private fun thumbnail(context: Context, url: String?): Bitmap? {
        if (url.isNullOrEmpty()) return null
        return try {
            Glide.with(context).asBitmap().load(url).centerCrop().submit(128, 128).get(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            null
        }
    }

    @JvmStatic
    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            && NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.notice_channel), NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = context.getString(R.string.notice_channel_desc)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Starts the background check (every ~15 minutes, when online); safe to call every launch. */
    @JvmStatic
    fun schedule(context: Context) {
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return
        if (jobs.getPendingJob(JOB_ID) != null) return
        jobs.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, Job::class.java))
                .setPeriodic(TimeUnit.MINUTES.toMillis(15))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
        )
    }

    /** The background check. */
    class Job : JobService() {
        override fun onStartJob(params: JobParameters): Boolean {
            if (!FirebaseHelper.isAvailable(this) || FirebaseHelper.currentUser(this) == null) return false
            check(applicationContext) { jobFinished(params, false) }
            return true
        }

        override fun onStopJob(params: JobParameters): Boolean = true
    }
}

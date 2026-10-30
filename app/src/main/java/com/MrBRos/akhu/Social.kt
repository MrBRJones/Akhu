package com.MrBRos.akhu

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

/** What people see on a profile. Everything but [photoUrl] is typed in Edit profile. */
data class Profile(
    val name: String = "",
    val username: String = "",
    val pronouns: String = "",
    val bio: String = "",
    val photoUrl: String? = null,
)

/**
 * Profiles and following. The user's own profile and picture are kept on the phone (so they work
 * signed out) and, when signed in, in Firestore: users/{uid} for the profile, usernames/{name} so
 * no two people share a username, and users/{uid}/followers/{who} + users/{uid}/following/{who}
 * for follows. Counts are counted on the server, so nobody can write a fake number.
 */
object Social {

    private const val PREFS = "profile"
    private const val USERS = "users"
    private const val USERNAMES = "usernames"
    private const val AVATAR_SIZE = 512
    private val USERNAME = Regex("^[a-z0-9._]{3,30}$")

    private fun db() = FirebaseFirestore.getInstance()

    // ---- The user's own profile ----

    @JvmStatic
    fun local(context: Context): Profile {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Profile(
            p.getString("name", "") ?: "", p.getString("username", "") ?: "",
            p.getString("pronouns", "") ?: "", p.getString("bio", "") ?: "", p.getString("photoUrl", null),
        )
    }

    private fun saveLocal(context: Context, profile: Profile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("name", profile.name).putString("username", profile.username)
            .putString("pronouns", profile.pronouns).putString("bio", profile.bio)
            .putString("photoUrl", profile.photoUrl).apply()
    }

    /** The profile picture as saved on this phone, or null for the default icon. */
    @JvmStatic
    fun avatarFile(context: Context): File? = File(File(context.filesDir, "profile"), "avatar.jpg").takeIf { it.exists() }

    fun normalizeUsername(typed: String) = typed.trim().removePrefix("@").lowercase(Locale.ROOT)

    fun isValidUsername(username: String) = username.isEmpty() || USERNAME.matches(username)

    /**
     * Saves the profile here and, when signed in, to Firestore. A new username is claimed first,
     * so the task fails with [UsernameTaken] if someone else has it.
     */
    fun save(context: Context, profile: Profile): Task<Void> {
        val app = context.applicationContext
        val old = local(app)
        val user = FirebaseHelper.currentUser(app)
        if (user == null) {
            saveLocal(app, profile)
            return Tasks.forResult(null)
        }
        val uid = user.uid
        val claim: Task<Void> = if (profile.username == old.username || profile.username.isEmpty()) {
            Tasks.forResult(null)
        } else {
            db().runTransaction { tx ->
                val ref = db().collection(USERNAMES).document(profile.username)
                val owner = tx.get(ref).getString("uid")
                if (owner != null && owner != uid) throw UsernameTaken()
                tx.set(ref, mapOf("uid" to uid))
                if (old.username.isNotEmpty()) tx.delete(db().collection(USERNAMES).document(old.username))
                null
            }
        }
        return claim.onSuccessTask {
            val doc = mapOf(
                "name" to profile.name, "username" to profile.username, "pronouns" to profile.pronouns,
                "bio" to profile.bio, "updatedAt" to FieldValue.serverTimestamp(),
            )
            db().collection(USERS).document(uid).set(doc, SetOptions.merge())
        }.addOnSuccessListener { saveLocal(app, profile.copy(photoUrl = old.photoUrl)) }
    }

    class UsernameTaken : Exception("username taken")

    /**
     * Makes a picked picture the profile picture: centre-cropped square, kept on the phone and,
     * when signed in, uploaded (after the explicit-picture check) for others to see. Blocking for
     * the decode; call off the main thread. Returns the upload, or null when signed out.
     */
    @Throws(IOException::class)
    fun setAvatar(context: Context, picked: File): Task<Void>? {
        val app = context.applicationContext
        val src = ImagePickerHelper.decodeSampled(picked, AVATAR_SIZE * 2) ?: throw IOException("decode")
        val side = minOf(src.width, src.height)
        val crop = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val square = Bitmap.createScaledBitmap(crop, AVATAR_SIZE, AVATAR_SIZE, true)
        if (crop != src) crop.recycle()
        if (square != src) src.recycle()
        val user = FirebaseHelper.currentUser(app)
        if (user != null && NsfwDetector.isNsfw(app, square)) {
            square.recycle()
            throw IOException(app.getString(R.string.nsfw_blocked))
        }
        val bytes = ByteArrayOutputStream().also { square.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        square.recycle()
        val dir = File(app.filesDir, "profile")
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create $dir")
        FileOutputStream(File(dir, "avatar.jpg")).use { it.write(bytes) }
        if (user == null) return null
        val ref = FirebaseStorage.getInstance().reference.child("users").child(user.uid).child("profile").child("avatar.jpg")
        return ref.putBytes(bytes, StorageMetadata.Builder().setContentType("image/jpeg").build())
            .continueWithTask { t -> if (!t.isSuccessful) throw t.exception!!; ref.downloadUrl }
            .onSuccessTask { url: Uri? ->
                val photo = url.toString()
                saveLocal(app, local(app).copy(photoUrl = photo))
                db().collection(USERS).document(user.uid).set(mapOf("photoUrl" to photo), SetOptions.merge())
            }
    }

    // ---- Anyone's profile ----

    fun load(uid: String): Task<Profile> = db().collection(USERS).document(uid).get().continueWith { t ->
        val d = t.result
        Profile(
            d.getString("name") ?: "", d.getString("username") ?: "", d.getString("pronouns") ?: "",
            d.getString("bio") ?: "", d.getString("photoUrl"),
        )
    }

    /** Pulls the signed-in user's profile down, so a new phone shows what they set elsewhere. */
    fun refreshOwn(context: Context): Task<Profile>? {
        val app = context.applicationContext
        val user = FirebaseHelper.currentUser(app) ?: return null
        return load(user.uid).addOnSuccessListener { p ->
            if (p.name.isNotEmpty() || p.username.isNotEmpty() || p.photoUrl != null) saveLocal(app, p)
        }
    }

    /** posts (public, or all when it is [mine]), followers, following. */
    fun counts(uid: String, mine: Boolean): Task<LongArray> {
        var posts = db().collection(FirebaseHelper.COLLECTION).whereEqualTo("uid", uid)
        if (!mine) posts = posts.whereEqualTo("visibility", FirebaseHelper.VISIBILITY_PUBLIC)
        val user = db().collection(USERS).document(uid)
        val tasks = listOf(
            posts.count().get(AggregateSource.SERVER),
            user.collection("followers").count().get(AggregateSource.SERVER),
            user.collection("following").count().get(AggregateSource.SERVER),
        )
        return Tasks.whenAllComplete(tasks).continueWith {
            LongArray(3) { i -> if (tasks[i].isSuccessful) tasks[i].result.count else 0 }
        }
    }

    fun isFollowing(me: String, them: String): Task<Boolean> =
        db().collection(USERS).document(them).collection("followers").document(me).get().continueWith { it.result.exists() }

    /** Follows or unfollows: both sides' lists change together. */
    fun setFollowing(me: String, them: String, on: Boolean): Task<Void> {
        val batch = db().batch()
        val follower = db().collection(USERS).document(them).collection("followers").document(me)
        val following = db().collection(USERS).document(me).collection("following").document(them)
        if (on) {
            val at = mapOf("at" to FieldValue.serverTimestamp())
            batch.set(follower, at)
            batch.set(following, at)
        } else {
            batch.delete(follower)
            batch.delete(following)
        }
        return batch.commit()
    }
}

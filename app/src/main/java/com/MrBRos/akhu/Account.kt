package com.MrBRos.akhu

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage

/**
 * Deletes the signed-in account and everything it put online: posts and themes (with their files
 * and comments), comments left on other posts, likes, follows both ways, notices, the username,
 * the profile and its picture, then the sign-in itself. Wallpapers kept on the phone stay.
 */
object Account {

    /** The password didn't match; nothing was deleted. */
    class WrongPassword : Exception()

    @JvmStatic
    fun delete(context: Context, password: String): Task<Void> {
        val app = context.applicationContext
        val user = FirebaseHelper.currentUser(app) ?: return Tasks.forException(IllegalStateException("signed out"))
        val email = user.email ?: return Tasks.forException(IllegalStateException("no email"))
        val me = user.uid
        // The password is checked first, and nothing is deleted unless it matched.
        return user.reauthenticate(EmailAuthProvider.getCredential(email, password))
            .continueWith { t ->
                if (t.exception is FirebaseAuthInvalidCredentialsException) throw WrongPassword()
                if (!t.isSuccessful) throw t.exception!!
            }
            .onSuccessTask { wipe(app, me) }
            .onSuccessTask { user.delete() }
            .onSuccessTask {
                forget(app)
                Tasks.forResult<Void>(null)
            }
    }

    /**
     * Removes everything the account put online. Each step carries on past the one before (a
     * missing post or comment shouldn't stop the rest); only the profile's own delete must work,
     * so the sign-in is never deleted while its profile is still there.
     */
    private fun wipe(app: Context, me: String): Task<Void> {
        val db = FirebaseFirestore.getInstance()
        val profile = db.collection("users").document(me)
        // Own posts and themes: Posts.delete also removes their files and comments.
        return each(db.collection(FirebaseHelper.COLLECTION).whereEqualTo("uid", me)) { Posts.delete(app, FirebaseHelper.COLLECTION, it) }
            .continueWithTask { each(db.collection(FirebaseHelper.THEMES).whereEqualTo("uid", me)) { Posts.delete(app, FirebaseHelper.THEMES, it) } }
            .continueWithTask { gone(db.collectionGroup("comments").whereEqualTo("uid", me)) }
            .continueWithTask { unlike(FirebaseHelper.COLLECTION, me) }
            .continueWithTask { unlike(FirebaseHelper.THEMES, me) }
            .continueWithTask { each(profile.collection("following")) { Social.setFollowing(me, it, false) } }
            .continueWithTask {
                each(profile.collection("followers")) { who ->
                    Tasks.whenAllComplete(
                        db.collection("users").document(who).collection("following").document(me).delete(),
                        profile.collection("followers").document(who).delete(),
                    )
                }
            }
            .continueWithTask { gone(profile.collection("notices")) }
            // The username as the account's own profile has it (the phone's copy may be another account's).
            .continueWithTask { profile.get() }
            .continueWithTask { t ->
                val username = if (t.isSuccessful) t.result.getString("username").orEmpty() else ""
                Tasks.whenAllComplete(listOfNotNull(
                    if (username.isNotEmpty()) db.collection("usernames").document(username).delete() else null,
                    FirebaseStorage.getInstance().reference.child("users").child(me).child("profile").child("avatar.jpg").delete(),
                ))
            }
            .continueWithTask { profile.delete() }
    }

    /** Runs [action] on every document id the query finds; failures of single items are skipped. */
    private fun each(query: Query, action: (String) -> Task<*>): Task<Void> =
        query.get().continueWithTask { t ->
            if (!t.isSuccessful) throw t.exception!!
            Tasks.whenAllComplete(t.result.documents.map { action(it.id) })
        }.continueWith { null }

    private fun gone(query: Query): Task<Void> =
        query.get().continueWithTask { t ->
            if (!t.isSuccessful) throw t.exception!!
            Tasks.whenAllComplete(t.result.documents.map { it.reference.delete() })
        }.continueWith { null }

    private fun unlike(collection: String, me: String): Task<Void> = each(
        FirebaseFirestore.getInstance().collection(collection)
            .whereEqualTo("visibility", FirebaseHelper.VISIBILITY_PUBLIC).whereArrayContains("likedBy", me)
    ) { FirebaseHelper.setLiked(collection, it, me, false) }

    /** Clears what this phone kept about the account (the profile, post links, Inbox state). */
    private fun forget(app: Context) {
        for (prefs in listOf("profile", "posts", "notices", "inbox")) {
            app.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit().clear().apply()
        }
        Social.avatarFile(app)?.delete()
    }
}

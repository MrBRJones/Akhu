package com.MrBRos.akhu

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The accounts this phone can switch between without signing in again, as on Instagram. Each keeps
 * its own sign-in in a named [FirebaseApp] ("account-<uid>"), which Firebase saves on the phone;
 * switching copies that user onto the app's main sign-in. No password is kept: an account is added
 * by signing in once, and one whose sign-in has lapsed asks for it again.
 *
 * What the phone keeps for the signed-in account (its profile, post links, Inbox state and picture)
 * is set aside per account on a switch and brought back on switching back, see [Social.claim].
 */
object Accounts {

    class Saved(val uid: String, val email: String, val name: String)

    /** The account's sign-in has lapsed (or was never kept); sign in to it again. */
    class SignInAgain(val email: String) : Exception("sign in again")

    private const val PREFS = "accounts"
    private const val KEY_LIST = "list"
    private const val APP_PREFIX = "account-"
    /** Per-account local state that [stash] sets aside; the same stores [Social.claim] clears. */
    private val STORES = listOf("profile", "posts", "notices", "inbox", "likes")

    /** Saved accounts, in the order they were added. */
    @JvmStatic
    fun list(context: Context): List<Saved> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LIST, null) ?: return emptyList()
        val arr = try {
            JSONArray(raw)
        } catch (e: org.json.JSONException) {
            return emptyList()
        }
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Saved(it.optString("uid"), it.optString("email"), it.optString("name")) }
        }.filter { it.uid.isNotEmpty() }
    }

    private fun write(context: Context, accounts: List<Saved>) {
        val arr = JSONArray()
        for (a in accounts) arr.put(JSONObject().put("uid", a.uid).put("email", a.email).put("name", a.name))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private fun auth(context: Context, uid: String): FirebaseAuth {
        val name = APP_PREFIX + uid
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == name }
            ?: FirebaseApp.initializeApp(context.applicationContext, FirebaseApp.getInstance().options, name)
        return FirebaseAuth.getInstance(app)
    }

    /**
     * Keeps whoever is signed in now, so they can be switched back to, under their profile name.
     * Call after [Social.claim]; safe to call often.
     */
    @JvmStatic
    fun rememberCurrent(context: Context) {
        val app = context.applicationContext
        val user = FirebaseHelper.currentUser(app) ?: return
        val name = Social.local(app).name.ifEmpty { FirebaseHelper.displayNameOf(user) }
        val kept = auth(app, user.uid)
        if (kept.currentUser?.uid != user.uid) kept.updateCurrentUser(user)
        val all = list(app).toMutableList()
        val entry = Saved(user.uid, user.email ?: "", name)
        val i = all.indexOfFirst { it.uid == user.uid }
        if (i < 0) all.add(entry) else if (all[i].name != name || all[i].email != entry.email) all[i] = entry else return
        write(app, all)
    }

    /** Makes [uid] the signed-in account; fails with [SignInAgain] if its sign-in wasn't kept. */
    @JvmStatic
    fun switchTo(context: Context, uid: String): Task<Void> {
        val app = context.applicationContext
        val saved = list(app).firstOrNull { it.uid == uid }
        val user = auth(app, uid).currentUser
            ?: return Tasks.forException(SignInAgain(saved?.email ?: ""))
        return FirebaseAuth.getInstance().updateCurrentUser(user)
            .continueWithTask { t ->
                if (!t.isSuccessful) throw SignInAgain(saved?.email ?: "")
                Social.claim(app)
                Tasks.forResult<Void>(null)
            }
    }

    /** Takes [uid] off this phone: its kept sign-in, its entry and its set-aside state. */
    @JvmStatic
    fun forget(context: Context, uid: String) {
        val app = context.applicationContext
        if (FirebaseApp.getApps(app).any { it.name == APP_PREFIX + uid }) auth(app, uid).signOut()
        write(app, list(app).filter { it.uid != uid })
        for (store in STORES) app.getSharedPreferences(stashName(uid, store), Context.MODE_PRIVATE).edit().clear().apply()
        stashedAvatar(app, uid).delete()
    }

    // ---- What's saved on this phone belongs to the account that made it ----

    private const val OWNERS = "account_owners"
    private const val KEY_HISTORY = "history"

    /** Notes that [uid] ("" when signed out) is the account from now on, so what's saved next is theirs. */
    @JvmStatic
    fun noteActive(context: Context, uid: String) {
        val app = context.applicationContext
        val history = history(app)
        val last = history.optJSONArray(history.length() - 1)
        if (last != null && last.optString(1) == uid) return
        history.put(JSONArray().put(System.currentTimeMillis()).put(uid))
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_HISTORY, history.toString()).apply()
    }

    private fun history(app: Context): JSONArray = try {
        JSONArray(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HISTORY, null) ?: "[]")
    } catch (e: org.json.JSONException) {
        JSONArray()
    }

    /**
     * Whether something saved on this phone ([key], last changed at [time]) belongs to the account
     * signed in now. The first time it's asked, it's given to whoever was signed in at [time];
     * anything from before accounts were tracked belongs to whoever was signed in when tracking
     * began (the phone's one account until then).
     */
    @JvmStatic
    fun isMine(context: Context, key: String, time: Long): Boolean {
        val app = context.applicationContext
        val me = FirebaseHelper.currentUser(app)?.uid ?: ""
        val owners = app.getSharedPreferences(OWNERS, Context.MODE_PRIVATE)
        val owner = owners.getString(key, null) ?: ownerAt(app, time).also { owners.edit().putString(key, it).apply() }
        return owner == me
    }

    private fun ownerAt(app: Context, time: Long): String {
        val history = history(app)
        var owner: String? = null
        for (i in 0 until history.length()) {
            val e = history.optJSONArray(i) ?: continue
            if (e.optLong(0) <= time) owner = e.optString(1)
        }
        return owner ?: history.optJSONArray(0)?.optString(1) ?: FirebaseHelper.currentUser(app)?.uid ?: ""
    }

    private fun stashName(uid: String, store: String) = "account_${uid}_$store"

    private fun stashedAvatar(app: Context, uid: String) = File(File(File(app.filesDir, "accounts"), uid), "avatar.jpg")

    /** Sets aside what the phone kept for [uid], before another account's state replaces it. */
    internal fun stash(app: Context, uid: String) {
        for (store in STORES) copy(app, store, stashName(uid, store))
        val avatar = Social.avatarFile(app)
        val dest = stashedAvatar(app, uid)
        if (avatar != null) {
            dest.parentFile?.mkdirs()
            avatar.copyTo(dest, overwrite = true)
        } else {
            dest.delete()
        }
    }

    /** Brings back what was set aside for [uid], if anything. */
    internal fun unstash(app: Context, uid: String) {
        for (store in STORES) {
            if (app.getSharedPreferences(stashName(uid, store), Context.MODE_PRIVATE).all.isNotEmpty()) {
                copy(app, stashName(uid, store), store)
            }
        }
        val saved = stashedAvatar(app, uid)
        if (saved.exists()) {
            val dest = File(File(app.filesDir, "profile"), "avatar.jpg")
            dest.parentFile?.mkdirs()
            saved.copyTo(dest, overwrite = true)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun copy(app: Context, from: String, to: String) {
        val out = app.getSharedPreferences(to, Context.MODE_PRIVATE).edit().clear()
        for ((k, v) in app.getSharedPreferences(from, Context.MODE_PRIVATE).all) {
            when (v) {
                is String -> out.putString(k, v)
                is Int -> out.putInt(k, v)
                is Long -> out.putLong(k, v)
                is Float -> out.putFloat(k, v)
                is Boolean -> out.putBoolean(k, v)
                is Set<*> -> out.putStringSet(k, v as Set<String>)
            }
        }
        out.commit()
    }
}

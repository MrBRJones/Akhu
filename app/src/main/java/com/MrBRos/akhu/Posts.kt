package com.MrBRos.akhu

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage

/**
 * Remembers which cloud post each of the user's own wallpapers became (a saved picture, 3D image,
 * motion video or theme), so deleting it on this phone deletes the post too and it leaves
 * everyone's Wallpapers tab. Kept on the phone: key (file path or theme id) -> "collection/docId".
 */
object Posts {

    private const val PREFS = "posts"
    private const val TAG = "Posts"

    @JvmStatic
    fun link(context: Context, key: String?, collection: String, docId: String) {
        if (key == null) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, "$collection/$docId").apply()
    }

    /** Deletes the post made from [key], if there is one. Safe to call for anything. */
    @JvmStatic
    fun deleteFor(context: Context, key: String?) {
        if (key == null) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ref = prefs.getString(key, null) ?: return
        prefs.edit().remove(key).apply()
        val slash = ref.indexOf('/')
        delete(ref.substring(0, slash), ref.substring(slash + 1))
    }

    /**
     * Deletes a post and the files it uploaded (picture, thumbnail and any 3D layers), and forgets
     * any local file linked to it. The Firestore rules let only the poster do this.
     */
    @JvmStatic
    fun delete(context: Context, collection: String, docId: String): Task<Void> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val gone = prefs.all.filterValues { it == "$collection/$docId" }.keys
        if (gone.isNotEmpty()) prefs.edit().apply { gone.forEach { remove(it) } }.apply()
        return delete(collection, docId)
    }

    private fun delete(collection: String, docId: String): Task<Void> {
        val doc = FirebaseFirestore.getInstance().collection(collection).document(docId)
        return doc.get().continueWithTask { t ->
            val d = t.result
            if (d == null || !d.exists()) return@continueWithTask Tasks.forResult<Void>(null)
            val urls = listOfNotNull(d.getString("downloadUrl"), d.getString("thumbnailUrl")) +
                ((d.get("layers") as? List<*>)?.filterIsInstance<String>() ?: emptyList())
            doc.delete().addOnSuccessListener {
                val storage = FirebaseStorage.getInstance()
                for (u in urls) {
                    try {
                        storage.getReferenceFromUrl(u).delete()
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "Not a storage URL: $u")
                    }
                }
            }
        }
    }
}

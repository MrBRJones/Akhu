package com.MrBRos.akhu

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Someone else's profile: their picture, name, counts and bio, a Follow button and their public posts. */
class UserProfileActivity : AppCompatActivity() {

    private lateinit var uid: String
    private var profile by mutableStateOf(Profile())
    private var fallbackName by mutableStateOf("")
    private var counts by mutableStateOf<LongArray?>(null)
    /** null while unknown, or for your own profile (no button). */
    private var following by mutableStateOf<Boolean?>(null)
    private var busy by mutableStateOf(false)
    private var loaded by mutableStateOf(false)
    private val posts = mutableStateListOf<WallpaperCard>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uid = intent.getStringExtra(EXTRA_UID) ?: return finish()
        if (!FirebaseHelper.isAvailable(this)) return finish()
        fallbackName = intent.getStringExtra(EXTRA_NAME) ?: ""
        setContent {
            AkhuTheme {
                UserProfileScreen(
                    profile, fallbackName, counts, following, busy, loaded, posts,
                    onBack = ::finish,
                    onFollow = ::toggleFollow,
                    onOpen = { card ->
                        startActivity(Intent(this, WallpaperDetailActivity::class.java)
                            .putExtra(WallpaperDetailActivity.EXTRA_DOC_ID, card.cloudId))
                    },
                )
            }
        }
        load()
    }

    private fun load() {
        Social.load(uid).addOnSuccessListener(this) { profile = it }
        Social.counts(uid, mine = false).addOnSuccessListener(this) { counts = it }
        val me = FirebaseHelper.currentUser(this)?.uid
        if (me != null && me != uid) {
            Social.isFollowing(me, uid).addOnSuccessListener(this) { following = it }
        } else if (me == null) {
            following = false
        }
        FirebaseHelper.userPublicQuery(uid).get().addOnCompleteListener(this) { t ->
            loaded = true
            if (!t.isSuccessful) return@addOnCompleteListener
            posts.clear()
            posts.addAll(t.result.documents.map { WallpaperCard.cloud(CloudWallpaper.from(it), null) })
        }
    }

    private fun toggleFollow() {
        val me = FirebaseHelper.currentUser(this)?.uid
        if (me == null) {
            Toast.makeText(this, R.string.sign_in_to_follow, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, AuthActivity::class.java))
            return
        }
        val on = following != true
        busy = true
        Social.setFollowing(me, uid, on).addOnCompleteListener(this) { t ->
            busy = false
            if (t.isSuccessful) {
                following = on
                counts = counts?.let { c -> longArrayOf(c[0], c[1] + if (on) 1 else -1, c[2]) }
            } else {
                Toast.makeText(this, getString(R.string.save_failed) + ": " + t.exception?.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val EXTRA_UID = "uid"
        private const val EXTRA_NAME = "name"

        @JvmStatic
        fun intent(context: Context, uid: String, name: String?): Intent =
            Intent(context, UserProfileActivity::class.java).putExtra(EXTRA_UID, uid).putExtra(EXTRA_NAME, name)
    }
}

@Composable
private fun UserProfileScreen(
    profile: Profile,
    fallbackName: String,
    counts: LongArray?,
    following: Boolean?,
    busy: Boolean,
    loaded: Boolean,
    posts: List<WallpaperCard>,
    onBack: () -> Unit,
    onFollow: () -> Unit,
    onOpen: (WallpaperCard) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val columns = maxOf(3, LocalConfiguration.current.screenWidthDp / 150)
    Column(Modifier.fillMaxSize()) {
        AkhuTopBar(profile.username.ifEmpty { profile.name.ifEmpty { fallbackName } }, onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.xl)) {
            item(key = "header") {
                Column(Modifier.padding(horizontal = Space.screen).fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(profile.photoUrl, 86.dp)
                        Column(Modifier.padding(start = Space.l).weight(1f)) {
                            Text(
                                profile.name.ifEmpty { fallbackName }, color = colors.onSurface,
                                style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Row(Modifier.padding(top = Space.s).fillMaxWidth()) {
                                listOf(R.string.posts, R.string.followers, R.string.following).forEachIndexed { i, label ->
                                    Column(Modifier.weight(1f)) {
                                        Text(counts?.get(i)?.toString() ?: "–", color = colors.onSurface,
                                            style = MaterialTheme.typography.titleMedium)
                                        Text(stringResource(label), color = colors.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                    if (profile.pronouns.isNotEmpty()) {
                        Text(profile.pronouns, Modifier.padding(top = Space.m), color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    if (profile.bio.isNotEmpty()) {
                        Text(profile.bio, Modifier.padding(top = Space.xs), color = colors.onSurface,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    if (following != null) {
                        // Follow is the bright button; once following it turns grey, as on Instagram.
                        Button(
                            onClick = onFollow, enabled = !busy, shape = MaterialTheme.shapes.small,
                            colors = if (following) ButtonDefaults.buttonColors(
                                containerColor = colors.surfaceContainer, contentColor = colors.onSurface,
                            ) else ButtonDefaults.buttonColors(),
                            modifier = Modifier.padding(top = Space.l).fillMaxWidth().height(40.dp),
                        ) {
                            Text(stringResource(if (following) R.string.unfollow else R.string.follow),
                                style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            item(key = "gap") { androidx.compose.foundation.layout.Spacer(Modifier.height(Space.l)) }
            if (loaded && posts.isEmpty()) item(key = "empty") { EmptyState(stringResource(R.string.gallery_empty)) }
            cardRows(posts, columns, inset = 2.dp, key = ::cardKey) { card ->
                WallpaperTile(card, null, 0) { onOpen(card) }
            }
        }
    }
}

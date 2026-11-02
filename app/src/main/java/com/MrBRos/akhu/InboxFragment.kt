package com.MrBRos.akhu

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot

/** One like, comment or share someone else left on the user's post. */
private data class InboxItem(
    val key: String,
    val uid: String,
    val name: String,
    val photo: String?,
    /** The comment, or null for a like. */
    val comment: String?,
    val postId: String,
    val thumb: String?,
    val theme: Boolean,
    /** When it happened, in ms; 0 for likes from before notices were kept. */
    val time: Long,
    val shared: Boolean = false,
    /** The notice behind it, deleted when the row is swiped away. */
    val notice: String? = null,
)

/**
 * Inbox tab: likes, comments and shares other people left on the user's posts and themes, newest
 * first. Likes and comments come from the posts themselves; shares, and the time of each like, from
 * the user's [Notices]. Old likes without a notice follow. Drawn with Compose.
 */
class InboxFragment : Fragment() {

    private val items = mutableStateListOf<InboxItem>()
    private var refreshing by mutableStateOf(false)
    private var empty by mutableStateOf<Int?>(null)
    private var loadGeneration = 0
    /** Where each row is on screen, so a sideways swipe on one deletes it instead of changing tab. */
    private val rowBounds = HashMap<String, Rect>()

    /** True when (x, y), in window pixels, is on an Inbox row. */
    fun hasRowAt(x: Float, y: Float): Boolean = !isHidden && rowBounds.values.any { it.contains(androidx.compose.ui.geometry.Offset(x, y)) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AkhuTheme {
                    InboxScreen(items, refreshing, empty, ::load, ::open, ::openProfile, ::dismiss, rowBounds)
                }
            }
        }

    override fun onResume() {
        super.onResume()
        if (!isHidden) load()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && view != null) load()
    }

    private fun load() {
        if (view == null) return
        val gen = ++loadGeneration
        val context = requireContext()
        val user = if (FirebaseHelper.isAvailable(context)) FirebaseHelper.currentUser(context) else null
        if (user == null) {
            show(gen, emptyList(), R.string.inbox_signed_out)
            return
        }
        val me = user.uid
        refreshing = true
        val db = FirebaseFirestore.getInstance()
        val mine = FirebaseHelper.mineQuery(me).get()
        val myThemes = db.collection(FirebaseHelper.THEMES).whereEqualTo("uid", me).limit(POSTS).get()
        val notices = Notices.recent(me, NOTICES)
        Tasks.whenAllComplete(mine, myThemes, notices).continueWithTask {
            val posts = if (mine.isSuccessful) mine.result.documents else emptyList()
            val themes = if (myThemes.isSuccessful) myThemes.result.documents else emptyList()
            val comments = posts.map { p ->
                p.reference.collection("comments").orderBy("createdAt", Query.Direction.DESCENDING).limit(COMMENTS).get()
            }
            Tasks.whenAllComplete(comments).continueWithTask {
                val found = ArrayList<InboxItem>()
                posts.forEachIndexed { i, p ->
                    if (comments[i].isSuccessful) found.addAll(commentsOn(p, comments[i].result, me))
                    found.addAll(likesOn(p, me, theme = false))
                }
                themes.forEach { found.addAll(likesOn(it, me, theme = true)) }
                if (notices.isSuccessful) withNotices(found, notices.result)
                withProfiles(found)
            }
        }.addOnCompleteListener(requireActivity()) { t ->
            if (gen != loadGeneration || view == null) return@addOnCompleteListener
            val list = if (t.isSuccessful) t.result else emptyList()
            show(gen, list.sortedByDescending { it.time }, if (list.isEmpty()) R.string.inbox_empty else null)
        }
    }

    private fun commentsOn(post: DocumentSnapshot, comments: QuerySnapshot, me: String): List<InboxItem> =
        comments.documents.mapNotNull { c ->
            val uid = c.getString("uid") ?: return@mapNotNull null
            if (uid == me) return@mapNotNull null
            InboxItem(
                "c:" + post.id + ":" + c.id, uid, c.getString("name") ?: "", null, c.getString("text") ?: "",
                post.id, post.getString("thumbnailUrl"), false, c.getTimestamp("createdAt")?.toDate()?.time ?: 0L,
            )
        }

    private fun likesOn(post: DocumentSnapshot, me: String, theme: Boolean): List<InboxItem> =
        CloudWallpaper.from(post).likedBy.filter { it != me }.map { uid ->
            InboxItem("l:" + post.id + ":" + uid, uid, "", null, null, post.id, post.getString("thumbnailUrl"), theme, 0L)
        }

    /** Dates the likes that have a notice, and adds shares and likes on posts not loaded here. */
    private fun withNotices(found: ArrayList<InboxItem>, notices: List<Notice>) {
        for (n in notices) {
            val theme = n.collection == FirebaseHelper.THEMES
            when (n.type) {
                Notices.SHARE -> found.add(
                    InboxItem("s:" + n.id, n.from, n.name, null, null, n.post, n.thumb, theme, n.time, shared = true, notice = n.id)
                )
                Notices.LIKE -> {
                    val key = "l:" + n.post + ":" + n.from
                    val at = found.indexOfFirst { it.key == key }
                    if (at >= 0) found[at] = found[at].copy(time = n.time, name = found[at].name.ifEmpty { n.name }, notice = n.id)
                }
            }
        }
    }

    /** Fills in each person's @username and picture. */
    private fun withProfiles(found: List<InboxItem>): Task<List<InboxItem>> {
        val uids = found.map { it.uid }.distinct().take(PROFILES)
        val loads = uids.map { Social.load(it) }
        return Tasks.whenAllComplete(loads).continueWith {
            val byUid = HashMap<String, Profile>()
            uids.forEachIndexed { i, uid -> if (loads[i].isSuccessful) byUid[uid] = loads[i].result }
            found.map { item ->
                val p = byUid[item.uid] ?: return@map item
                val name = if (p.username.isNotEmpty()) "@" + p.username else p.name.ifEmpty { item.name }
                item.copy(name = name, photo = p.photoUrl)
            }
        }
    }

    private fun show(gen: Int, list: List<InboxItem>, emptyText: Int?) {
        if (gen != loadGeneration || view == null) return
        refreshing = false
        val hidden = hidden()
        val shown = list.filter { it.key !in hidden }
        items.clear()
        items.addAll(shown)
        empty = if (shown.isEmpty() && list.isNotEmpty()) R.string.inbox_empty else emptyText
    }

    /**
     * Swiped away: gone from the Inbox for good. Likes and comments stay on the post (they're
     * other people's); the row is remembered as hidden, and its notice, if any, is deleted.
     */
    private fun dismiss(item: InboxItem) {
        items.remove(item)
        if (items.isEmpty()) empty = R.string.inbox_empty
        val prefs = requireContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_HIDDEN, hidden() + item.key).apply()
        val me = FirebaseHelper.currentUser(requireContext()) ?: return
        item.notice?.let { Notices.delete(me.uid, it) }
    }

    private fun hidden(): Set<String> =
        requireContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getStringSet(KEY_HIDDEN, null)?.toSet() ?: emptySet()

    /** A post opens on its own screen; a theme like opens the person's profile. */
    private fun open(item: InboxItem) {
        if (item.theme) {
            openProfile(item)
            return
        }
        startActivity(Intent(requireContext(), WallpaperDetailActivity::class.java)
            .putExtra(WallpaperDetailActivity.EXTRA_DOC_ID, item.postId))
    }

    private fun openProfile(item: InboxItem) {
        startActivity(UserProfileActivity.intent(requireContext(), item.uid, item.name))
    }

    private companion object {
        const val POSTS = 50L
        const val COMMENTS = 20L
        const val PROFILES = 60
        const val NOTICES = 100L
        const val PREFS = "inbox"
        const val KEY_HIDDEN = "hidden"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboxScreen(
    items: List<InboxItem>,
    refreshing: Boolean,
    empty: Int?,
    onRefresh: () -> Unit,
    onOpen: (InboxItem) -> Unit,
    onProfile: (InboxItem) -> Unit,
    onDismiss: (InboxItem) -> Unit,
    rowBounds: MutableMap<String, Rect>,
) {
    PullToRefreshBox(refreshing, onRefresh, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = Space.xs, bottom = Space.xl)) {
            if (empty != null) item(key = "empty") { EmptyState(stringResource(empty)) }
            items(items, key = { it.key }) { item -> SwipeAway(item, onDismiss, rowBounds) { InboxRow(item, onOpen, onProfile) } }
        }
    }
}

/** Either way swipes the row off, over a red strip with a bin. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeAway(item: InboxItem, onDismiss: (InboxItem) -> Unit, rowBounds: MutableMap<String, Rect>, content: @Composable () -> Unit) {
    DisposableEffect(item.key) { onDispose { rowBounds.remove(item.key) } }
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value != SwipeToDismissBoxValue.Settled) onDismiss(item)
        value != SwipeToDismissBoxValue.Settled
    })
    val colors = MaterialTheme.colorScheme
    SwipeToDismissBox(state, modifier = Modifier.onGloballyPositioned { rowBounds[item.key] = it.boundsInWindow() }, backgroundContent = {
        val start = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
        Box(
            Modifier.fillMaxSize().background(colors.errorContainer).padding(horizontal = Space.xl),
            contentAlignment = if (start) Alignment.CenterStart else Alignment.CenterEnd,
        ) {
            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.delete), tint = colors.onErrorContainer)
        }
    }) {
        Box(Modifier.background(colors.background)) { content() }
    }
}

@Composable
private fun InboxRow(item: InboxItem, onOpen: (InboxItem) -> Unit, onProfile: (InboxItem) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(item) }.padding(horizontal = Space.l, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Avatar(item.photo, 44.dp, Modifier.clickable { onProfile(item) })
        Column(Modifier.weight(1f)) {
            Text(item.name, color = colors.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            val what = when {
                item.comment != null -> stringResource(R.string.inbox_commented, item.comment)
                item.shared -> stringResource(if (item.theme) R.string.inbox_shared_theme else R.string.inbox_shared)
                item.theme -> stringResource(R.string.inbox_liked_theme)
                else -> stringResource(R.string.inbox_liked)
            }
            Text(what, color = colors.onSurface, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.time > 0) {
                Text(
                    if (System.currentTimeMillis() - item.time < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.inbox_just_now)
                    else DateUtils.getRelativeTimeSpanString(item.time).toString(),
                    color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (item.thumb != null) {
            AndroidView(
                factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
                modifier = Modifier.size(width = 40.dp, height = 56.dp).clip(RoundedCornerShape(6.dp)),
                update = { iv -> Glide.with(iv).load(item.thumb).into(iv) },
            )
        }
    }
}

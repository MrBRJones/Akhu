package com.MrBRos.akhu

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.gms.tasks.Tasks
import java.util.Locale

/**
 * Someone's followers and who they follow, as on Instagram: two tabs with their counts, a search
 * bar, and a row per person (tap for their profile). On your own lists each row has a button:
 * Remove for a follower, Following (tap to unfollow) for someone you follow.
 */
class FollowListActivity : AppCompatActivity() {

    /** One person in a list. */
    class Person(val uid: String, val profile: Profile)

    private lateinit var uid: String
    private var mine = false
    private var tab by mutableIntStateOf(FOLLOWERS)
    private var query by mutableStateOf("")
    private val followers = mutableStateListOf<Person>()
    private val following = mutableStateListOf<Person>()
    private var loaded by mutableStateOf(false)
    /** Whom you've unfollowed or removed from here, until the list is next opened. */
    private val gone = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uid = intent.getStringExtra(EXTRA_UID) ?: return finish()
        if (!FirebaseHelper.isAvailable(this)) return finish()
        tab = intent.getIntExtra(EXTRA_TAB, FOLLOWERS)
        mine = FirebaseHelper.currentUser(this)?.uid == uid
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        setContent {
            AkhuTheme {
                FollowListScreen(
                    title, tab, { tab = it }, query, { query = it },
                    followers.size, following.size, shown(), loaded, mine,
                    onBack = ::finish,
                    onOpen = { startActivity(UserProfileActivity.intent(this, it.uid, it.profile.name)) },
                    onAction = ::act,
                )
            }
        }
        load()
    }

    /** The open tab's people that match the search. */
    private fun shown(): List<Person> {
        val q = query.trim().removePrefix("@").lowercase(Locale.ROOT)
        return (if (tab == FOLLOWERS) followers else following).filter { p ->
            q.isEmpty() || p.profile.username.lowercase(Locale.ROOT).contains(q) || p.profile.name.lowercase(Locale.ROOT).contains(q)
        }
    }

    private fun load() {
        Tasks.whenAllComplete(Social.followerIds(uid), Social.followingIds(uid)).addOnCompleteListener(this) { done ->
            val lists = done.result.map { t -> if (t.isSuccessful) @Suppress("UNCHECKED_CAST") (t.result as List<String>) else emptyList() }
            val ids = (lists[0] + lists[1]).distinct()
            val profiles = ids.map { Social.load(it) }
            Tasks.whenAllComplete(profiles).addOnCompleteListener(this) {
                val byId = ids.zip(profiles).associate { (id, t) -> id to (if (t.isSuccessful) t.result else Profile()) }
                followers.clear()
                followers.addAll(lists[0].map { Person(it, byId[it] ?: Profile()) })
                following.clear()
                following.addAll(lists[1].map { Person(it, byId[it] ?: Profile()) })
                loaded = true
            }
            if (ids.isEmpty()) loaded = true
        }
    }

    /** Your own lists: removes a follower, or unfollows / follows again someone you follow. */
    private fun act(person: Person) {
        val me = FirebaseHelper.currentUser(this)?.uid ?: return
        val task = if (tab == FOLLOWERS) Social.removeFollower(me, person.uid)
        else Social.setFollowing(me, person.uid, person.uid in gone)
        val wasGone = person.uid in gone
        if (tab == FOLLOWERS) {
            gone.add(person.uid)
            followers.remove(person)
        } else if (wasGone) gone.remove(person.uid) else gone.add(person.uid)
        task.addOnFailureListener(this) {
            Toast.makeText(this, R.string.follow_failed, Toast.LENGTH_SHORT).show()
            if (tab == FOLLOWERS) {
                gone.remove(person.uid)
                load()
            } else if (wasGone) gone.add(person.uid) else gone.remove(person.uid)
        }
    }

    /** Whether you still follow [person] (for the Following tab's button). */
    internal fun stillFollowing(person: Person) = person.uid !in gone

    companion object {
        const val FOLLOWERS = 0
        const val FOLLOWING = 1
        private const val EXTRA_UID = "uid"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TAB = "tab"

        @JvmStatic
        fun intent(context: Context, uid: String, title: String, tab: Int): Intent =
            Intent(context, FollowListActivity::class.java)
                .putExtra(EXTRA_UID, uid).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TAB, tab)
    }
}

@Composable
private fun FollowListScreen(
    title: String,
    tab: Int,
    onTab: (Int) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    followerCount: Int,
    followingCount: Int,
    people: List<FollowListActivity.Person>,
    loaded: Boolean,
    mine: Boolean,
    onBack: () -> Unit,
    onOpen: (FollowListActivity.Person) -> Unit,
    onAction: (FollowListActivity.Person) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val activity = androidx.compose.ui.platform.LocalContext.current as? FollowListActivity
    Column(Modifier.fillMaxSize()) {
        AkhuTopBar(title, onBack)
        TabRow(
            selectedTabIndex = tab, containerColor = colors.surface, contentColor = colors.onSurface,
            indicator = { pos ->
                TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(pos[tab]), color = colors.onSurface)
            },
        ) {
            listOf(
                pluralStringResource(R.plurals.followers_count, followerCount, followerCount),
                stringResource(R.string.following_count, followingCount),
            ).forEachIndexed { i, label ->
                Tab(
                    selected = tab == i, onClick = { onTab(i) },
                    text = { Text(label, style = MaterialTheme.typography.titleSmall) },
                    unselectedContentColor = colors.onSurfaceVariant,
                )
            }
        }
        TextField(
            value = query, onValueChange = onQuery, singleLine = true,
            placeholder = { Text(stringResource(R.string.search_stickers)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null, Modifier.size(20.dp)) },
            shape = MaterialTheme.shapes.medium,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = colors.surfaceContainer, unfocusedContainerColor = colors.surfaceContainer,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.m).fillMaxWidth(),
        )
        if (loaded && people.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(Space.xl), contentAlignment = Alignment.TopCenter) {
                Text(
                    stringResource(if (query.isNotBlank()) R.string.follow_list_no_match
                    else if (tab == FollowListActivity.FOLLOWERS) R.string.followers_empty else R.string.following_none),
                    color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.xl)) {
            items(people, key = { it.uid }) { person ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(person) }
                        .padding(horizontal = Space.screen, vertical = Space.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(person.profile.photoUrl, 52.dp)
                    Column(Modifier.padding(start = Space.m).weight(1f)) {
                        val p = person.profile
                        Text(
                            p.username.ifEmpty { p.name.ifEmpty { stringResource(R.string.akhu_user) } },
                            color = colors.onSurface, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (p.username.isNotEmpty() && p.name.isNotEmpty()) {
                            Text(p.name, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (mine) {
                        val follows = tab == FollowListActivity.FOLLOWING && activity?.stillFollowing(person) != false
                        // Remove and Following are grey, as on Instagram; Follow (after unfollowing) is bright.
                        Button(
                            onClick = { onAction(person) }, shape = MaterialTheme.shapes.small,
                            colors = if (tab == FollowListActivity.FOLLOWERS || follows) ButtonDefaults.buttonColors(
                                containerColor = colors.surfaceContainer, contentColor = colors.onSurface,
                            ) else ButtonDefaults.buttonColors(),
                            contentPadding = PaddingValues(horizontal = Space.l),
                            modifier = Modifier.height(36.dp),
                        ) {
                            Text(
                                stringResource(when {
                                    tab == FollowListActivity.FOLLOWERS -> R.string.remove_follower
                                    follows -> R.string.unfollow
                                    else -> R.string.follow
                                }),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }
    }
}

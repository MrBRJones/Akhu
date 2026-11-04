package com.MrBRos.akhu

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment

/**
 * A grid of motion videos ([VIDEOS]), 3D motion images ([FOURD]) or reactive wallpapers ([REACT]):
 * the Motion, 3D and React sections of the Wallpapers tab. Drawn with Compose.
 */
class LiveFragment : Fragment() {

    private var tab by mutableIntStateOf(0)
    private var gridTilt: TiltSensor? = null
    private val videos = mutableStateListOf<WallpaperCard>()
    private val fourD = mutableStateListOf<WallpaperCard>()
    private val react = mutableStateListOf<WallpaperCard>()

    companion object {
        const val VIDEOS = 0
        const val FOURD = 1
        const val REACT = 2
        private const val ARG_KIND = "kind"

        @JvmStatic
        fun newInstance(kind: Int) = LiveFragment().apply { arguments = Bundle().apply { putInt(ARG_KIND, kind) } }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        tab = arguments?.getInt(ARG_KIND, VIDEOS) ?: VIDEOS
        if (videos.isEmpty()) videos.addAll(MotionWallpapers.list(context).map { WallpaperCard.motion(it) })
        // The bundled 3D images (the user's own are in Profile -> 3D), then public posts from loadPublic.
        if (fourD.isEmpty()) fourD.addAll(FourDWallpapers.list(context).filter { !FourDWallpapers.isUser(it) }.map { WallpaperCard.fourD(it) })
        // The bundled reactive ones; the user's own are in Profile -> React.
        if (react.isEmpty()) react.addAll(ReactWallpapers.bundled(context).map { WallpaperCard.react(it) })
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AkhuTheme {
                    MotionScreen(tab, when (tab) { FOURD -> fourD; REACT -> react; else -> videos }, ::open)
                }
            }
        }
    }

    /** Tapping a tile opens the swipeable full-screen preview over that tab's items. */
    private fun open(card: WallpaperCard) {
        if (card.isCloud) {
            // A post's card doesn't know its kind, so swipe through whichever tab it is in.
            val tab = listOf(fourD, react, videos).firstOrNull { card in it } ?: videos
            startActivity(WallpaperDetailActivity.intent(requireContext(), card.cloudId, tab.filter { it.isCloud }.mapNotNull { it.cloudId }))
            return
        }
        val items = when {
            card.isFourD -> fourD.filter { !it.isCloud }
            card.isReact -> react.filter { !it.isCloud }
            else -> videos.filter { !it.isCloud }
        }
        val prefix = when {
            card.isFourD -> PreviewActivity.FOURD_PREFIX
            card.isReact -> PreviewActivity.REACT_PREFIX
            else -> PreviewActivity.VIDEO_PREFIX
        }
        val sources = ArrayList(items.map { prefix + it.assetPath })
        val labels = ArrayList(items.map { previewLabel(requireContext(), it) })
        startActivity(PreviewActivity.intent(requireContext(), sources, items.indexOf(card), labels))
    }

    /** Adds the public motion videos, 3D images and reactive wallpapers users have posted after the bundled ones. */
    private fun loadPublic() {
        val context = context ?: return
        if (!FirebaseHelper.isAvailable(context)) return
        val uid = FirebaseHelper.currentUser(context)?.uid
        FirebaseHelper.publicQuery(null, true, FirebaseHelper.MEDIA_VIDEO, FirebaseHelper.MEDIA_FOURD, FirebaseHelper.MEDIA_REACT).get().addOnCompleteListener(requireActivity()) { t ->
            if (view == null || !t.isSuccessful) return@addOnCompleteListener
            val cloud = t.result.documents.map { CloudWallpaper.from(it) }.filter { it.isVideo || it.isFourD || it.isReact }
            cloud.forEach { Likes.sync(context, Likes.CLOUD + it.id, it.isLikedBy(uid)) }
            videos.removeAll { it.isCloud }
            videos.addAll(cloud.filter { it.isVideo }.map { WallpaperCard.cloud(it, null) })
            fourD.removeAll { it.isCloud }
            fourD.addAll(cloud.filter { it.isFourD }.map { WallpaperCard.cloud(it, null) })
            react.removeAll { it.isCloud }
            react.addAll(cloud.filter { it.isReact }.map { WallpaperCard.cloud(it, null) })
        }
    }

    /**
     * The 3D grid's shared tilt sensor runs only while the 3D tab is selected and this screen is
     * actually on screen (resumed and not hidden behind another bottom-nav tab). React tiles run
     * their own.
     */
    private fun updateGridTilt() {
        val want = tab == FOURD && isResumed && !isHidden && FourDTileView.isSupported()
        if (want) {
            val tilt = gridTilt ?: TiltSensor(requireContext()).also { gridTilt = it }
            tilt.start()
            FourDTileView.setSharedTilt(tilt)
        } else {
            gridTilt?.let {
                it.stop()
                FourDTileView.setSharedTilt(null)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadPublic()
        updateGridTilt()
    }

    override fun onPause() {
        super.onPause()
        updateGridTilt()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        updateGridTilt()
    }
}

@Composable
private fun MotionScreen(tab: Int, cards: List<WallpaperCard>, onOpen: (WallpaperCard) -> Unit) {
    val columns = maxOf(3, LocalConfiguration.current.screenWidthDp / 180)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = Space.xs, bottom = Space.xl)) {
        if (tab == LiveFragment.FOURD || tab == LiveFragment.REACT) {
            item(key = "hint") {
                Text(
                    stringResource(if (tab == LiveFragment.REACT) R.string.react_hint else R.string.fourd_hint), Modifier.padding(start = 6.dp, end = 6.dp, top = Space.m),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item(key = "gap") { androidx.compose.foundation.layout.Spacer(Modifier.padding(top = Space.s)) }
        cardRows(cards, columns, key = { "$tab:" + cardKey(it) }) { card ->
            WallpaperTile(card, null, 0, labels = false) { onOpen(card) }
        }
    }
}

package com.MrBRos.akhu

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.min

/**
 * Double tap to like for the View-based full-screen screens: a single tap still does [onTap], a
 * double tap calls [onLike] and pops a white heart in the middle of the view (drawn on its overlay,
 * so nothing is added to the layout).
 */
object HeartPop {

    @SuppressLint("ClickableViewAccessibility")
    @JvmStatic
    fun attach(view: View, onTap: Runnable?, onLike: Runnable) {
        val detector = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onTap?.run()
                return onTap != null
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                onLike.run()
                show(view)
                return true
            }
        })
        view.setOnTouchListener { _, e -> detector.onTouchEvent(e) }
    }

    @JvmStatic
    fun show(view: View) {
        val heart = ContextCompat.getDrawable(view.context, R.drawable.ic_heart)?.mutate() ?: return
        heart.setTint(0xFFFFFFFF.toInt())
        val full = (min(view.width, view.height) * 0.28f).toInt().coerceAtLeast(1)
        view.overlay.add(heart)
        val grow = OvershootInterpolator(2.5f)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            addUpdateListener { a ->
                val t = a.animatedFraction
                // Grow in over the first third, hold, then fade over the last quarter.
                val scale = grow.getInterpolation(min(1f, t * 3f))
                val size = (full * scale).toInt()
                val cx = view.width / 2
                val cy = view.height / 2
                heart.setBounds(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
                heart.alpha = if (t < 0.75f) 255 else ((1f - (t - 0.75f) / 0.25f) * 255).toInt()
                view.invalidate()
            }
            doOnEnd { view.overlay.remove(heart) }
            start()
        }
    }

    private fun ValueAnimator.doOnEnd(block: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) = block()
    })
}

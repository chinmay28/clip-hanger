package io.github.chinmay28.cliphanger

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/**
 * The developer badge thrown up full screen for a beat — the native twin of
 * the web page's DevFlash (web/src/components/Brand.jsx), on the same clock:
 * over 3 s the veil fades in by 7%, holds, and fades out from 82%; the badge
 * lands with a small overshoot (0.82 → 1.02 at 14% → 1 at 22%) and drifts out
 * to 1.06 as the veil clears. A tap ends it early. With animations turned off
 * in Android's settings the badge does not scale, as the page honours
 * prefers-reduced-motion.
 */
object DevFlash {
    private const val DURATION_MS = 3_000L

    /** Makes [badge] throw the flash when tapped. */
    fun attach(badge: View) {
        badge.setOnClickListener { show(it.context) }
    }

    fun show(context: Context) {
        val dialog = Dialog(context, R.style.Theme_ClipHanger_DevFlash)
        val density = context.resources.displayMetrics.density
        val metrics = context.resources.displayMetrics
        // min(64vmin, 26rem), as on the page.
        val size = min(min(metrics.widthPixels, metrics.heightPixels) * 0.64f, 416 * density).toInt()

        val veil = FrameLayout(context).apply {
            setBackgroundColor(context.getColor(R.color.clip_veil))
            alpha = 0f
        }
        val lockup = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        lockup.addView(ImageView(context).apply {
            setImageResource(R.drawable.dev_badge_full)
            contentDescription = context.getString(R.string.dev_badge)
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL }
        }, LinearLayout.LayoutParams(size, size))
        lockup.addView(TextView(context).apply {
            text = context.getString(R.string.pref_developer_summary)
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.08f
            textSize = 16f
            setTextColor(context.getColor(R.color.clip_muted))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (22 * density).toInt()
        })
        veil.addView(lockup, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        veil.setOnClickListener { dialog.dismiss() }

        dialog.setContentView(veil)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        }

        val motion = ValueAnimator.areAnimatorsEnabled()
        val clock = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                veil.alpha = veilAt(t)
                if (motion) {
                    val s = scaleAt(t)
                    lockup.scaleX = s
                    lockup.scaleY = s
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (dialog.isShowing) dialog.dismiss()
                }
            })
        }
        dialog.setOnDismissListener { clock.cancel() }
        dialog.show()
        if (motion) {
            clock.start()
        } else {
            // No animator clock to run on: show it, and take it down on time.
            veil.alpha = 1f
            veil.postDelayed({ if (dialog.isShowing) dialog.dismiss() }, DURATION_MS)
        }
    }

    /** The veil's opacity at [t] of the way through — the page's clip-dev-veil keyframes. */
    private fun veilAt(t: Float): Float = when {
        t < 0.07f -> easeInOut(t / 0.07f)
        t < 0.82f -> 1f
        else -> 1f - easeInOut((t - 0.82f) / 0.18f)
    }

    /** The lockup's scale — the page's clip-dev-badge keyframes, eased out as its cubic-bezier is. */
    private fun scaleAt(t: Float): Float = when {
        t < 0.14f -> lerp(0.82f, 1.02f, easeOut(t / 0.14f))
        t < 0.22f -> lerp(1.02f, 1f, easeOut((t - 0.14f) / 0.08f))
        t < 0.82f -> 1f
        else -> lerp(1f, 1.06f, easeOut((t - 0.82f) / 0.18f))
    }

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
    private fun easeOut(f: Float) = 1f - (1f - f) * (1f - f) * (1f - f)
    private fun easeInOut(f: Float) = if (f < 0.5f) 2f * f * f else 1f - (-2f * f + 2f).let { it * it } / 2f
}

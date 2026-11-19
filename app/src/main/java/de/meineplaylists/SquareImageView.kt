package de.meineplaylists

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import kotlin.math.min

/** Quadratisches Cover: nimmt die kleinere der verfügbaren Seiten. */
class SquareImageView(ctx: Context, attrs: AttributeSet?) : ImageView(ctx, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val s = min(measuredWidth, measuredHeight)
        setMeasuredDimension(s, s)
    }
}

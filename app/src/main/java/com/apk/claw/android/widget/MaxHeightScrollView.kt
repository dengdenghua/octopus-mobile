package com.apk.claw.android.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/** 普通 ScrollView 的 android:maxHeight 不生效(FrameLayout.onMeasure 不读它)，这里补上。 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ScrollView(context, attrs, defStyleAttr) {

    private val maxHeightPx: Int

    init {
        val ta = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.maxHeight))
        maxHeightPx = ta.getDimensionPixelSize(0, -1)
        ta.recycle()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cappedSpec = if (maxHeightPx >= 0) {
            MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, cappedSpec)
    }
}

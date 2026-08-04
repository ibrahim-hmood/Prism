package com.prism.launcher

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import com.prism.launcher.messaging.ModelStoreView

/**
 * Custom desktop page (mirrors NebulaSocial/FileExplorer/Models — a swipeable pager slot, not a
 * Settings screen) hosting [ModelStoreView] for browsing/searching/downloading AI models.
 */
class ModelStorePageView @JvmOverloads constructor(
    context: Context, attrs: android.util.AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        addView(ModelStoreView(context), ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}

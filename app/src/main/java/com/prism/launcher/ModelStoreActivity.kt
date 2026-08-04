package com.prism.launcher

import android.os.Bundle
import android.view.ViewGroup
import com.prism.launcher.messaging.ModelStoreView

/** Thin Activity wrapper hosting [ModelStoreView] — the entry point for Settings' "Search for Models". */
class ModelStoreActivity : PrismBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val storeView = ModelStoreView(this)
        storeView.onBackRequested = { finish() }
        storeView.setBackgroundColor(resolveAttr(R.attr.prismBackground))
        setContentView(storeView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}

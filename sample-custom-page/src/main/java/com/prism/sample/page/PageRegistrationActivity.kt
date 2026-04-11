package com.prism.sample.page

import android.app.Activity
import android.os.Bundle

/**
 * Exported so Prism can discover this package as a page provider.
 * Open the Prism launcher to add this view to a slot.
 */
class PageRegistrationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}

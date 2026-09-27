package com.root.app.ui

import com.root.app.data.PackEntity

/** A phrase pack paired with its installed phrase count, as loaded for display
 *  in the Explore tab (see [ExploreScreen]) and by [com.root.app.RootViewModel]. */
data class PackRow(val pack: PackEntity, val phraseCount: Int)

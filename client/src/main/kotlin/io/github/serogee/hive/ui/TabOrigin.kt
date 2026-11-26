package io.github.serogee.hive.ui

/**
 * The tab that a screen such as a stack was opened from: the tab below it in the back stack. Home is the
 * first of [tabs] and is always at the bottom. Every other tab opens on top of Home, so at most one of
 * them is below.
 */
internal fun <T> originTab(tabs: List<T>, inBackStack: (T) -> Boolean): T = tabs.drop(1).firstOrNull(inBackStack) ?: tabs.first()

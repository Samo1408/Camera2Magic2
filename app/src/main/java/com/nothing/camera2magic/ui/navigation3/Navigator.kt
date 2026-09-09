package com.nothing.camera2magic.ui.navigation3

import androidx.compose.runtime.staticCompositionLocalOf
import top.yukonga.miuix.kmp.nav.core.NavKey

class Navigator(val backStack: MutableList<NavKey>) {
    fun push(key: NavKey) {
        backStack.add(key)
    }

    fun pop() {
        if (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
    }

    fun current() = backStack.lastOrNull()

    fun backStackSize() = backStack.size
}

val LocalNavigator = staticCompositionLocalOf<Navigator> {
    error("LocalNavigator not provided")
}

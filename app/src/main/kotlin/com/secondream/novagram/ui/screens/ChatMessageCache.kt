package com.secondream.novagram.ui.screens

import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import org.drinkless.tdlib.TdApi

/**
 * Process-wide cache of the per-chat message lists rendered by
 * [ChatScreen].
 *
 * Why this exists: Compose Navigation tears down the previous
 * destination's composable when you push a new one (MediaViewer,
 * MainSettings, etc.), and plain `remember { mutableStateListOf<...>() }`
 * state is lost in that tear-down. Popping back re-enters ChatScreen
 * with an empty messages list, which makes the initial-load
 * LaunchedEffect re-fetch history AND re-run the "scroll to first
 * unread" decision from scratch — by then unreadCount has dropped to
 * zero (we already opened the chat once) so the scroll snaps to the
 * bottom and the user is "ributtato più in basso" exactly where they
 * weren't reading.
 *
 * Caching the SnapshotStateList in a static map keyed by chatId means:
 *  - Pop back from MediaViewer → same list instance → same scroll
 *    anchor → the LazyListState's rememberSaveable position restores
 *    against the same content.
 *  - Switch to a different chat → different key → different list,
 *    fresh load (unchanged behaviour).
 *  - TDLib update collectors (UpdateMessageContent, send-succeeded,
 *    new-message, delete) can be wired to mutate the cached list even
 *    while ChatScreen is OFF SCREEN (during MediaViewer), so when the
 *    user returns the bubble already reflects the live state — no
 *    stale render then snap.
 *
 * The cache is intentionally not bounded: messages-per-chat is small
 * (50-200 typical, capped server-side by our pagination), and a Long
 * key map of SnapshotStateLists is cheap. If memory pressure becomes
 * an issue we'd add an LRU eviction in the open-chat flow.
 */
internal object ChatMessageCache {
    private val cache = mutableMapOf<Long, SnapshotStateList<TdApi.Message>>()

    /**
     * Return the list for [chatId], creating an empty one on first
     * access. Same instance across calls so a ChatScreen recomposition
     * after nav pop sees the same content it left behind.
     */
    fun forChat(chatId: Long): SnapshotStateList<TdApi.Message> =
        cache.getOrPut(chatId) { mutableStateListOf() }

    /**
     * Drop the cached list for [chatId]. Called when the user
     * explicitly leaves the chat back to the chat list (not when they
     * push to MediaViewer / a sub-screen), so a re-entry into the
     * same chat picks up the fresh server state instead of resuming
     * an old window that might be several minutes out of date.
     */
    fun evict(chatId: Long) {
        cache.remove(chatId)
    }
}

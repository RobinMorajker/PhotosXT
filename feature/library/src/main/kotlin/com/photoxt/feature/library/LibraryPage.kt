package com.photoxt.feature.library

import com.photoxt.core.designsystem.R as DesignR

/** A page the Library tab opens (spec §4.1), in its order there. */
enum class LibraryPage(val title: Int, val body: Int, val icon: Int) {
    FAVORITES(
        R.string.library_favorites,
        R.string.library_favorites_body,
        DesignR.drawable.ic_favorite
    ),
    BOOKMARKS(
        R.string.library_bookmarks,
        R.string.library_bookmarks_body,
        DesignR.drawable.ic_bookmark
    ),
    HISTORY(R.string.history_title, R.string.library_history_body, DesignR.drawable.ic_history),
    VIDEOS(R.string.library_videos, R.string.library_videos_body, DesignR.drawable.ic_play),
    SCREENSHOTS(
        R.string.library_screenshots,
        R.string.library_screenshots_body,
        DesignR.drawable.ic_image
    ),
    TRASH(R.string.library_trash, R.string.library_trash_body, DesignR.drawable.ic_delete),
    HIDDEN(R.string.hidden_title, R.string.library_hidden_body, DesignR.drawable.ic_visibility_off),
    VAULT(R.string.vault_title, R.string.library_vault_body, DesignR.drawable.ic_lock),
    OFFLINE(R.string.library_offline, R.string.library_offline_body, DesignR.drawable.ic_cloud_off),
    TRANSFERS(
        R.string.library_transfers,
        R.string.library_transfers_body,
        DesignR.drawable.ic_swap_vert
    ),
    STORAGE(
        R.string.library_storage,
        R.string.library_storage_body,
        DesignR.drawable.ic_hard_drive
    ),
    SETTINGS(R.string.settings_title, R.string.library_settings_body, DesignR.drawable.ic_settings)
}

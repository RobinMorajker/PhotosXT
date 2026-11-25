package com.photoxt.feature.collage

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photoxt.core.database.CollageDao
import com.photoxt.core.database.CollageProjectEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The collage drafts (spec §4.11, "re-editable draft"), the latest change first. */
@HiltViewModel
class CollageDraftsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: CollageDao
) : ViewModel() {
    val drafts: StateFlow<List<CollageProjectEntity>?> = dao.drafts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    /** Deletes a draft and its picture; the photos and any saved collage stay. */
    fun delete(id: String) {
        viewModelScope.launch {
            dao.delete(id)
            withContext(Dispatchers.IO) { draftPicture(context, id).delete() }
        }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

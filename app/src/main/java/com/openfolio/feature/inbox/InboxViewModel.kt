package com.openfolio.feature.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.entity.DocumentStatus
import com.openfolio.core.data.repository.DocumentRepositoryImpl
import com.openfolio.core.domain.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val documentRepository: DocumentRepository,
) : ViewModel() {
    private val _documents = MutableStateFlow<List<DocumentEntity>>(emptyList())
    val documents: StateFlow<List<DocumentEntity>> = _documents.asStateFlow()

    init {
        viewModelScope.launch {
            documentRepository.getAllDocuments().collect { documents ->
                _documents.value = documents
            }
        }
    }

    fun retryOrResume(document: DocumentEntity) {
        viewModelScope.launch {
            when (document.status) {
                DocumentStatus.OCR_FAILED.name -> {
                    if (document.extractedText != DocumentRepositoryImpl.PASSWORD_PROTECTED_MARKER) {
                        documentRepository.retryLocalExtraction(document.id)
                    }
                }
                DocumentStatus.FAILED.name -> {
                    documentRepository.enqueueAiExtraction(document.id)
                }
            }
        }
    }
}

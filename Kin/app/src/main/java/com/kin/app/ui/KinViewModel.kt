package com.kin.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kin.app.data.FirebaseRepository
import com.kin.app.data.Friendship
import com.kin.app.data.FriendshipStatus
import com.kin.app.data.UserListeningState
import com.kin.app.data.UserProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SessionState {
    data object Loading : SessionState
    /** No Firebase user. [busy] is true while the Google flow / token exchange is in progress. */
    data class SignedOut(val error: String? = null, val busy: Boolean = false) : SessionState
    data class Ready(val profile: UserProfile) : SessionState
    data class Error(val message: String) : SessionState
}

data class RequestUiState(
    val sending: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class KinViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = FirebaseRepository.get()
    private val sharing = SharingStarted.WhileSubscribed(5_000)

    private val _session = MutableStateFlow<SessionState>(SessionState.Loading)
    val session: StateFlow<SessionState> = _session.asStateFlow()

    private val _requestUi = MutableStateFlow(RequestUiState())
    val requestUi: StateFlow<RequestUiState> = _requestUi.asStateFlow()

    private val uid: Flow<String?> = _session
        .map { (it as? SessionState.Ready)?.profile?.userId }
        .distinctUntilChanged()

    val profile: StateFlow<UserProfile?> = uid
        .flatMapLatest { id -> if (id == null) flowOf(null) else repository.observeProfile(id) }
        .stateIn(viewModelScope, sharing, null)

    val myStatus: StateFlow<UserListeningState?> = uid
        .flatMapLatest { id -> if (id == null) flowOf(null) else repository.observeOwnStatus(id) }
        .stateIn(viewModelScope, sharing, null)

    private val friendships: Flow<List<Friendship>> = uid
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.observeFriendships(id) }

    val incomingRequests: StateFlow<List<Friendship>> = combine(friendships, uid) { list, me ->
        list.filter { it.status == FriendshipStatus.PENDING && it.receiverId == me }
            .sortedByDescending { it.createdAt }
    }.stateIn(viewModelScope, sharing, emptyList())

    val outgoingRequests: StateFlow<List<Friendship>> = combine(friendships, uid) { list, me ->
        list.filter { it.status == FriendshipStatus.PENDING && it.requesterId == me }
            .sortedByDescending { it.createdAt }
    }.stateIn(viewModelScope, sharing, emptyList())

    val friends: StateFlow<List<Friendship>> = friendships
        .map { list -> list.filter { it.status == FriendshipStatus.ACCEPTED } }
        .stateIn(viewModelScope, sharing, emptyList())

    val friendStatuses: StateFlow<List<UserListeningState>> =
        combine(friendships, uid) { list, me ->
            if (me == null) emptyList()
            else list.filter { it.status == FriendshipStatus.ACCEPTED }.map { it.otherUserId(me) }.sorted()
        }
            .distinctUntilChanged()
            .flatMapLatest { ids -> repository.observeFriendStatuses(ids) }
            .map { list -> list.filter { it.title.isNotBlank() }.sortedByDescending { it.updatedAt } }
            .stateIn(viewModelScope, sharing, emptyList())

    init {
        bootstrap()
    }

    /** Restores an existing Firebase session (if any) and loads/creates the profile. */
    fun bootstrap() {
        viewModelScope.launch {
            if (!repository.isSignedIn) {
                _session.value = SessionState.SignedOut()
                return@launch
            }
            _session.value = SessionState.Loading
            _session.value = loadProfile()
        }
    }

    private suspend fun loadProfile(): SessionState =
        runCatching { repository.ensureProfile() }.fold(
            onSuccess = { SessionState.Ready(it) },
            onFailure = { SessionState.Error(it.message ?: "Could not load your profile") }
        )

    // ---------------------------------------------------------------- Google Sign-In

    fun onSignInStarted() {
        _session.value = SessionState.SignedOut(busy = true)
    }

    fun onSignInCancelled() {
        _session.value = SessionState.SignedOut()
    }

    fun onSignInFailed(message: String) {
        _session.value = SessionState.SignedOut(error = message)
    }

    /** Called with the Google ID token returned by the sign-in intent. */
    fun signInWithGoogle(idToken: String) {
        viewModelScope.launch {
            _session.value = SessionState.SignedOut(busy = true)
            val result = runCatching { repository.signInWithGoogle(idToken) }
            if (result.isSuccess) {
                _session.value = SessionState.Loading
                _session.value = loadProfile()
            } else {
                _session.value = SessionState.SignedOut(
                    error = result.exceptionOrNull()?.message ?: "Sign-in failed"
                )
            }
        }
    }

    /** Clears Firebase Auth; the caller is responsible for signing out of the Google client. */
    fun signOut() {
        repository.signOut()
        _requestUi.value = RequestUiState()
        _session.value = SessionState.SignedOut()
    }

    // ---------------------------------------------------------------- Friends / profile

    fun sendFriendRequest(tag: String) {
        if (_requestUi.value.sending) return
        viewModelScope.launch {
            _requestUi.value = RequestUiState(sending = true)
            _requestUi.value = repository.sendFriendRequest(tag).fold(
                onSuccess = { RequestUiState(message = it, isError = false) },
                onFailure = { RequestUiState(message = it.message ?: "Something went wrong", isError = true) }
            )
        }
    }

    fun clearRequestMessage() {
        _requestUi.value = RequestUiState()
    }

    fun acceptRequest(friendship: Friendship) {
        viewModelScope.launch { runCatching { repository.acceptRequest(friendship.id) } }
    }

    fun removeFriendship(friendship: Friendship) {
        viewModelScope.launch { runCatching { repository.removeFriendship(friendship.id) } }
    }

    fun updateDisplayName(name: String) {
        viewModelScope.launch { runCatching { repository.updateDisplayName(name) } }
    }
}

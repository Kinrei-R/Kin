package com.kin.app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

class FirebaseRepository private constructor(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore
) {
    private val users get() = db.collection("users")
    private val tags get() = db.collection("tags")
    private val statuses get() = db.collection("statuses")
    private val friendships get() = db.collection("friendships")

    @Volatile private var cachedName: String? = null
    private val random = SecureRandom()

    private val currentUid: String
        get() = auth.currentUser?.uid ?: throw IllegalStateException("Not signed in")

    // ---------------------------------------------------------------- Auth / profile

    val isSignedIn: Boolean get() = auth.currentUser != null

    /** Exchanges a Google ID token for a Firebase Auth session. */
    suspend fun signInWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential).await()
    }

    fun signOut() {
        auth.signOut()
        cachedName = null
    }

    /**
     * Loads the signed-in user's profile, creating it on first sign-in from the Google account's
     * display name and email, together with a unique shareable Tag.
     */
    suspend fun ensureProfile(): UserProfile {
        val user = auth.currentUser ?: throw IllegalStateException("Not signed in")
        val ref = users.document(user.uid)
        val email = user.email.orEmpty()

        val existing = ref.get().await().toObject(UserProfile::class.java)
        if (existing != null) {
            val refreshed = if (email.isNotEmpty() && existing.email != email) {
                ref.update("email", email).await()
                existing.copy(email = email)
            } else existing
            cachedName = refreshed.displayName
            return refreshed
        }

        val displayName = (user.displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: email.substringBefore('@').takeIf { it.isNotBlank() }
            ?: "Kin User").take(32)
        val tag = claimUniqueTag(user.uid, displayName)
        val profile = UserProfile(
            userId = user.uid,
            displayName = displayName,
            email = email,
            tag = tag,
            createdAt = System.currentTimeMillis()
        )
        ref.set(profile).await()
        cachedName = displayName
        return profile
    }

    /** Reserves a unique Tag. tags/{tag} holds only the public bits (uid + display name). */
    private suspend fun claimUniqueTag(uid: String, displayName: String): String {
        repeat(12) {
            val candidate = buildString { repeat(TAG_LENGTH) { append(TAG_ALPHABET[random.nextInt(TAG_ALPHABET.length)]) } }
            val ref = tags.document(candidate)
            val claimed = db.runTransaction { tx ->
                if (tx.get(ref).exists()) {
                    false
                } else {
                    tx.set(ref, mapOf("userId" to uid, "displayName" to displayName))
                    true
                }
            }.await()
            if (claimed) return candidate
        }
        throw IllegalStateException("Could not allocate a unique Tag, please retry")
    }

    suspend fun updateDisplayName(name: String) {
        val clean = name.trim().take(32)
        require(clean.isNotEmpty()) { "Name cannot be empty" }
        val uid = currentUid
        val tag = users.document(uid).get().await().getString("tag")
        users.document(uid).update("displayName", clean).await()
        if (tag != null) tags.document(tag).update("displayName", clean).await()
        statuses.document(uid).set(mapOf("displayName" to clean), SetOptions.merge()).await()
        cachedName = clean
    }

    fun observeProfile(uid: String): Flow<UserProfile?> = callbackFlow {
        val reg = users.document(uid).addSnapshotListener { snap, err ->
            if (err == null) trySend(snap?.toObject(UserProfile::class.java))
        }
        awaitClose { reg.remove() }
    }

    fun observeOwnStatus(uid: String): Flow<UserListeningState?> = callbackFlow {
        val reg = statuses.document(uid).addSnapshotListener { snap, err ->
            if (err == null) trySend(snap?.toObject(UserListeningState::class.java))
        }
        awaitClose { reg.remove() }
    }

    // ---------------------------------------------------------------- Live status

    /** Called when the track changes: writes the full status document. */
    suspend fun publishListeningState(
        title: String,
        artist: String,
        sourceApp: String,
        sourcePackage: String,
        playing: Boolean
    ) {
        val uid = currentUid
        val name = cachedName ?: users.document(uid).get().await()
            .getString("displayName").orEmpty().also { cachedName = it }
        statuses.document(uid).set(
            UserListeningState(
                userId = uid,
                displayName = name,
                title = title,
                artist = artist,
                sourceApp = sourceApp,
                sourcePackage = sourcePackage,
                playing = playing,
                updatedAt = System.currentTimeMillis()
            )
        ).await()
    }

    /** Lightweight update when the same track is paused/resumed or playback stops. */
    suspend fun setPlaying(playing: Boolean) {
        statuses.document(currentUid)
            .update(mapOf("playing" to playing, "updatedAt" to System.currentTimeMillis()))
            .await()
    }

    // ---------------------------------------------------------------- Friends

    /** Returns a user-facing success message, or fails with a user-facing message. */
    suspend fun sendFriendRequest(rawTag: String): Result<String> = runCatching {
        val tag = rawTag.trim().removePrefix("#").uppercase()
        require(tag.isNotEmpty()) { "Enter a Tag" }
        val me = currentUid

        val tagSnap = tags.document(tag).get().await()
        val targetId = tagSnap.getString("userId") ?: throw IllegalArgumentException("No user found with that Tag")
        require(targetId != me) { "That's your own Tag" }

        val id = pairId(me, targetId)
        val existing = friendships.document(id).get().await().toObject(Friendship::class.java)
        if (existing != null) {
            when {
                existing.status == FriendshipStatus.ACCEPTED -> throw IllegalArgumentException("You're already friends")
                existing.requesterId == me -> throw IllegalArgumentException("Request already sent")
                else -> {
                    friendships.document(id).update("status", FriendshipStatus.ACCEPTED).await()
                    return@runCatching "You're now friends with ${existing.requesterName}"
                }
            }
        }

        val myName = cachedName ?: users.document(me).get().await().getString("displayName").orEmpty()
        val targetName = tagSnap.getString("displayName").orEmpty()
        friendships.document(id).set(
            Friendship(
                id = id,
                requesterId = me,
                receiverId = targetId,
                requesterName = myName,
                receiverName = targetName,
                participants = listOf(me, targetId),
                status = FriendshipStatus.PENDING,
                createdAt = System.currentTimeMillis()
            )
        ).await()
        "Request sent to $targetName"
    }

    suspend fun acceptRequest(friendshipId: String) {
        friendships.document(friendshipId).update("status", FriendshipStatus.ACCEPTED).await()
    }

    /** Rejects an incoming request, cancels an outgoing one, or removes an existing friend. */
    suspend fun removeFriendship(friendshipId: String) {
        friendships.document(friendshipId).delete().await()
    }

    /** All friendships (pending + accepted) involving the current user, in real time. */
    fun observeFriendships(uid: String): Flow<List<Friendship>> = callbackFlow {
        val reg = friendships.whereArrayContains("participants", uid)
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                trySend(snap.documents.mapNotNull { it.toObject(Friendship::class.java) })
            }
        awaitClose { reg.remove() }
    }

    /**
     * Real-time statuses of the given friends. One snapshot listener per friend so Firestore
     * security rules can verify friendship per document.
     */
    fun observeFriendStatuses(friendIds: List<String>): Flow<List<UserListeningState>> = callbackFlow {
        val current = ConcurrentHashMap<String, UserListeningState>()
        if (friendIds.isEmpty()) trySend(emptyList())
        val registrations = friendIds.map { id ->
            statuses.document(id).addSnapshotListener { snap, err ->
                if (err != null) return@addSnapshotListener
                val state = snap?.toObject(UserListeningState::class.java)
                if (state != null) current[id] = state else current.remove(id)
                trySend(current.values.toList())
            }
        }
        awaitClose { registrations.forEach { it.remove() } }
    }

    companion object {
        private const val TAG_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val TAG_LENGTH = 6

        @Volatile private var instance: FirebaseRepository? = null

        fun get(): FirebaseRepository = instance ?: synchronized(this) {
            instance ?: FirebaseRepository(
                FirebaseAuth.getInstance(),
                FirebaseFirestore.getInstance()
            ).also { instance = it }
        }

        fun pairId(a: String, b: String): String = if (a < b) "${a}_$b" else "${b}_$a"
    }
}

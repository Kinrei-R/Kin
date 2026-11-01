package com.kin.app.data

/** Private profile stored at users/{userId} (owner-only). [tag] is the shareable friend code. */
data class UserProfile(
    val userId: String = "",
    val displayName: String = "",
    val email: String = "",
    val tag: String = "",
    val createdAt: Long = 0L
)

/** Live listening state stored at statuses/{userId}. */
data class UserListeningState(
    val userId: String = "",
    val displayName: String = "",
    val title: String = "",
    val artist: String = "",
    val sourceApp: String = "",
    val sourcePackage: String = "",
    val playing: Boolean = false,
    val updatedAt: Long = 0L
)

object FriendshipStatus {
    const val PENDING = "pending"
    const val ACCEPTED = "accepted"
}

/** Stored at friendships/{sortedUidA_sortedUidB}. */
data class Friendship(
    val id: String = "",
    val requesterId: String = "",
    val receiverId: String = "",
    val requesterName: String = "",
    val receiverName: String = "",
    val participants: List<String> = emptyList(),
    val status: String = FriendshipStatus.PENDING,
    val createdAt: Long = 0L
) {
    fun otherUserId(me: String): String = if (requesterId == me) receiverId else requesterId
    fun otherName(me: String): String = if (requesterId == me) receiverName else requesterName
}

package com.rudnam.samfran.core.network.model

import com.rudnam.samfran.core.model.AppLock
import com.rudnam.samfran.core.model.LockPage
import com.rudnam.samfran.core.model.LockRequest
import com.rudnam.samfran.core.model.LockRequestKind
import com.rudnam.samfran.core.model.LockRequestStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

// Locked apps (internal/applock), as GET /api/locks and the requests' routes answer.

@Serializable
data class NetworkLock(
    val id: Long,
    @SerialName("package") val packageName: String,
    val label: String = "",
    val mine: Boolean = false,
    val owner: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("open_until") val openUntil: String? = null,
)

@Serializable
data class NetworkLockRequest(
    val id: Long,
    @SerialName("lock_id") val lockId: Long? = null,
    @SerialName("package") val packageName: String = "",
    val label: String = "",
    val kind: String = "open",
    val minutes: Int = 0,
    val message: String = "",
    val status: String = "pending",
    val reply: String = "",
    @SerialName("open_until") val openUntil: String? = null,
    val mine: Boolean = false,
    val who: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("answered_at") val answeredAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class NetworkLockPage(
    val locks: List<NetworkLock> = emptyList(),
    val requests: List<NetworkLockRequest> = emptyList(),
    val partner: String = "",
)

@Serializable
data class NetworkLockInput(@SerialName("package") val packageName: String, val label: String)

@Serializable
data class NetworkLockAsk(
    @SerialName("lock_id") val lockId: Long,
    val kind: String,
    val minutes: Int,
    val message: String,
)

@Serializable
data class NetworkLockAnswer(val allow: Boolean, val minutes: Int, val reply: String)

@Serializable
data class NetworkLockOverride(val minutes: Int, val message: String)

@Serializable
data class NetworkPushToken(val token: String)

private fun instant(s: String?): Instant? = s?.let { runCatching { Instant.parse(it) }.getOrNull() }

fun NetworkLock.asModel() = AppLock(
    id = id,
    packageName = packageName,
    label = label.ifEmpty { packageName },
    mine = mine,
    owner = owner,
    createdAt = instant(createdAt) ?: Instant.EPOCH,
    openUntil = instant(openUntil),
)

fun NetworkLockRequest.asModel() = LockRequest(
    id = id,
    lockId = lockId,
    packageName = packageName,
    label = label.ifEmpty { packageName },
    kind = LockRequestKind.of(kind),
    minutes = minutes,
    message = message,
    status = LockRequestStatus.of(status),
    reply = reply,
    openUntil = instant(openUntil),
    mine = mine,
    who = who,
    createdAt = instant(createdAt) ?: Instant.EPOCH,
    answeredAt = instant(answeredAt),
    expiresAt = instant(expiresAt),
)

fun NetworkLockPage.asModel() = LockPage(
    locks = locks.map { it.asModel() },
    requests = requests.map { it.asModel() },
    partner = partner,
)

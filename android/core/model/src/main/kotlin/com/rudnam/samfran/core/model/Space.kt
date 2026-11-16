package com.rudnam.samfran.core.model

import java.time.Instant

/** Someone in the space the two of you share. */
data class Member(
    val name: String,
    val email: String,
    /** The signed-in person. */
    val me: Boolean,
)

data class Invite(val email: String, val createdAt: Instant)

/** Everything belongs to a space shared by two people (internal/space). */
data class Space(
    /** In the order they joined: the first is "a" (red), the second "b" (orange). */
    val members: List<Member>,
    /** Addresses invited to join, who haven't yet. */
    val invites: List<Invite>,
    /** When the open invite link stops working, if there is one (its address is shown only when made). */
    val inviteLinkExpires: Instant?,
) {
    /** There's room for one more: nobody else has joined or been invited. */
    val hasRoom: Boolean get() = members.size + invites.size < 2

    /** The other person, once they've joined. */
    val partner: Member? get() = members.firstOrNull { !it.me }

    /** Each person keeps one colour everywhere: the first to join is [Tone.A], the second [Tone.B]. */
    fun toneOf(name: String): Tone = if (members.indexOfFirst { it.name == name } == 1) Tone.B else Tone.A
}

/** The two people's colours (web/src/styles.css: --a-* is red, --b-* orange). */
enum class Tone { A, B }

/** A person's initial, as their round badge shows it. */
fun initialOf(name: String): String = name.trim().firstOrNull()?.uppercase() ?: "?"

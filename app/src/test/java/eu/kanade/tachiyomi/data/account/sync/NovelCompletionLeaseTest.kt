package eu.kanade.tachiyomi.data.account.sync

import eu.kanade.tachiyomi.novels.NovelCloudState
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mihon.domain.account.AccountSession
import mihon.domain.account.MangaroProfile
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.lang.LocalAccountScope

class NovelCompletionLeaseTest {
    private val a = "00000000-0000-0000-0000-000000000031"
    private val b = "00000000-0000-0000-0000-000000000032"
    private val token = LocalAccountScope.Token(a, 1)
    private val session = AccountSession.Authenticated(MangaroProfile(a, null, "Fixture", null, null, 0, 1))
    private fun capability(value: kotlinx.serialization.json.JsonElement? = JsonPrimitive(1)) = JsonObject(
        mapOf(
            "schema" to JsonPrimitive(1),
            "private" to JsonPrimitive(true),
            "tables" to JsonArray(NovelCloudState.tables.map(::JsonPrimitive)),
        ) +
            if (value == null) emptyMap() else mapOf("completionEvidence" to value),
    )
    private fun lease(
        c: JsonObject = capability(),
    ) = NovelCompletionLease.negotiate(a, token, session, "synthetic-fingerprint", c)!!

    @Test fun `exact supported response enables only its bound session`() {
        val lease = lease()
        lease.enabled shouldBe true
        lease.valid(a, token, session, "synthetic-fingerprint") shouldBe true
    }

    @Test fun `schema one and unknown capability values never enable new wire fields`() {
        for (v in listOf(null, JsonPrimitive("1"), JsonPrimitive(true), JsonPrimitive(2), JsonPrimitive(0))) {
            lease(capability(v)).enabled shouldBe false
        }
    }

    @Test fun `malformed base contract fails closed`() {
        for ((key, value) in listOf(
            "schema" to JsonPrimitive("1"),
            "private" to JsonPrimitive(false),
            "tables" to JsonArray(emptyList()),
        )) {
            NovelCompletionLease.negotiate(
                a,
                token,
                session,
                "synthetic-fingerprint",
                JsonObject(capability() + (key to value)),
            ) shouldBe
                null
        }
    }

    @Test fun `logout expiry or changed SDK session invalidates capability`() {
        lease().valid(a, token, AccountSession.Guest, "synthetic-fingerprint") shouldBe false
        lease().valid(a, token, session, null) shouldBe false
        lease().valid(a, token, session, "refreshed-fingerprint") shouldBe false
    }

    @Test fun `account A to B to A requires fresh generation and probe`() {
        lease().valid(b, LocalAccountScope.Token(b, 2), session, "synthetic-fingerprint") shouldBe false
        lease().valid(a, LocalAccountScope.Token(a, 3), session, "synthetic-fingerprint") shouldBe false
    }

    @Test fun `same account profile in a new authentication state is not the original lease`() {
        lease().valid(a, token, session.copy(), "synthetic-fingerprint") shouldBe false
    }

    @Test fun `guest missing credentials and mismatched owner never negotiate`() {
        NovelCompletionLease.negotiate(a, token, AccountSession.Guest, "x", capability()) shouldBe null
        NovelCompletionLease.negotiate(a, token, session, null, capability()) shouldBe null
        NovelCompletionLease.negotiate(b, token, session, "x", capability()) shouldBe null
    }
}

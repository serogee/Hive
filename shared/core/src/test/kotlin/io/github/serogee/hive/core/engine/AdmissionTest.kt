package io.github.serogee.hive.core.engine

import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.model.AdmissionPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class AdmissionTest {
    @Test
    fun everyoneAcceptsGuestsAndAccounts() {
        assertEquals(AdmissionDecision.AsGuest, Admission.decide(AdmissionPolicy.EVERYONE, signedIn = false, asGuest = true))
        assertEquals(AdmissionDecision.AsGuest, Admission.decide(AdmissionPolicy.EVERYONE, signedIn = true, asGuest = true))
        assertEquals(AdmissionDecision.AsAccount, Admission.decide(AdmissionPolicy.EVERYONE, signedIn = true, asGuest = false))
    }

    @Test
    fun accountJoinNeedsSignIn() {
        assertEquals(
            AdmissionDecision.Denied(ErrorCode.SIGN_IN_REQUIRED),
            Admission.decide(AdmissionPolicy.EVERYONE, signedIn = false, asGuest = false),
        )
    }

    @Test
    fun signedInOnlyRejectsGuests() {
        assertEquals(
            AdmissionDecision.Denied(ErrorCode.GUESTS_NOT_ALLOWED),
            Admission.decide(AdmissionPolicy.SIGNED_IN_ONLY, signedIn = true, asGuest = true),
        )
        assertEquals(
            AdmissionDecision.Denied(ErrorCode.SIGN_IN_REQUIRED),
            Admission.decide(AdmissionPolicy.SIGNED_IN_ONLY, signedIn = false, asGuest = false),
        )
        assertEquals(AdmissionDecision.AsAccount, Admission.decide(AdmissionPolicy.SIGNED_IN_ONLY, signedIn = true, asGuest = false))
    }

    @Test
    fun guestsOnlyLetsSignedInUsersJoinAsGuests() {
        assertEquals(AdmissionDecision.AsGuest, Admission.decide(AdmissionPolicy.GUESTS_ONLY, signedIn = true, asGuest = true))
        assertEquals(
            AdmissionDecision.Denied(ErrorCode.GUESTS_ONLY),
            Admission.decide(AdmissionPolicy.GUESTS_ONLY, signedIn = true, asGuest = false),
        )
    }
}

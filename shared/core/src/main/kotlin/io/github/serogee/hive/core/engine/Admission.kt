package io.github.serogee.hive.core.engine

import io.github.serogee.hive.core.api.ErrorCode
import io.github.serogee.hive.core.model.AdmissionPolicy

/** The outcome of asking to join a room. */
sealed interface AdmissionDecision {
    /** Join with the signed-in account. */
    data object AsAccount : AdmissionDecision

    /** Join as a guest with a display name. */
    data object AsGuest : AdmissionDecision

    data class Denied(val reason: ErrorCode) : AdmissionDecision
}

object Admission {
    /**
     * Applies the room's [policy] to a join request.
     *
     * A signed-in user can still join as a guest when the policy allows guests.
     */
    fun decide(policy: AdmissionPolicy, signedIn: Boolean, asGuest: Boolean): AdmissionDecision =
        when (policy) {
            AdmissionPolicy.EVERYONE -> when {
                asGuest -> AdmissionDecision.AsGuest
                signedIn -> AdmissionDecision.AsAccount
                else -> AdmissionDecision.Denied(ErrorCode.SIGN_IN_REQUIRED)
            }

            AdmissionPolicy.SIGNED_IN_ONLY -> when {
                asGuest -> AdmissionDecision.Denied(ErrorCode.GUESTS_NOT_ALLOWED)
                signedIn -> AdmissionDecision.AsAccount
                else -> AdmissionDecision.Denied(ErrorCode.SIGN_IN_REQUIRED)
            }

            AdmissionPolicy.GUESTS_ONLY -> when {
                asGuest -> AdmissionDecision.AsGuest
                else -> AdmissionDecision.Denied(ErrorCode.GUESTS_ONLY)
            }
        }
}

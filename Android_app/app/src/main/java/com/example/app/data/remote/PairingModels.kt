package com.example.app.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Account type as verified by the backend (`get_my_account`), never from local input. */
enum class AccountRole { Patient, VerifiedClinician, PendingClinician }

data class AccountInfo(
    val userId: String,
    val email: String?,
    val fullName: String?,
    val role: AccountRole,
)

data class ClinicianCode(val code: String, val updatedAt: String?) {
    val display: String get() = InviteCodeFormat.display(code)
}

/** Client-side format checks only; the backend is the authority on whether a code is valid. */
object InviteCodeFormat {
    const val LENGTH = 8
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun normalize(input: String): String = input.filter { it.isLetterOrDigit() }.uppercase()

    /** Null when the input looks like a code, otherwise a message for the patient. */
    fun validationError(input: String): String? {
        val code = normalize(input)
        return when {
            code.isEmpty() -> "Enter the code your clinician gave you."
            code.any { it !in ALPHABET } -> "Codes use letters and the digits 2–9 only (no I, O, 0 or 1)."
            code.length != LENGTH -> "Clinician codes have $LENGTH characters, for example ABCD-EF23."
            else -> null
        }
    }

    fun display(code: String): String =
        if (code.length == LENGTH) code.substring(0, 4) + "-" + code.substring(4) else code
}

enum class ConnectionKind { Direct, NeedsApproval, AlreadyConnected, AlreadyPending }

sealed interface LookupResult {
    data class Found(
        val code: String,
        val clinicianName: String,
        val clinicianEmailHint: String?,
        val kind: ConnectionKind,
        /** Name of the patient's other active clinician, who would be replaced. */
        val currentClinicianName: String?,
    ) : LookupResult
    data object InvalidCode : LookupResult
    data object RateLimited : LookupResult
}

sealed interface ConfirmResult {
    data class Connected(val clinicianName: String) : ConfirmResult
    data class PendingApproval(val clinicianName: String) : ConfirmResult
    data class AlreadyConnected(val clinicianName: String) : ConfirmResult
    data class HasActiveClinician(val currentClinicianName: String) : ConfirmResult
    data object InvalidCode : ConfirmResult
    data object RateLimited : ConfirmResult
}

enum class AssignmentStatus { Pending, Active, Revoked, Rejected, Cancelled }

data class ClinicianConnection(
    val assignmentId: String,
    val clinicianId: String,
    val clinicianName: String,
    val clinicianEmailHint: String?,
    val status: AssignmentStatus,
    val consentedAt: String?,
    val approvedAt: String?,
)

data class EndedConnection(
    val clinicianName: String,
    val status: AssignmentStatus,
    val endedAt: String?,
    val endedByClinician: Boolean,
)

data class MyClinicians(val connections: List<ClinicianConnection>, val lastEnded: EndedConnection?)

/** What the patient's "My Clinician" screen shows, derived only from backend data. */
sealed interface PatientConnectionStatus {
    data object None : PatientConnectionStatus
    data class Connected(val connection: ClinicianConnection) : PatientConnectionStatus
    data class PendingApproval(val connection: ClinicianConnection) : PatientConnectionStatus
    /** The clinician removed the patient or declined their request. */
    data class Revoked(val ended: EndedConnection) : PatientConnectionStatus
}

fun MyClinicians.status(): PatientConnectionStatus {
    connections.firstOrNull { it.status == AssignmentStatus.Active }?.let { return PatientConnectionStatus.Connected(it) }
    connections.firstOrNull { it.status == AssignmentStatus.Pending }?.let { return PatientConnectionStatus.PendingApproval(it) }
    val ended = lastEnded
    return if (ended != null && ended.endedByClinician) PatientConnectionStatus.Revoked(ended) else PatientConnectionStatus.None
}

data class AssignedPatient(
    val assignmentId: String?,
    val patientId: String,
    val name: String,
    val email: String?,
    val connectedSince: String?,
)

data class ReconnectionRequest(
    val requestId: String,
    val patientId: String,
    val name: String,
    val email: String?,
    val requestedAt: String?,
)

enum class ApproveResult { Approved, NotFound, PatientHasActiveClinician }

/** Parses the JSON returned by the pairing RPCs (see the Supabase migration). Pure, for unit tests. */
object PairingJson {
    fun account(json: JsonElement): AccountInfo {
        val o = json.obj()
        val verified = o.bool("clinician_verified") == true
        return AccountInfo(
            userId = o.string("user_id") ?: error("user_id missing"),
            email = o.string("email"),
            fullName = o.string("full_name"),
            role = when {
                o.string("role") != "clinician" -> AccountRole.Patient
                verified -> AccountRole.VerifiedClinician
                else -> AccountRole.PendingClinician
            },
        )
    }

    fun code(json: JsonElement): ClinicianCode? {
        if (json is JsonNull) return null
        val o = json.obj()
        return ClinicianCode(o.string("code") ?: return null, o.string("updated_at"))
    }

    fun lookup(json: JsonElement, code: String): LookupResult {
        val o = json.obj()
        return when (o.string("result")) {
            "found" -> LookupResult.Found(
                code = code,
                clinicianName = o.string("clinician_name") ?: "Your clinician",
                clinicianEmailHint = o.string("clinician_email_hint"),
                kind = when (o.string("connection")) {
                    "needs_approval" -> ConnectionKind.NeedsApproval
                    "already_connected" -> ConnectionKind.AlreadyConnected
                    "already_pending" -> ConnectionKind.AlreadyPending
                    else -> ConnectionKind.Direct
                },
                currentClinicianName = o.string("current_clinician_name"),
            )
            "rate_limited" -> LookupResult.RateLimited
            else -> LookupResult.InvalidCode
        }
    }

    fun confirm(json: JsonElement): ConfirmResult {
        val o = json.obj()
        val name = o.string("clinician_name") ?: "your clinician"
        return when (o.string("result")) {
            "connected" -> ConfirmResult.Connected(name)
            "pending_approval" -> ConfirmResult.PendingApproval(name)
            "already_connected" -> ConfirmResult.AlreadyConnected(name)
            "has_active_clinician" -> ConfirmResult.HasActiveClinician(o.string("current_clinician_name") ?: "another clinician")
            "rate_limited" -> ConfirmResult.RateLimited
            else -> ConfirmResult.InvalidCode
        }
    }

    fun myClinicians(json: JsonElement): MyClinicians {
        val o = json.obj()
        val connections = (o["connections"] as? JsonArray).orEmpty().map { item ->
            val c = item.obj()
            ClinicianConnection(
                assignmentId = c.string("assignment_id").orEmpty(),
                clinicianId = c.string("clinician_id").orEmpty(),
                clinicianName = c.string("clinician_name") ?: "Your clinician",
                clinicianEmailHint = c.string("clinician_email_hint"),
                status = status(c.string("status")),
                consentedAt = c.string("patient_consented_at"),
                approvedAt = c.string("clinician_approved_at"),
            )
        }
        val ended = (o["last_ended"] as? JsonObject)?.let { e ->
            EndedConnection(
                clinicianName = e.string("clinician_name") ?: "Your clinician",
                status = status(e.string("status")),
                endedAt = e.string("revoked_at"),
                endedByClinician = e.string("revoked_by_role") == "clinician" || e.string("status") == "rejected",
            )
        }
        return MyClinicians(connections, ended)
    }

    fun patients(json: JsonElement): List<AssignedPatient> = (json as? JsonArray).orEmpty().map { patient(it.obj()) }

    fun overview(json: JsonElement): AssignedPatient? {
        val o = json.obj()
        return if (o.string("result") == "ok") patient(o) else null
    }

    fun requests(json: JsonElement): List<ReconnectionRequest> = (json as? JsonArray).orEmpty().map { item ->
        val r = item.obj()
        ReconnectionRequest(
            requestId = r.string("request_id").orEmpty(),
            patientId = r.string("patient_id").orEmpty(),
            name = r.string("patient_name") ?: "Patient",
            email = r.string("patient_email"),
            requestedAt = r.string("requested_at"),
        )
    }

    fun result(json: JsonElement): String? = (json as? JsonObject)?.string("result")

    fun approve(json: JsonElement): ApproveResult = when (result(json)) {
        "approved" -> ApproveResult.Approved
        "patient_has_active_clinician" -> ApproveResult.PatientHasActiveClinician
        else -> ApproveResult.NotFound
    }

    private fun patient(o: JsonObject) = AssignedPatient(
        assignmentId = o.string("assignment_id"),
        patientId = o.string("patient_id").orEmpty(),
        name = o.string("patient_name") ?: "Patient",
        email = o.string("patient_email"),
        connectedSince = o.string("connected_since"),
    )

    private fun status(value: String?) = when (value) {
        "active" -> AssignmentStatus.Active
        "pending" -> AssignmentStatus.Pending
        "rejected" -> AssignmentStatus.Rejected
        "cancelled" -> AssignmentStatus.Cancelled
        else -> AssignmentStatus.Revoked
    }

    private fun JsonElement.obj(): JsonObject = this as? JsonObject ?: error("Unexpected response from server")
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonElement)?.let { if (it is JsonNull) null else it.jsonPrimitive.contentOrNull }
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonElement)?.let { if (it is JsonNull) null else it.jsonPrimitive.booleanOrNull }
}

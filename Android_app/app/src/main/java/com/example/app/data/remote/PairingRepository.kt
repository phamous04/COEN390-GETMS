package com.example.app.data.remote

import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Clinician–patient pairing. Every call is a Supabase RPC that identifies the caller from the
 * session JWT and checks role, verification and the live assignment on the server; the app never
 * sends its own user id or role. See supabase/migrations/20261116000000_clinician_patient_pairing.sql.
 */
interface PairingRepository {
    suspend fun getMyAccount(): AccountInfo
    suspend fun setMyFullName(name: String): String

    // Clinician
    suspend fun getMyClinicianCode(): ClinicianCode?
    suspend fun generateClinicianCode(): ClinicianCode
    suspend fun regenerateClinicianCode(): ClinicianCode
    suspend fun getMyAssignedPatients(): List<AssignedPatient>
    suspend fun getPendingReconnectionRequests(): List<ReconnectionRequest>
    /** Null when the caller no longer has an active relationship with this patient. */
    suspend fun getPatientOverview(patientId: String): AssignedPatient?
    suspend fun removePatient(patientId: String): Boolean
    suspend fun approveReconnection(requestId: String): ApproveResult
    suspend fun rejectReconnection(requestId: String): Boolean

    // Patient
    suspend fun lookupClinicianCode(code: String): LookupResult
    /** Also covers reconnection requests: the server decides between active and pending. */
    suspend fun confirmClinicianConnection(code: String, replaceCurrent: Boolean): ConfirmResult
    suspend fun getMyClinicians(): MyClinicians
    suspend fun removeClinician(clinicianId: String): Boolean
}

class PairingException(val kind: Kind, cause: Throwable? = null) : Exception(kind.name, cause) {
    enum class Kind { Network, NotSignedIn, NotVerifiedClinician, NotClinician, NotPatient, InvalidName, ServerNotReady, Unknown }

    val userMessage: String
        get() = when (kind) {
            Kind.Network -> "Can't reach the server. Check your internet connection and try again."
            Kind.NotSignedIn -> "Your session has expired. Please log in again."
            Kind.NotVerifiedClinician -> "Clinician access for this account is turned off. Contact your ReBand administrator."
            Kind.NotClinician -> "Only clinician accounts can do this."
            Kind.NotPatient -> "Only patient accounts can connect to a clinician."
            Kind.InvalidName -> "Please enter a name between 1 and 80 characters."
            Kind.ServerNotReady -> "Clinician linking isn't set up on the server yet. Ask your team to apply the pairing migration."
            Kind.Unknown -> "Something went wrong. Please try again."
        }

    companion object {
        /** Maps the error tokens raised by the SQL functions (and PostgREST) to a [Kind]. */
        fun kindFor(errorText: String): Kind {
            val text = errorText.lowercase()
            return when {
                "not_authenticated" in text || "jwt" in text -> Kind.NotSignedIn
                "not_verified_clinician" in text -> Kind.NotVerifiedClinician
                "not_clinician" in text -> Kind.NotClinician
                "not_patient" in text -> Kind.NotPatient
                "invalid_name" in text -> Kind.InvalidName
                "pgrst202" in text || "could not find the function" in text ||
                    "schema cache" in text -> Kind.ServerNotReady
                else -> Kind.Unknown
            }
        }

        fun from(e: Throwable): PairingException = when (e) {
            is PairingException -> e
            is HttpRequestException -> PairingException(Kind.Network, e)
            is RestException -> PairingException(kindFor("${e.error} ${e.description} ${e.message}"), e)
            else -> PairingException(Kind.Unknown, e)
        }
    }
}

val Throwable.pairingMessage: String get() = PairingException.from(this).userMessage

class SupabasePairingRepository : PairingRepository {

    private suspend fun call(function: String, params: JsonObject? = null): JsonElement {
        if (!SupabaseProvider.configured) throw PairingException(PairingException.Kind.ServerNotReady)
        try {
            val result = if (params == null) {
                SupabaseProvider.postgrest.rpc(function)
            } else {
                SupabaseProvider.postgrest.rpc(function, params)
            }
            return Json.parseToJsonElement(result.data.ifBlank { "null" })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PairingException.from(e)
        }
    }

    override suspend fun getMyAccount() = PairingJson.account(call("get_my_account"))

    override suspend fun setMyFullName(name: String): String {
        call("set_my_full_name", buildJsonObject { put("p_full_name", name.trim()) })
        return name.trim()
    }

    override suspend fun getMyClinicianCode() = PairingJson.code(call("get_my_clinician_code"))

    override suspend fun generateClinicianCode() =
        PairingJson.code(call("generate_clinician_code")) ?: throw PairingException(PairingException.Kind.Unknown)

    override suspend fun regenerateClinicianCode() =
        PairingJson.code(call("regenerate_clinician_code")) ?: throw PairingException(PairingException.Kind.Unknown)

    override suspend fun getMyAssignedPatients() = PairingJson.patients(call("get_my_assigned_patients"))

    override suspend fun getPendingReconnectionRequests() =
        PairingJson.requests(call("get_pending_reconnection_requests"))

    override suspend fun getPatientOverview(patientId: String) =
        PairingJson.overview(call("get_patient_overview", buildJsonObject { put("p_patient_id", patientId) }))

    override suspend fun removePatient(patientId: String) =
        PairingJson.result(call("remove_patient", buildJsonObject { put("p_patient_id", patientId) })) == "removed"

    override suspend fun approveReconnection(requestId: String) =
        PairingJson.approve(call("approve_reconnection", buildJsonObject { put("p_request_id", requestId) }))

    override suspend fun rejectReconnection(requestId: String) =
        PairingJson.result(call("reject_reconnection", buildJsonObject { put("p_request_id", requestId) })) == "rejected"

    override suspend fun lookupClinicianCode(code: String): LookupResult {
        val normalized = InviteCodeFormat.normalize(code)
        return PairingJson.lookup(call("lookup_clinician_code", buildJsonObject { put("p_code", normalized) }), normalized)
    }

    override suspend fun confirmClinicianConnection(code: String, replaceCurrent: Boolean) =
        PairingJson.confirm(
            call(
                "confirm_clinician_connection",
                buildJsonObject {
                    put("p_code", InviteCodeFormat.normalize(code))
                    put("p_replace_current", replaceCurrent)
                },
            ),
        )

    override suspend fun getMyClinicians() = PairingJson.myClinicians(call("get_my_clinicians"))

    override suspend fun removeClinician(clinicianId: String) =
        PairingJson.result(call("remove_clinician", buildJsonObject { put("p_clinician_id", clinicianId) })) == "removed"
}

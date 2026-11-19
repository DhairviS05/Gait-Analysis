
package com.robobionics.gaitimudemo.data

import android.net.Uri
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.suspendCancellableCoroutine
import com.google.android.gms.tasks.Task
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SessionUploadResult(
    val sessionId: String,
    val storagePath: String
)

class GaitFirebaseRepository {

    private val storage = FirebaseStorage.getInstance(
        "gs://grippyanalytics.appspot.com"
    )

    private val firestore = FirebaseFirestore.getInstance()

    suspend fun uploadCsv(
        csvFile: File,
        patientName: String
    ): SessionUploadResult {

        require(csvFile.exists()) {
            "CSV file does not exist"
        }

        val safePatientName = patientName.trim()
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")

        val sessionId = UUID.randomUUID().toString()

        val timestamp = csvFile.nameWithoutExtension.removePrefix("test_")

        val cloudFileName = "${safePatientName}_$timestamp.csv"

        val storagePath =
            "gaitAnalysisLeg/$safePatientName/$cloudFileName"

        val metadata = StorageMetadata.Builder()
            .setContentType("text/csv")
            .build()

        // 1. Upload CSV to Firebase Storage
        storage.reference
            .child(storagePath)
            .putFile(Uri.fromFile(csvFile), metadata)
            .awaitResult()

        // 2. Get the download URL
        val downloadUrl = storage.reference
            .child(storagePath)
            .downloadUrl
            .awaitResult()
            .toString()

        // 3. Save session metadata in Firestore
        val sessionData = hashMapOf(
            "sessionId" to sessionId,
            "patientName" to patientName.trim(),
            "fileName" to cloudFileName,
            "storagePath" to storagePath,
            "downloadUrl" to downloadUrl,
            "uploadedAt" to FieldValue.serverTimestamp(),
            "uploadStatus" to "completed"
        )

        firestore.collection("gait_sessions")
            .document(sessionId)
            .set(sessionData)
            .awaitResult()

        return SessionUploadResult(
            sessionId = sessionId,
            storagePath = storagePath
        )
    }

    private suspend fun <T> Task<T>.awaitResult(): T =
        suspendCancellableCoroutine { continuation ->

            addOnSuccessListener { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            addOnFailureListener { exception ->
                if (continuation.isActive) {
                    continuation.resumeWithException(exception)
                }
            }
        }
}

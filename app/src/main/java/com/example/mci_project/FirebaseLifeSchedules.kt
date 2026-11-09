package com.example.mci_project

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.QuerySnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared schedules for an already authenticated elder or a family member selected
 * through the consent-based account linking flow.
 */
internal class FirebaseLifeSchedules(app: FirebaseApp, elderUid: String) {
    private val collection = FirebaseFirestore.getInstance(app)
        .collection("lifeGuideUsers").document(elderUid).collection("schedules")

    fun listen(result: (List<LifeSchedule>?, String?) -> Unit) =
        collection.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            when {
                error != null -> result(null, "가족 일정을 열 수 없어요. 연결과 공유 권한을 확인해 주세요.")
                snapshot == null || snapshot.metadata.isFromCache -> Unit
                else -> runCatching { decode(snapshot) }.fold(
                    { result(it, null) },
                    { result(null, "일정 정보를 읽을 수 없어요.") }
                )
            }
        }

    fun put(item: LifeSchedule, onResult: (Boolean) -> Unit) {
        val data = mapOf(
            "title" to item.title, "date" to item.date, "minutes" to item.minutes,
            "repeat" to item.repeat.name, "steps" to item.steps, "reminder" to item.reminder
        )
        collection.document(item.id).set(data).addOnCompleteListener { onResult(it.isSuccessful) }
    }

    fun remove(id: String, onResult: (Boolean) -> Unit) {
        collection.document(id).delete().addOnCompleteListener { onResult(it.isSuccessful) }
    }

    private fun decode(snapshot: QuerySnapshot): List<LifeSchedule> = snapshot.documents.map { doc ->
        val data = requireNotNull(doc.data)
        LifeSchedule.fromJson(JSONObject(data).apply {
            put("id", doc.id)
            put("steps", JSONArray(data.getValue("steps") as List<*>))
        })
    }

    companion object {
        const val ELDER_UID = "com.example.mci_project.LIFE_GUIDE_ELDER_UID"

        fun app(context: Context): FirebaseApp? =
            FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }

        fun uid(context: Context): String? = app(context)?.let { FirebaseAuth.getInstance(it).currentUser?.uid }
    }
}

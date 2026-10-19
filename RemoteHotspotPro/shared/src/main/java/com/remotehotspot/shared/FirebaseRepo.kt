package com.remotehotspot.shared

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Schema (no pairing):
 *  /config/adminKey                 secret you set once in the Firebase console
 *  /adminSessions/{adminUid}        written by Admin app with the key
 *  /devices/{id}/meta               name, model, manufacturer, token, deviceUid, lastSeen
 *  /devices/{id}/status             battery, charging, hotspot, lastSeen, lastCmd, lastCmdTs
 *  /commands/{id}/{cmdId}           cmd, arg, ts
 *  /commandResults/{id}/{cmdId}     ok, msg, hotspot
 */
class FirebaseRepo {
    private val db = FirebaseDatabase.getInstance().apply { runCatching { setPersistenceEnabled(true) } }
    val auth = FirebaseAuth.getInstance()

    @Volatile private var offset = 0L
    init {
        db.getReference(".info/serverTimeOffset").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { offset = s.getValue(Long::class.java) ?: 0L }
            override fun onCancelled(e: DatabaseError) {}
        })
    }
    fun now(): Long = System.currentTimeMillis() + offset

    suspend fun uid(): String = auth.currentUser?.uid ?: auth.signInAnonymously().await().user!!.uid
    fun ref(path: String) = db.getReference(path)

    suspend fun registerDevice(deviceId: String, name: String, model: String, manufacturer: String, token: String) {
        val me = uid()
        ref("devices/$deviceId/meta").updateChildren(mapOf(
            "deviceUid" to me, "name" to name, "model" to model, "manufacturer" to manufacturer,
            "token" to token, "lastSeen" to ServerValue.TIMESTAMP
        )).await()
    }

    fun writeStatus(deviceId: String, battery: Int, charging: Boolean, hotspot: Boolean?, lastCmd: String? = null) {
        val m = mutableMapOf<String, Any?>("battery" to battery, "charging" to charging, "hotspot" to hotspot, "lastSeen" to ServerValue.TIMESTAMP)
        if (lastCmd != null) { m["lastCmd"] = lastCmd; m["lastCmdTs"] = System.currentTimeMillis() }
        ref("devices/$deviceId/status").updateChildren(m)
    }

    fun observeCommands(deviceId: String, onCommand: (Command) -> Unit): () -> Unit {
        val r = ref("commands/$deviceId")
        val l = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, p: String?) {
                val id = s.key ?: return
                val ts = s.child("ts").getValue(Long::class.java) ?: 0
                if (System.currentTimeMillis() - ts > 120_000L) { s.ref.removeValue(); return }
                onCommand(Command(id, s.child("cmd").getValue(String::class.java) ?: "", s.child("arg").getValue(String::class.java) ?: ""))
                s.ref.removeValue()
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        }
        r.addChildEventListener(l)
        return { r.removeEventListener(l) }
    }

    fun writeResult(deviceId: String, r: Reply) {
        ref("commandResults/$deviceId/${r.id}").setValue(mapOf("ok" to r.ok, "msg" to r.msg, "hotspot" to r.hotspot, "ts" to System.currentTimeMillis()))
    }

    suspend fun registerAdmin(key: String) { ref("adminSessions/${uid()}").setValue(key).await() }

    fun observeAllDevices(): Flow<List<DeviceInfo>> = callbackFlow {
        val r = ref("devices")
        val l = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                trySend(snap.children.mapNotNull { d ->
                    val id = d.key ?: return@mapNotNull null
                    val m = d.child("meta"); val s = d.child("status")
                    DeviceInfo(
                        deviceId = id,
                        name = m.child("name").getValue(String::class.java) ?: "Device",
                        model = m.child("model").getValue(String::class.java) ?: "",
                        manufacturer = m.child("manufacturer").getValue(String::class.java) ?: "",
                        battery = s.child("battery").getValue(Int::class.java) ?: -1,
                        charging = s.child("charging").getValue(Boolean::class.java) ?: false,
                        hotspot = s.child("hotspot").getValue(Boolean::class.java),
                        lastSeen = s.child("lastSeen").getValue(Long::class.java) ?: m.child("lastSeen").getValue(Long::class.java) ?: 0L,
                        lastCmd = s.child("lastCmd").getValue(String::class.java) ?: "",
                        lastCmdTs = s.child("lastCmdTs").getValue(Long::class.java) ?: 0L
                    )
                })
            }
            override fun onCancelled(e: DatabaseError) { close(e.toException()) }
        }
        r.addValueEventListener(l)
        awaitClose { r.removeEventListener(l) }
    }

    suspend fun sendCommand(deviceId: String, c: Command, timeoutMs: Long = 20_000L): Reply? {
        ref("commands/$deviceId/${c.id}").setValue(mapOf("cmd" to c.cmd, "arg" to c.arg, "ts" to ServerValue.TIMESTAMP)).await()
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Reply?> { cont ->
                val r = ref("commandResults/$deviceId/${c.id}")
                val l = object : ValueEventListener {
                    override fun onDataChange(s: DataSnapshot) {
                        if (!s.exists()) return
                        r.removeEventListener(this)
                        if (cont.isActive) cont.resume(Reply(c.id, s.child("ok").getValue(Boolean::class.java) ?: false,
                            s.child("msg").getValue(String::class.java) ?: "", s.child("hotspot").getValue(Boolean::class.java)))
                    }
                    override fun onCancelled(e: DatabaseError) { if (cont.isActive) cont.resume(null) }
                }
                r.addValueEventListener(l); cont.invokeOnCancellation { r.removeEventListener(l) }
            }
        }
    }
}

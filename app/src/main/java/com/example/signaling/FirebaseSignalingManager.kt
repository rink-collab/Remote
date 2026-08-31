package com.example.signaling

import android.util.Log
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

data class IceCandidateData(
    val sdpMid: String? = null,
    val sdpMLineIndex: Int = 0,
    val sdp: String = ""
)

class FirebaseSignalingManager {

    private val TAG = "FirebaseSignaling"
    private var database: FirebaseDatabase? = null
    private var roomsRef: DatabaseReference? = null

    init {
        try {
            database = FirebaseDatabase.getInstance()
            roomsRef = database?.getReference("peer_media_rooms")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Firebase Database", e)
        }
    }

    private var answerListener: ValueEventListener? = null
    private var candidateListener: ChildEventListener? = null
    private var hostsDiscoveryListener: ValueEventListener? = null
    private var currentRoomRef: DatabaseReference? = null

    fun generateHostId(): String {
        return "host_" + java.util.UUID.randomUUID().toString().take(8)
    }

    fun isFirebaseAvailable(): Boolean {
        return roomsRef != null
    }

    fun hostRoomWithDeviceInfo(
        roomId: String,
        deviceName: String,
        deviceModel: String,
        mediaCount: Int,
        photosCount: Int,
        videosCount: Int,
        audioCount: Int,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val ref = roomsRef ?: run {
            onError("Firebase Realtime Database is not initialized.")
            return
        }

        currentRoomRef = ref.child(roomId)
        val roomData = mapOf(
            "hostId" to roomId,
            "deviceName" to deviceName,
            "deviceModel" to deviceModel,
            "mediaCount" to mediaCount,
            "photosCount" to photosCount,
            "videosCount" to videosCount,
            "audioCount" to audioCount,
            "hostPresent" to true,
            "clientPresent" to false,
            "createdAt" to System.currentTimeMillis(),
            "lastSeen" to System.currentTimeMillis()
        )

        currentRoomRef?.setValue(roomData)
            ?.addOnSuccessListener {
                currentRoomRef?.child("hostPresent")?.onDisconnect()?.setValue(false)
                onSuccess()
            }
            ?.addOnFailureListener { e ->
                onError(e.localizedMessage ?: "Failed to broadcast host in Firebase")
            }
    }

    fun stopListeningForOnlineHosts() {
        hostsDiscoveryListener?.let {
            roomsRef?.removeEventListener(it)
            hostsDiscoveryListener = null
        }
    }

    fun listenForClientAnswer(
        roomId: String,
        onAnswerReceived: (String) -> Unit,
        onClientCandidateReceived: (IceCandidateData) -> Unit
    ) {
        val roomRef = roomsRef?.child(roomId) ?: return

        answerListener = roomRef.child("answer").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(answerSnap: DataSnapshot) {
                val sdp = answerSnap.child("sdp").getValue(String::class.java)
                if (!sdp.isNullOrEmpty()) {
                    onAnswerReceived(sdp)
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })

        candidateListener = roomRef.child("clientCandidates").addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(candSnap: DataSnapshot, previousChildName: String?) {
                val sdp = candSnap.child("sdp").getValue(String::class.java) ?: return
                val sdpMid = candSnap.child("sdpMid").getValue(String::class.java)
                val sdpMLineIndex = candSnap.child("sdpMLineIndex").getValue(Int::class.java) ?: 0
                onClientCandidateReceived(IceCandidateData(sdpMid, sdpMLineIndex, sdp))
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    fun sendOffer(roomId: String, sdp: String) {
        val offerMap = mapOf(
            "type" to "offer",
            "sdp" to sdp,
            "timestamp" to System.currentTimeMillis()
        )
        roomsRef?.child(roomId)?.child("offer")?.setValue(offerMap)
    }

    fun sendIceCandidate(roomId: String, isHost: Boolean, candidate: IceCandidateData) {
        val childNode = if (isHost) "hostCandidates" else "clientCandidates"
        val candMap = mapOf(
            "sdpMid" to candidate.sdpMid,
            "sdpMLineIndex" to candidate.sdpMLineIndex,
            "sdp" to candidate.sdp
        )
        roomsRef?.child(roomId)?.child(childNode)?.push()?.setValue(candMap)
    }

    fun cleanup() {
        answerListener?.let { currentRoomRef?.child("answer")?.removeEventListener(it) }
        candidateListener?.let {
            currentRoomRef?.child("hostCandidates")?.removeEventListener(it)
            currentRoomRef?.child("clientCandidates")?.removeEventListener(it)
        }
        currentRoomRef = null
    }
}

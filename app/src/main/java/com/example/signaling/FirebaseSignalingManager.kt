package com.example.signaling

import android.util.Log
import com.example.model.HostDevice
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

    private var offerListener: ValueEventListener? = null
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

    fun listenForOnlineHosts(onHostsUpdated: (List<HostDevice>) -> Unit): ValueEventListener? {
        val ref = roomsRef ?: return null
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val hosts = mutableListOf<HostDevice>()
                for (child in snapshot.children) {
                    val hostPresent = child.child("hostPresent").getValue(Boolean::class.java) ?: false
                    val createdAt = child.child("createdAt").getValue(Long::class.java) ?: 0L
                    if (hostPresent) {
                        val id = child.child("hostId").getValue(String::class.java) ?: (child.key ?: "")
                        val name = child.child("deviceName").getValue(String::class.java) ?: "Peer Host Device"
                        val model = child.child("deviceModel").getValue(String::class.java) ?: ""
                        val mediaCount = child.child("mediaCount").getValue(Int::class.java) ?: 0
                        val photosCount = child.child("photosCount").getValue(Int::class.java) ?: 0
                        val videosCount = child.child("videosCount").getValue(Int::class.java) ?: 0
                        val audioCount = child.child("audioCount").getValue(Int::class.java) ?: 0
                        val lastSeen = child.child("lastSeen").getValue(Long::class.java) ?: createdAt

                        if (id.isNotEmpty()) {
                            hosts.add(
                                HostDevice(
                                    id = id,
                                    name = name,
                                    model = model,
                                    isOnline = true,
                                    mediaCount = mediaCount,
                                    photosCount = photosCount,
                                    videosCount = videosCount,
                                    audioCount = audioCount,
                                    lastSeen = lastSeen
                                )
                            )
                        }
                    }
                }
                // Sort by newest first
                hosts.sortByDescending { it.lastSeen }
                onHostsUpdated(hosts)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Error discovering online hosts: ${error.message}")
            }
        }
        hostsDiscoveryListener = listener
        ref.addValueEventListener(listener)
        return listener
    }

    fun stopListeningForOnlineHosts() {
        hostsDiscoveryListener?.let {
            roomsRef?.removeEventListener(it)
            hostsDiscoveryListener = null
        }
    }

    fun hostRoom(
        roomId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        hostRoomWithDeviceInfo(
            roomId = roomId,
            deviceName = "Peer Device",
            deviceModel = "",
            mediaCount = 0,
            photosCount = 0,
            videosCount = 0,
            audioCount = 0,
            onSuccess = onSuccess,
            onError = onError
        )
    }

    fun joinRoom(
        roomId: String,
        onOfferReceived: (String) -> Unit,
        onHostCandidateReceived: (IceCandidateData) -> Unit,
        onError: (String) -> Unit
    ) {
        val ref = roomsRef ?: run {
            onError("Firebase Realtime Database is not initialized.")
            return
        }

        val roomRef = ref.child(roomId)
        currentRoomRef = roomRef

        // Verify room exists & host is present
        roomRef.child("hostPresent").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val hostPresent = snapshot.getValue(Boolean::class.java) ?: false
                if (!hostPresent) {
                    onError("Room $roomId not found or Host is offline.")
                    return
                }

                // Mark client as present
                roomRef.child("clientPresent").setValue(true)
                roomRef.child("clientPresent").onDisconnect().setValue(false)

                // Listen for SDP Offer from Host
                offerListener = roomRef.child("offer").addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(offerSnap: DataSnapshot) {
                        val sdp = offerSnap.child("sdp").getValue(String::class.java)
                        if (!sdp.isNullOrEmpty()) {
                            onOfferReceived(sdp)
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        onError("Offer listener cancelled: ${error.message}")
                    }
                })

                // Listen for Host ICE Candidates
                candidateListener = roomRef.child("hostCandidates").addChildEventListener(object : ChildEventListener {
                    override fun onChildAdded(candSnap: DataSnapshot, previousChildName: String?) {
                        val sdp = candSnap.child("sdp").getValue(String::class.java) ?: return
                        val sdpMid = candSnap.child("sdpMid").getValue(String::class.java)
                        val sdpMLineIndex = candSnap.child("sdpMLineIndex").getValue(Int::class.java) ?: 0
                        onHostCandidateReceived(IceCandidateData(sdpMid, sdpMLineIndex, sdp))
                    }

                    override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
                    override fun onChildRemoved(snapshot: DataSnapshot) {}
                    override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                    override fun onCancelled(error: DatabaseError) {}
                })
            }

            override fun onCancelled(error: DatabaseError) {
                onError("Database error: ${error.message}")
            }
        })
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

    fun sendAnswer(roomId: String, sdp: String) {
        val answerMap = mapOf(
            "type" to "answer",
            "sdp" to sdp,
            "timestamp" to System.currentTimeMillis()
        )
        roomsRef?.child(roomId)?.child("answer")?.setValue(answerMap)
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
        offerListener?.let { currentRoomRef?.child("offer")?.removeEventListener(it) }
        answerListener?.let { currentRoomRef?.child("answer")?.removeEventListener(it) }
        candidateListener?.let {
            currentRoomRef?.child("hostCandidates")?.removeEventListener(it)
            currentRoomRef?.child("clientCandidates")?.removeEventListener(it)
        }
        currentRoomRef = null
    }
}

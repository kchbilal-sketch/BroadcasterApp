package com.broadcaster;

import android.util.Log;
import androidx.annotation.NonNull;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;
import java.util.HashMap;
import java.util.Map;

public class FirebaseSignalingClient {
    private static final String TAG = "FirebaseSignaling";
    
    private DatabaseReference databaseRef;
    private String deviceId;
    private DatabaseReference deviceRef;
    private DatabaseReference offerRef;
    private DatabaseReference answerRef;
    private DatabaseReference iceCandidatesRef;
    
    // Listeners
    private OnViewerConnectedListener viewerConnectedListener;
    private OnViewerDisconnectedListener viewerDisconnectedListener;
    private OnOfferReceivedListener offerReceivedListener;
    private OnIceCandidateReceivedListener iceCandidateReceivedListener;
    
    // Value event listeners for cleanup
    private ValueEventListener viewerConnectionListener;
    private ValueEventListener offerListener;
    private ValueEventListener answerListener;
    private ValueEventListener iceCandidatesListener;
    
    // WebRTC objects (set by BackgroundService)
    private org.webrtc.PeerConnection peerConnection;
    
    public interface OnViewerConnectedListener {
        void onViewerConnected();
    }
    
    public interface OnViewerDisconnectedListener {
        void onViewerDisconnected();
    }
    
    public interface OnOfferReceivedListener {
        void onOfferReceived(SessionDescription offer);
    }
    
    public interface OnIceCandidateReceivedListener {
        void onIceCandidateReceived(IceCandidate candidate);
    }
    
    public FirebaseSignalingClient() {
        FirebaseDatabase database = FirebaseDatabase.getInstance();
        databaseRef = database.getReference();
    }
    
    // Set PeerConnection reference (called from BackgroundService)
    public void setPeerConnection(org.webrtc.PeerConnection peerConnection) {
        this.peerConnection = peerConnection;
    }
    
    public void registerBroadcaster(String deviceId) {
        this.deviceId = deviceId;
        deviceRef = databaseRef.child("broadcasters").child(deviceId);
        offerRef = databaseRef.child("offers").child(deviceId);
        answerRef = databaseRef.child("answers").child(deviceId);
        iceCandidatesRef = databaseRef.child("iceCandidates").child(deviceId);
        
        // Register device
        Map<String, Object> deviceInfo = new HashMap<>();
        deviceInfo.put("deviceId", deviceId);
        deviceInfo.put("status", "online");
        deviceInfo.put("timestamp", System.currentTimeMillis());
        deviceRef.setValue(deviceInfo);
        
        // Setup all listeners
        setupViewerConnectionListener();
        setupAnswerListener();
        setupIceCandidateListener();
        
        Log.d(TAG, "Device registered: " + deviceId);
    }
    
    private void setupViewerConnectionListener() {
        viewerConnectionListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean isConnected = snapshot.getValue(Boolean.class);
                if (isConnected != null && isConnected) {
                    Log.d(TAG, "Viewer connected");
                    if (viewerConnectedListener != null) {
                        viewerConnectedListener.onViewerConnected();
                    }
                } else {
                    Log.d(TAG, "Viewer disconnected");
                    if (viewerDisconnectedListener != null) {
                        viewerDisconnectedListener.onViewerDisconnected();
                    }
                }
            }
            
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Viewer connection listener cancelled: " + error.getMessage());
            }
        };
        
        databaseRef.child("viewers").child(deviceId).child("connected")
            .addValueEventListener(viewerConnectionListener);
    }
    
    private void setupAnswerListener() {
        answerListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String answerSdp = snapshot.getValue(String.class);
                if (answerSdp != null && peerConnection != null && !answerSdp.isEmpty()) {
                    Log.d(TAG, "Received answer from viewer");
                    SessionDescription answer = new SessionDescription(
                        SessionDescription.Type.ANSWER, 
                        answerSdp
                    );
                    peerConnection.setRemoteDescription(new SimpleSdpObserver(), answer);
                    // Remove answer after processing
                    answerRef.removeValue();
                }
            }
            
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Answer listener cancelled: " + error.getMessage());
            }
        };
        
        answerRef.addValueEventListener(answerListener);
    }
    
    private void setupIceCandidateListener() {
        iceCandidatesListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                for (DataSnapshot candidateSnapshot : snapshot.getChildren()) {
                    String candidateStr = candidateSnapshot.getValue(String.class);
                    if (candidateStr != null && peerConnection != null) {
                        // Parse ICE candidate (simple format: "sdpMid,sdpMLineIndex,sdp")
                        String[] parts = candidateStr.split(",", 3);
                        if (parts.length == 3) {
                            IceCandidate candidate = new IceCandidate(
                                parts[0],           // sdpMid
                                Integer.parseInt(parts[1]), // sdpMLineIndex
                                parts[2]            // sdp
                            );
                            peerConnection.addIceCandidate(candidate);
                            Log.d(TAG, "ICE candidate added");
                        }
                    }
                    // Remove processed candidate
                    candidateSnapshot.getRef().removeValue();
                }
            }
            
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "ICE candidate listener cancelled: " + error.getMessage());
            }
        };
        
        iceCandidatesRef.addValueEventListener(iceCandidatesListener);
    }
    
    public void sendOffer(SessionDescription offer) {
        if (offer != null && offer.description != null) {
            offerRef.setValue(offer.description);
            Log.d(TAG, "Offer sent to Firebase");
        }
    }
    
    public void sendIceCandidate(IceCandidate candidate) {
        if (candidate != null) {
            // Store candidate as string: "sdpMid,sdpMLineIndex,sdp"
            String candidateStr = candidate.sdpMid + "," + candidate.sdpMLineIndex + "," + candidate.sdp;
            iceCandidatesRef.push().setValue(candidateStr);
            Log.d(TAG, "ICE candidate sent to Firebase");
        }
    }
    
    public void unregisterBroadcaster() {
        // Remove all listeners
        if (viewerConnectionListener != null) {
            databaseRef.child("viewers").child(deviceId).child("connected")
                .removeEventListener(viewerConnectionListener);
        }
        if (answerListener != null) {
            answerRef.removeEventListener(answerListener);
        }
        if (iceCandidatesListener != null) {
            iceCandidatesRef.removeEventListener(iceCandidatesListener);
        }
        
        // Remove device from Firebase
        if (deviceRef != null) {
            deviceRef.removeValue();
        }
        
        // Cleanup signaling paths
        if (offerRef != null) {
            offerRef.removeValue();
        }
        if (answerRef != null) {
            answerRef.removeValue();
        }
        if (iceCandidatesRef != null) {
            iceCandidatesRef.removeValue();
        }
        
        Log.d(TAG, "Broadcaster unregistered: " + deviceId);
    }
    
    // Setter methods for listeners
    public void setOnViewerConnected(OnViewerConnectedListener listener) {
        this.viewerConnectedListener = listener;
    }
    
    public void setOnViewerDisconnected(OnViewerDisconnectedListener listener) {
        this.viewerDisconnectedListener = listener;
    }
    
    public String getDeviceId() {
        return deviceId;
    }
    
    // Simple SDP Observer
    private static class SimpleSdpObserver implements org.webrtc.SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sd) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) {}
        @Override public void onSetFailure(String error) {}
    }
}
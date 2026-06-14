package com.broadcaster;

import android.util.Log;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.gson.Gson;

import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import java.util.HashMap;
import java.util.Map;

public class FirebaseSignalingClient {
    private static final String TAG = "FirebaseSignaling";
    private static final String SIGNALING_PATH = "signaling";
    
    private DatabaseReference databaseReference;
    private String deviceId;
    private SignalingListener listener;
    private ValueEventListener signalingListener;
    private Gson gson;
    
    public interface SignalingListener {
        void onOfferReceived(SessionDescription offer);
        void onAnswerReceived(SessionDescription answer);
        void onIceCandidateReceived(IceCandidate candidate);
        void onHangUp();
    }
    
    // Constructor - no arguments as required by the error
    public FirebaseSignalingClient() {
        this.databaseReference = FirebaseDatabase.getInstance().getReference();
        this.gson = new Gson();
        this.deviceId = null;
    }
    
    // Set device ID after construction if needed
    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }
    
    public void setListener(SignalingListener listener) {
        this.listener = listener;
    }
    
    public void listenForSignals() {
        if (deviceId == null) {
            Log.e(TAG, "Device ID not set. Call setDeviceId() first.");
            return;
        }
        
        DatabaseReference signalsRef = databaseReference.child(SIGNALING_PATH).child(deviceId);
        
        signalingListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                for (DataSnapshot signalSnapshot : dataSnapshot.getChildren()) {
                    String type = signalSnapshot.child("type").getValue(String.class);
                    String data = signalSnapshot.child("data").getValue(String.class);
                    
                    if (type == null || data == null) continue;
                    
                    Log.d(TAG, "Received signal type: " + type);
                    
                    try {
                        switch (type) {
                            case "offer":
                                SessionDescription offer = new SessionDescription(
                                    SessionDescription.Type.OFFER,
                                    data
                                );
                                if (listener != null) {
                                    listener.onOfferReceived(offer);
                                }
                                break;
                                
                            case "answer":
                                SessionDescription answer = new SessionDescription(
                                    SessionDescription.Type.ANSWER,
                                    data
                                );
                                if (listener != null) {
                                    listener.onAnswerReceived(answer);
                                }
                                break;
                                
                            case "ice":
                                IceCandidate candidate = gson.fromJson(data, IceCandidate.class);
                                if (listener != null) {
                                    listener.onIceCandidateReceived(candidate);
                                }
                                break;
                                
                            case "hangup":
                                if (listener != null) {
                                    listener.onHangUp();
                                }
                                break;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing signal: " + e.getMessage());
                    }
                    
                    // Remove processed signal
                    signalSnapshot.getRef().removeValue();
                }
            }
            
            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Signaling listener cancelled: " + databaseError.getMessage());
            }
        };
        
        signalsRef.addValueEventListener(signalingListener);
        Log.d(TAG, "Listening for signals on device: " + deviceId);
    }
    
    public void sendOffer(SessionDescription offer) {
        if (deviceId == null) {
            Log.e(TAG, "Device ID not set. Call setDeviceId() first.");
            return;
        }
        sendSignal("offer", offer.description);
    }
    
    public void sendAnswer(SessionDescription answer) {
        if (deviceId == null) {
            Log.e(TAG, "Device ID not set. Call setDeviceId() first.");
            return;
        }
        sendSignal("answer", answer.description);
    }
    
    public void sendIceCandidate(IceCandidate candidate) {
        if (deviceId == null) {
            Log.e(TAG, "Device ID not set. Call setDeviceId() first.");
            return;
        }
        String iceData = gson.toJson(candidate);
        sendSignal("ice", iceData);
    }
    
    public void sendHangUp() {
        if (deviceId == null) {
            Log.e(TAG, "Device ID not set. Call setDeviceId() first.");
            return;
        }
        sendSignal("hangup", "");
    }
    
    private void sendSignal(String type, String data) {
        DatabaseReference signalsRef = databaseReference.child(SIGNALING_PATH).child(deviceId);
        String key = signalsRef.push().getKey();
        
        if (key != null) {
            Map<String, Object> signal = new HashMap<>();
            signal.put("type", type);
            signal.put("data", data);
            signal.put("timestamp", System.currentTimeMillis());
            
            signalsRef.child(key).setValue(signal)
                .addOnSuccessListener(aVoid -> Log.d(TAG, "Signal sent: " + type))
                .addOnFailureListener(e -> Log.e(TAG, "Failed to send signal: " + type, e));
        }
    }
    
    public void cleanup() {
        if (signalingListener != null && deviceId != null) {
            DatabaseReference signalsRef = databaseReference.child(SIGNALING_PATH).child(deviceId);
            signalsRef.removeEventListener(signalingListener);
            signalsRef.removeValue();
        }
        Log.d(TAG, "Cleanup complete");
    }
}

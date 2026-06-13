package com.broadcaster;

import android.content.Context;
import android.util.Log;
import org.webrtc.*;
import java.util.ArrayList;
import java.util.List;

public class WebRTCManager {
    private static final String TAG = "WebRTCManager";
    
    private Context context;
    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private MediaStream localStream;
    private VideoCapturer cameraCapturer;
    private boolean isStreaming = false;
    
    // Callback interfaces
    private PeerConnectionCreatedCallback peerConnectionCreatedCallback;
    private OfferCreatedCallback offerCreatedCallback;
    private IceCandidateCreatedCallback iceCandidateCreatedCallback;
    
    // ICE Servers: STUN + Metered TURN (Asia region for Pakistan)
    private static final List<PeerConnection.IceServer> ICE_SERVERS = new ArrayList<>();
    static {
        // STUN servers (tried first - direct connection)
        ICE_SERVERS.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        ICE_SERVERS.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());
        ICE_SERVERS.add(PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer());
        ICE_SERVERS.add(PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer());
        ICE_SERVERS.add(PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer());
        
        // Metered TURN - Asia region (optimized for Pakistan)
        // UDP - Port 80
        ICE_SERVERS.add(PeerConnection.IceServer.builder("turn:asia.relay.metered.ca:80")
            .setUsername("ef24325ba7b48683eea77921")
            .setPassword("A7u6UJTnE6KimONG")
            .createIceServer());
        
        // TCP fallback - Port 80
        ICE_SERVERS.add(PeerConnection.IceServer.builder("turn:asia.relay.metered.ca:80?transport=tcp")
            .setUsername("ef24325ba7b48683eea77921")
            .setPassword("A7u6UJTnE6KimONG")
            .createIceServer());
        
        // TLS over TCP - Port 443 (most secure)
        ICE_SERVERS.add(PeerConnection.IceServer.builder("turns:asia.relay.metered.ca:443?transport=tcp")
            .setUsername("ef24325ba7b48683eea77921")
            .setPassword("A7u6UJTnE6KimONG")
            .createIceServer());
    }
    
    // Callback interfaces definition
    public interface PeerConnectionCreatedCallback {
        void onPeerConnectionCreated(PeerConnection peerConnection);
    }
    
    public interface OfferCreatedCallback {
        void onOfferCreated(SessionDescription offer);
    }
    
    public interface IceCandidateCreatedCallback {
        void onIceCandidateCreated(IceCandidate candidate);
    }
    
    // Setter methods for callbacks
    public void setPeerConnectionCreatedCallback(PeerConnectionCreatedCallback callback) {
        this.peerConnectionCreatedCallback = callback;
    }
    
    public void setOfferCreatedCallback(OfferCreatedCallback callback) {
        this.offerCreatedCallback = callback;
    }
    
    public void setIceCandidateCreatedCallback(IceCandidateCreatedCallback callback) {
        this.iceCandidateCreatedCallback = callback;
    }
    
    // Notification methods
    private void notifyPeerConnectionCreated(PeerConnection peerConnection) {
        if (peerConnectionCreatedCallback != null) {
            peerConnectionCreatedCallback.onPeerConnectionCreated(peerConnection);
        }
    }
    
    private void notifyOfferCreated(SessionDescription offer) {
        if (offerCreatedCallback != null) {
            offerCreatedCallback.onOfferCreated(offer);
        }
    }
    
    private void notifyIceCandidateCreated(IceCandidate candidate) {
        if (iceCandidateCreatedCallback != null) {
            iceCandidateCreatedCallback.onIceCandidateCreated(candidate);
        }
    }
    
    public WebRTCManager(Context context) {
        this.context = context;
        initializeWebRTC();
    }
    
    private void initializeWebRTC() {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                .builder(context)
                .createInitializationOptions());
        
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(
                        new EglBase.ContextImpl(EglBase.createEgl14().getEglBaseContext()), 
                        true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(
                        new EglBase.ContextImpl(EglBase.createEgl14().getEglBaseContext())))
                .createPeerConnectionFactory();
    }
    
    public void startBroadcasting() {
        if (isStreaming) return;
        
        try {
            localStream = factory.createLocalMediaStream("ARDAMS");
            
            VideoTrack videoTrack = createVideoTrack();
            if (videoTrack != null) {
                localStream.addTrack(videoTrack);
            }
            
            AudioTrack audioTrack = createAudioTrack();
            if (audioTrack != null) {
                localStream.addTrack(audioTrack);
            }
            
            PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(ICE_SERVERS);
            peerConnection = factory.createPeerConnection(rtcConfig, new PeerConnectionObserver());
            
            notifyPeerConnectionCreated(peerConnection);
            peerConnection.addStream(localStream);
            
            peerConnection.createOffer(new SdpObserver() {
                @Override
                public void onCreateSuccess(SessionDescription sdp) {
                    peerConnection.setLocalDescription(new SdpObserver() {
                        @Override
                        public void onSetSuccess() {
                            Log.d(TAG, "Local description set");
                            sendOfferToFirebase(sdp);
                            notifyOfferCreated(sdp);
                        }
                        @Override public void onSetFailure(String error) {}
                        @Override public void onCreateSuccess(SessionDescription sd) {}
                        @Override public void onCreateFailure(String error) {}
                    }, sdp);
                }
                
                @Override
                public void onCreateFailure(String error) {
                    Log.e(TAG, "Create offer failed: " + error);
                }
            }, new MediaConstraints());
            
            isStreaming = true;
            Log.d(TAG, "Broadcasting started with STUN + Metered TURN (Asia region)");
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting broadcast: " + e.getMessage());
        }
    }
    
    private VideoTrack createVideoTrack() {
        cameraCapturer = createCameraCapturer();
        if (cameraCapturer == null) {
            Log.e(TAG, "Failed to open camera");
            return null;
        }
        
        SurfaceTextureHelper surfaceHelper = SurfaceTextureHelper.create("CaptureThread", 
            new EglBase.ContextImpl(EglBase.createEgl14().getEglBaseContext()));
        
        VideoSource videoSource = factory.createVideoSource(cameraCapturer.isScreencast());
        cameraCapturer.initialize(surfaceHelper, context, videoSource.getCapturerObserver());
        cameraCapturer.startCapture(640, 480, 15);
        
        return factory.createVideoTrack("ARDAMSv0", videoSource);
    }
    
    private VideoCapturer createCameraCapturer() {
        CameraEnumerator enumerator = new Camera1Enumerator();
        String[] deviceNames = enumerator.getDeviceNames();
        
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                VideoCapturer capturer = enumerator.createCapturer(deviceName, null);
                if (capturer != null) {
                    return capturer;
                }
            }
        }
        
        for (String deviceName : deviceNames) {
            VideoCapturer capturer = enumerator.createCapturer(deviceName, null);
            if (capturer != null) {
                return capturer;
            }
        }
        return null;
    }
    
    private AudioTrack createAudioTrack() {
        AudioSource audioSource = factory.createAudioSource(new MediaConstraints());
        return factory.createAudioTrack("ARDAMSa0", audioSource);
    }
    
    private void sendOfferToFirebase(SessionDescription sdp) {
        FirebaseSignalingClient signalingClient = BackgroundService.getInstance().getSignalingClient();
        if (signalingClient != null) {
            signalingClient.sendOffer(sdp);
        }
    }
    
    public void stopBroadcasting() {
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }
        
        if (localStream != null) {
            localStream.dispose();
            localStream = null;
        }
        
        if (cameraCapturer != null) {
            try {
                cameraCapturer.stopCapture();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            cameraCapturer.dispose();
            cameraCapturer = null;
        }
        
        isStreaming = false;
        Log.d(TAG, "Broadcasting stopped");
    }
    
    public void close() {
        stopBroadcasting();
        if (factory != null) {
            factory.dispose();
        }
    }
    
    public boolean isStreaming() {
        return isStreaming;
    }
    
    public PeerConnection getPeerConnection() {
        return peerConnection;
    }
    
    // PeerConnection Observer
    private class PeerConnectionObserver implements PeerConnection.Observer {
        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            Log.d(TAG, "ICE Candidate: " + iceCandidate.sdp);
            
            // Detect candidate type
            if (iceCandidate.sdp.contains("typ host")) {
                Log.d(TAG, "📡 Candidate: HOST (same network)");
            } else if (iceCandidate.sdp.contains("typ srflx")) {
                Log.d(TAG, "📡 Candidate: STUN (direct connection)");
            } else if (iceCandidate.sdp.contains("typ relay")) {
                Log.d(TAG, "📡 Candidate: TURN (relay via Asia server)");
            }
            
            FirebaseSignalingClient signalingClient = BackgroundService.getInstance().getSignalingClient();
            if (signalingClient != null) {
                signalingClient.sendIceCandidate(iceCandidate);
                notifyIceCandidateCreated(iceCandidate);
            }
        }
        
        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState newState) {
            Log.d(TAG, "ICE Connection State: " + newState);
            
            if (newState == PeerConnection.IceConnectionState.CONNECTED) {
                Log.d(TAG, "✅ WebRTC connection established via Asia TURN!");
            } else if (newState == PeerConnection.IceConnectionState.FAILED) {
                Log.e(TAG, "❌ ICE connection failed");
            }
        }
        
        @Override
        public void onAddStream(MediaStream mediaStream) {
            Log.d(TAG, "Stream added");
        }
        
        @Override public void onSignalingChange(PeerConnection.SignalingState newState) {}
        @Override public void onIceConnectionReceivingChange(boolean receiving) {}
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState newState) {}
        @Override public void onRemoveStream(MediaStream mediaStream) {}
        @Override public void onDataChannel(DataChannel dataChannel) {}
        @Override public void onRenegotiationNeeded() {}
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
        @Override public void onSelectedCandidatePairChanged(CandidatePairChangeEvent event) {}
    }
    
    private abstract class SdpObserver implements org.webrtc.SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sd) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) {}
        @Override public void onSetFailure(String error) {}
    }
}
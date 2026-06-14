package com.broadcaster;

import android.content.Context;
import android.util.Log;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.List;

public class WebRTCManager {
    private static final String TAG = "WebRTCManager";
    private static final String VIDEO_TRACK_ID = "video_track";
    private static final String AUDIO_TRACK_ID = "audio_track";
    
    private Context context;
    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private EglBase rootEglBase;
    private SurfaceTextureHelper surfaceHelper;
    private VideoCapturer videoCapturer;
    
    private WebRTCCallback callback;
    private List<IceCandidate> pendingIceCandidates = new ArrayList<>();
    
    // STUN/TURN servers
    private static final List<PeerConnection.IceServer> ICE_SERVERS = new ArrayList<>();
    static {
        // Google STUN servers
        ICE_SERVERS.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        ICE_SERVERS.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());
        ICE_SERVERS.add(PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer());
        ICE_SERVERS.add(PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer());
        ICE_SERVERS.add(PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer());
        
        // Metered TURN server (replace with your credentials)
        // ICE_SERVERS.add(PeerConnection.IceServer.builder("turn:asia.relay.metered.ca:80")
        //     .setUsername("YOUR_USERNAME")
        //     .setPassword("YOUR_PASSWORD")
        //     .createIceServer());
    }
    
    public interface WebRTCCallback {
        void onLocalDescription(SessionDescription sdp);
        void onIceCandidate(IceCandidate candidate);
        void onConnected();
        void onDisconnected();
        void onError(String error);
    }
    
    public WebRTCManager(Context context) {
        this.context = context;
        initPeerConnectionFactory();
    }
    
    public void setCallback(WebRTCCallback callback) {
        this.callback = callback;
    }
    
    private void initPeerConnectionFactory() {
        // Initialize EGL base - FIXED: Use EglBase.create() instead of deprecated methods
        rootEglBase = EglBase.create();
        
        // Initialize PeerConnectionFactory
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                .builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions());
        
        // Create factory with hardware acceleration
        DefaultVideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(
                rootEglBase.getEglBaseContext(), true, true);
        DefaultVideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(
                rootEglBase.getEglBaseContext());
        
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();
    }
    
    public void createPeerConnection() {
        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(ICE_SERVERS);
        rtcConfig.iceTransportsType = PeerConnection.IceTransportsType.ALL;
        rtcConfig.iceServers = ICE_SERVERS;
        
        peerConnection = factory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(IceCandidate candidate) {
                Log.d(TAG, "onIceCandidate: " + candidate);
                if (callback != null) {
                    callback.onIceCandidate(candidate);
                }
            }
            
            @Override
            public void onIceCandidatesRemoved(IceCandidate[] candidates) {
                Log.d(TAG, "onIceCandidatesRemoved");
            }
            
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                Log.d(TAG, "onSignalingChange: " + signalingState);
            }
            
            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                Log.d(TAG, "onIceConnectionChange: " + iceConnectionState);
                if (iceConnectionState == PeerConnection.IceConnectionState.CONNECTED) {
                    if (callback != null) {
                        callback.onConnected();
                    }
                } else if (iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED ||
                           iceConnectionState == PeerConnection.IceConnectionState.FAILED) {
                    if (callback != null) {
                        callback.onDisconnected();
                    }
                }
            }
            
            @Override
            public void onIceConnectionReceivingChange(boolean receiving) {
                Log.d(TAG, "onIceConnectionReceivingChange: " + receiving);
            }
            
            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                Log.d(TAG, "onIceGatheringChange: " + iceGatheringState);
            }
            
            @Override
            public void onAddStream(MediaStream stream) {
                Log.d(TAG, "onAddStream");
            }
            
            @Override
            public void onRemoveStream(MediaStream stream) {
                Log.d(TAG, "onRemoveStream");
            }
            
            @Override
            public void onDataChannel(DataChannel dataChannel) {
                Log.d(TAG, "onDataChannel");
            }
            
            @Override
            public void onRenegotiationNeeded() {
                Log.d(TAG, "onRenegotiationNeeded");
            }
            
            @Override
            public void onAddTrack(org.webrtc.RtpReceiver receiver, MediaStream[] mediaStreams) {
                Log.d(TAG, "onAddTrack");
            }
        });
    }
    
    public void startStreaming() {
        if (peerConnection == null) {
            createPeerConnection();
        }
        
        createVideoAndAudioTracks();
        
        MediaStream stream = factory.createLocalMediaStream("stream");
        stream.addTrack(videoTrack);
        stream.addTrack(audioTrack);
        peerConnection.addStream(stream);
        
        createOffer();
    }
    
    private void createVideoAndAudioTracks() {
        // Create video source and track
        videoSource = factory.createVideoSource(isScreencast());
        videoCapturer = createCameraCapturer();
        
        if (videoCapturer != null) {
            // FIXED: Use rootEglBase.getEglBaseContext() directly
            surfaceHelper = SurfaceTextureHelper.create("VideoThread", rootEglBase.getEglBaseContext());
            videoCapturer.initialize(surfaceHelper, context, videoSource.getCapturerObserver());
            videoCapturer.startCapture(640, 480, 30);
        }
        
        videoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource);
        videoTrack.setEnabled(true);
        
        // Create audio source and track
        MediaConstraints audioConstraints = new MediaConstraints();
        audioSource = factory.createAudioSource(audioConstraints);
        audioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource);
        audioTrack.setEnabled(true);
    }
    
    private VideoCapturer createCameraCapturer() {
        CameraEnumerator enumerator;
        if (Camera2Enumerator.isSupported(context)) {
            enumerator = new Camera2Enumerator(context);
        } else {
            enumerator = new Camera1Enumerator();
        }
        
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
            if (!enumerator.isFrontFacing(deviceName)) {
                VideoCapturer capturer = enumerator.createCapturer(deviceName, null);
                if (capturer != null) {
                    return capturer;
                }
            }
        }
        
        return null;
    }
    
    private boolean isScreencast() {
        return false;
    }
    
    private void createOffer() {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        
        peerConnection.createOffer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                Log.d(TAG, "createOffer success");
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sdp) {}
                    
                    @Override
                    public void onSetSuccess() {
                        Log.d(TAG, "setLocalDescription success");
                        if (callback != null) {
                            callback.onLocalDescription(sdp);
                        }
                    }
                    
                    @Override
                    public void onCreateFailure(String error) {
                        Log.e(TAG, "setLocalDescription failed: " + error);
                    }
                    
                    @Override
                    public void onSetFailure(String error) {
                        Log.e(TAG, "setLocalDescription failed: " + error);
                    }
                }, sdp);
            }
            
            @Override
            public void onSetSuccess() {}
            
            @Override
            public void onCreateFailure(String error) {
                Log.e(TAG, "createOffer failed: " + error);
                if (callback != null) {
                    callback.onError("Create offer failed: " + error);
                }
            }
            
            @Override
            public void onSetFailure(String error) {}
        }, constraints);
    }
    
    public void setRemoteDescription(SessionDescription sdp) {
        if (peerConnection == null) {
            createPeerConnection();
        }
        
        peerConnection.setRemoteDescription(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {}
            
            @Override
            public void onSetSuccess() {
                Log.d(TAG, "setRemoteDescription success");
                // Process any pending ICE candidates
                for (IceCandidate candidate : pendingIceCandidates) {
                    peerConnection.addIceCandidate(candidate);
                }
                pendingIceCandidates.clear();
            }
            
            @Override
            public void onCreateFailure(String error) {}
            
            @Override
            public void onSetFailure(String error) {
                Log.e(TAG, "setRemoteDescription failed: " + error);
            }
        }, sdp);
    }
    
    public void addIceCandidate(IceCandidate candidate) {
        if (peerConnection != null && peerConnection.getRemoteDescription() != null) {
            peerConnection.addIceCandidate(candidate);
        } else {
            pendingIceCandidates.add(candidate);
        }
    }
    
    public void createAnswer() {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        
        peerConnection.createAnswer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sdp) {}
                    
                    @Override
                    public void onSetSuccess() {
                        if (callback != null) {
                            callback.onLocalDescription(sdp);
                        }
                    }
                    
                    @Override
                    public void onCreateFailure(String error) {}
                    
                    @Override
                    public void onSetFailure(String error) {}
                }, sdp);
            }
            
            @Override
            public void onSetSuccess() {}
            
            @Override
            public void onCreateFailure(String error) {
                Log.e(TAG, "createAnswer failed: " + error);
            }
            
            @Override
            public void onSetFailure(String error) {}
        }, constraints);
    }
    
    public void stopStreaming() {
        if (videoCapturer != null) {
            try {
                videoCapturer.stopCapture();
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping capture: " + e.getMessage());
            }
            videoCapturer.dispose();
            videoCapturer = null;
        }
        
        if (surfaceHelper != null) {
            surfaceHelper.dispose();
            surfaceHelper = null;
        }
        
        if (videoTrack != null) {
            videoTrack.dispose();
            videoTrack = null;
        }
        
        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }
        
        if (audioTrack != null) {
            audioTrack.dispose();
            audioTrack = null;
        }
        
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }
        
        if (peerConnection != null) {
            peerConnection.dispose();
            peerConnection = null;
        }
        
        if (rootEglBase != null) {
            rootEglBase.release();
            rootEglBase = null;
        }
    }
    
    public void dispose() {
        stopStreaming();
        if (factory != null) {
            factory.dispose();
            factory = null;
        }
    }
}

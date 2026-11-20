package com.familylink.core.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Wraps WebRTC PeerConnectionFactory, shared EglBase, local microphone, front/back camera,
 * and MediaProjection screen capturer with hardware-accelerated codecs.
 */
class WebRtcEngine(private val context: Context) {

    val eglBase: EglBase = EglBase.create()
    val peerConnectionFactory: PeerConnectionFactory

    private var audioSource: AudioSource? = null
    private var cameraVideoSource: VideoSource? = null
    private var screenVideoSource: VideoSource? = null
    private var cameraCapturer: CameraVideoCapturer? = null
    private var screenCapturer: VideoCapturer? = null
    private var cameraTextureHelper: SurfaceTextureHelper? = null
    private var screenTextureHelper: SurfaceTextureHelper? = null

    var localAudioTrack: AudioTrack? = null
        private set
    var localCameraTrack: VideoTrack? = null
        private set
    var localScreenTrack: VideoTrack? = null
        private set

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun startLocalMedia(enableVideo: Boolean, profile: VideoQualityProfile) {
        if (localAudioTrack == null) {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            }
            audioSource = peerConnectionFactory.createAudioSource(constraints)
            localAudioTrack = peerConnectionFactory.createAudioTrack("fl_audio_local", audioSource)
            localAudioTrack?.setEnabled(true)
        }
        if (enableVideo && localCameraTrack == null) {
            startCameraCapture(profile)
        }
    }

    fun startCameraCapture(profile: VideoQualityProfile) {
        if (localCameraTrack != null) {
            localCameraTrack?.setEnabled(true)
            return
        }
        val capturer = createCameraCapturer() ?: return
        cameraCapturer = capturer
        cameraTextureHelper = SurfaceTextureHelper.create("CameraCaptureThread", eglBase.eglBaseContext)
        cameraVideoSource = peerConnectionFactory.createVideoSource(capturer.isScreencast)
        capturer.initialize(cameraTextureHelper, context.applicationContext, cameraVideoSource!!.capturerObserver)
        try {
            capturer.startCapture(profile.width, profile.height, profile.fps)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start camera capture: ${e.message}")
        }
        localCameraTrack = peerConnectionFactory.createVideoTrack("fl_video_local", cameraVideoSource).apply {
            setEnabled(true)
        }
    }

    fun updateCameraFormat(profile: VideoQualityProfile) {
        try {
            cameraCapturer?.changeCaptureFormat(profile.width, profile.height, profile.fps)
        } catch (_: Exception) {
        }
    }

    fun flipCamera() {
        cameraCapturer?.switchCamera(null)
    }

    fun startScreenShare(mediaProjectionData: Intent, onStopped: () -> Unit): VideoTrack? {
        stopScreenShare()
        val capturer = ScreenCapturerAndroid(
            mediaProjectionData,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    onStopped()
                }
            }
        )
        screenCapturer = capturer
        screenTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase.eglBaseContext)
        screenVideoSource = peerConnectionFactory.createVideoSource(true)
        capturer.initialize(screenTextureHelper, context.applicationContext, screenVideoSource!!.capturerObserver)
        val profile = AdaptiveBitratePolicy.profileForParticipantCount(2, isScreenShare = true)
        capturer.startCapture(profile.width, profile.height, profile.fps)
        val track = peerConnectionFactory.createVideoTrack("fl_screen_local", screenVideoSource).apply {
            setEnabled(true)
        }
        localScreenTrack = track
        return track
    }

    fun stopScreenShare() {
        try {
            screenCapturer?.stopCapture()
        } catch (_: Exception) {
        }
        screenCapturer?.dispose()
        screenCapturer = null
        localScreenTrack?.dispose()
        localScreenTrack = null
        screenVideoSource?.dispose()
        screenVideoSource = null
        screenTextureHelper?.dispose()
        screenTextureHelper = null
    }

    fun createPeerConnection(
        iceServers: List<PeerConnection.IceServer>,
        observer: PeerConnection.Observer
    ): PeerConnection? {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        }
        return peerConnectionFactory.createPeerConnection(rtcConfig, observer)
    }

    fun stopLocalMedia() {
        stopScreenShare()
        try {
            cameraCapturer?.stopCapture()
        } catch (_: Exception) {
        }
        cameraCapturer?.dispose()
        cameraCapturer = null
        localCameraTrack?.dispose()
        localCameraTrack = null
        cameraVideoSource?.dispose()
        cameraVideoSource = null
        cameraTextureHelper?.dispose()
        cameraTextureHelper = null

        localAudioTrack?.dispose()
        localAudioTrack = null
        audioSource?.dispose()
        audioSource = null
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames
        val front = names.firstOrNull { enumerator.isFrontFacing(it) }
        val chosen = front ?: names.firstOrNull() ?: return null
        return enumerator.createCapturer(chosen, null)
    }

    companion object {
        private const val TAG = "WebRtcEngine"
    }
}

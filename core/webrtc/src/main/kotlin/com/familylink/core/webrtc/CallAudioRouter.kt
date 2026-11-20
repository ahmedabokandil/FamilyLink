package com.familylink.core.webrtc

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Manages Android audio routing during P2P voice & video calls (earpiece, speakerphone,
 * wired headset, and Bluetooth communication devices).
 */
class CallAudioRouter(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousMode: Int = AudioManager.MODE_NORMAL

    fun startCallAudio(preferSpeaker: Boolean) {
        previousMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeakerphone(preferSpeaker)
    }

    fun setSpeakerphone(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val target = if (enabled) {
                devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            } else {
                devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                    ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
                    ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            }
            if (target != null) {
                audioManager.setCommunicationDevice(target)
                return
            }
        }
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = enabled
    }

    fun stopCallAudio() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
        audioManager.mode = previousMode
    }
}

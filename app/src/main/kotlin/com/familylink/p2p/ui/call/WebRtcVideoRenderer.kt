package com.familylink.p2p.ui.call

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@Composable
fun WebRtcVideoRenderer(
    videoTrack: VideoTrack?,
    eglContext: EglBase.Context,
    mirror: Boolean = false,
    zOrderMediaOverlay: Boolean = false,
    modifier: Modifier = Modifier
) {
    var rendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    DisposableEffect(videoTrack, rendererRef) {
        val view = rendererRef
        if (view != null && videoTrack != null) {
            videoTrack.addSink(view)
        }
        onDispose {
            if (view != null && videoTrack != null) {
                runCatching { videoTrack.removeSink(view) }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            rendererRef?.release()
            rendererRef = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(eglContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                setMirror(mirror)
                setZOrderMediaOverlay(zOrderMediaOverlay)
                rendererRef = this
            }
        },
        update = { view ->
            view.setMirror(mirror)
        }
    )
}

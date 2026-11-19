package org.tucsoncommunity.kokoho.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.tucsoncommunity.kokoho.posture.DeviceTrustManager
import org.tucsoncommunity.kokoho.posture.PriorityVault
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Zero-GMS Camera2 + ZXing QR scanner & deep-link handler for single-session Hub authorization
 * (`kokoho://web-auth?c=...&s=...`) and invite seat enrollment (`kokoho://enroll?invite=...` / `/dashboard?invite=...`).
 */
object WebAuthQrDialog {

    const val CAMERA_PERMISSION_REQ_CODE = 49102

    fun openScannerOrRequestPermission(activity: AppCompatActivity) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQ_CODE
            )
            return
        }
        showScannerDialog(activity)
    }

    internal fun extractInviteKey(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val uri = runCatching { Uri.parse(text) }.getOrNull()
        if (uri != null) {
            if (uri.scheme.equals("kokoho", ignoreCase = true) &&
                uri.host.equals("enroll", ignoreCase = true)
            ) {
                val inv = (uri.getQueryParameter("invite") ?: uri.getQueryParameter("slug"))?.trim().orEmpty()
                if (inv.isNotEmpty()) return inv
            }
            if (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) {
                val path = uri.path.orEmpty()
                if (path == "/dashboard" || path == "/dashboard/") {
                    val inv = uri.getQueryParameter("invite")?.trim().orEmpty()
                    if (inv.isNotEmpty()) return inv
                }
            }
        }
        if (text.contains("/s/")) {
            val slug = text.substringAfter("/s/").substringBefore('?').substringBefore('/').trim()
            if (slug.isNotEmpty()) return slug
        }
        return null
    }

    fun handleDeepLinkUri(activity: AppCompatActivity, uri: Uri?): Boolean {
        if (uri == null) return false
        if (uri.scheme.equals("kokoho", ignoreCase = true) &&
            uri.host.equals("web-auth", ignoreCase = true)
        ) {
            val c = uri.getQueryParameter("c")?.trim().orEmpty()
            val s = uri.getQueryParameter("s")?.trim().orEmpty()
            val u = uri.getQueryParameter("u")?.trim()?.takeIf { it.isNotEmpty() }
            if (c.isNotEmpty() && s.isNotEmpty()) {
                promptConfirmWebGrant(activity, c, s, u, fromDeepLink = true)
                return true
            }
        }
        if (uri.scheme.equals("kokoho", ignoreCase = true) &&
            uri.host.equals("enroll", ignoreCase = true)
        ) {
            val inviteKey = (uri.getQueryParameter("invite") ?: uri.getQueryParameter("slug"))?.trim().orEmpty()
            if (inviteKey.isNotEmpty()) {
                handleInviteClaim(activity, inviteKey, fromDeepLink = true)
                return true
            }
        }
        return false
    }

    fun handleRawQrPayload(activity: AppCompatActivity, raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty()) return false
        val uri = runCatching { Uri.parse(text) }.getOrNull()
        if (uri != null && uri.scheme.equals("kokoho", ignoreCase = true) &&
            uri.host.equals("web-auth", ignoreCase = true)
        ) {
            val c = uri.getQueryParameter("c")?.trim().orEmpty()
            val s = uri.getQueryParameter("s")?.trim().orEmpty()
            val u = uri.getQueryParameter("u")?.trim()?.takeIf { it.isNotEmpty() }
            if (c.isNotEmpty() && s.isNotEmpty()) {
                promptConfirmWebGrant(activity, c, s, u, fromDeepLink = false)
                return true
            }
        }
        val inviteKey = extractInviteKey(text)
        if (!inviteKey.isNullOrEmpty()) {
            handleInviteClaim(activity, inviteKey, fromDeepLink = false)
            return true
        }
        return false
    }

    fun handleInviteClaim(
        activity: AppCompatActivity,
        inviteKey: String,
        fromDeepLink: Boolean = false
    ) {
        val cleanKey = inviteKey.trim().substringAfterLast('/').substringBefore('?').substringBefore('&').substringBefore('#').trim()
        if (cleanKey.isEmpty() || activity.isFinishing || activity.isDestroyed) return

        DeviceTrustManager.claimSeatBySlug(activity, cleanKey) { ok, msg ->
            if (ok) {
                PriorityVault.syncAllAsync(activity)
            }
            showAuthResultModal(
                activity = activity,
                ok = ok,
                message = msg,
                isSeatClaim = true,
                fromDeepLink = fromDeepLink
            )
        }
    }

    private fun resolveAppLabel(activity: AppCompatActivity): String =
        runCatching {
            activity.applicationInfo.loadLabel(activity.packageManager).toString().trim()
        }.getOrDefault("").ifEmpty { "Kokoho" }

    private fun showAuthResultModal(
        activity: AppCompatActivity,
        ok: Boolean,
        message: String,
        isSeatClaim: Boolean = false,
        fromDeepLink: Boolean = false
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val appLabel = resolveAppLabel(activity)
        val title = when {
            ok && isSeatClaim -> "Device Enrolled"
            isSeatClaim -> "Enrollment Not Completed"
            ok -> "Browser Session Authorized"
            else -> "Authorization Not Completed"
        }
        val subtitle = if (ok) {
            "Confirmed in $appLabel"
        } else {
            "Handled by $appLabel"
        }
        val bodyCopy = buildString {
            append(message.trim())
            if (ok && fromDeepLink && !message.contains("Switch back to your browser", ignoreCase = true)) {
                append("\n\nSwitch back to your browser — your Hub session is now unlocked.")
            }
            if (!ok) {
                append("\n\nHandled by app: ")
                append(appLabel)
            }
        }
        KokohoJourneyUi.showModal(
            activity = activity,
            title = title,
            subtitle = subtitle
        ) { body, dismiss ->
            val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
            val msgTv = TextView(activity).apply {
                text = bodyCopy
                textSize = 14.5f
                setTextColor(KokohoJourneyUi.INK_PRIMARY)
                setLineSpacing(0f, 1.28f)
                setPadding(0, dp(2), 0, dp(14))
            }
            body.addView(msgTv)

            val btnRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            val doneBtn = KokohoJourneyUi.primaryActionButton(activity, if (ok) "Done" else "OK") {
                dismiss()
            }
            btnRow.addView(doneBtn)
            body.addView(btnRow)
        }
    }

    private fun promptConfirmWebGrant(
        activity: AppCompatActivity,
        challengeId: String,
        secret: String,
        slugHint: String?,
        fromDeepLink: Boolean
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val appLabel = resolveAppLabel(activity)
        KokohoJourneyUi.showModal(
            activity = activity,
            title = "Authorize Web Session?",
            subtitle = "$appLabel · Grants a single-session Hub token that expires when the browser closes."
        ) { body, dismiss ->
            val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
            val btnRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(10), 0, 0)
            }
            val cancelBtn = KokohoJourneyUi.secondaryActionButton(activity, "Cancel") {
                dismiss()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            }
            val authBtn = KokohoJourneyUi.primaryActionButton(activity, "Authorize Session") {
                dismiss()
                DeviceTrustManager.grantWebSession(activity, challengeId, secret, slugHint) { ok, msg ->
                    showAuthResultModal(
                        activity = activity,
                        ok = ok,
                        message = msg,
                        isSeatClaim = false,
                        fromDeepLink = fromDeepLink
                    )
                }
            }
            btnRow.addView(cancelBtn)
            btnRow.addView(authBtn)
            body.addView(btnRow)
        }
    }

    @SuppressLint("MissingPermission")
    private fun showScannerDialog(activity: AppCompatActivity) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        val modalW = (activity.resources.displayMetrics.widthPixels * 0.90f).toInt()

        val dialog = KokohoJourneyUi.createCustomModal(activity)
        val outer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = modalW
            background = KokohoJourneyUi.cardDrawable(activity, KokohoJourneyUi.SURFACE_DARK, KokohoJourneyUi.BORDER_STRONG, 18)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val titleTv = TextView(activity).apply {
            text = "Scan Hub QR Code"
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(KokohoJourneyUi.INK_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = ImageButton(activity).apply {
            contentDescription = "Close scanner"
            background = KokohoJourneyUi.pillDrawable(activity, KokohoJourneyUi.SURFACE_ELEVATED, KokohoJourneyUi.BORDER_SUBTLE)
            setImageDrawable(KokohoJourneyUi.VectorIconDrawable(activity, KokohoJourneyUi.VectorIconKind.CLOSE_X))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(titleTv)
        header.addView(closeBtn)
        outer.addView(header)

        val statusHint = TextView(activity).apply {
            val status = DeviceTrustManager.enrollmentStatus(activity)
            text = if (status == "ENROLLED") {
                "Point camera at the Hub browser QR code to grant a single-session login."
            } else {
                "Point camera at your Hub invitation QR code to enroll this phone."
            }
            textSize = 13f
            setTextColor(KokohoJourneyUi.INK_SECONDARY)
            setPadding(0, 0, 0, dp(10))
        }
        outer.addView(statusHint)

        val previewFrame = FrameLayout(activity).apply {
            background = KokohoJourneyUi.cardDrawable(activity, 0xFF000000.toInt(), KokohoJourneyUi.BORDER_SUBTLE, 12)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280))
        }
        val textureView = TextureView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        previewFrame.addView(textureView)
        outer.addView(previewFrame)

        var cameraThread: HandlerThread? = null
        var cameraHandler: Handler? = null
        var cameraDevice: CameraDevice? = null
        var captureSession: CameraCaptureSession? = null
        var imageReader: ImageReader? = null
        val consumed = AtomicBoolean(false)
        val qrReader = QRCodeReader()
        val qrHints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
            put(DecodeHintType.TRY_HARDER, true)
        }

        fun releaseCamera() {
            runCatching { captureSession?.close() }
            captureSession = null
            runCatching { cameraDevice?.close() }
            cameraDevice = null
            runCatching { imageReader?.close() }
            imageReader = null
            runCatching { cameraThread?.quitSafely() }
            cameraThread = null
            cameraHandler = null
        }

        dialog.setOnDismissListener { releaseCamera() }

        fun startCameraPreview(st: SurfaceTexture) {
            val mgr = activity.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
            val camId = runCatching {
                mgr.cameraIdList.firstOrNull { id ->
                    mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_BACK
                } ?: mgr.cameraIdList.firstOrNull()
            }.getOrNull() ?: return

            val thread = HandlerThread("kokoho-qr-cam").apply { start() }
            cameraThread = thread
            val handler = Handler(thread.looper)
            cameraHandler = handler

            st.setDefaultBufferSize(640, 480)
            val previewSurface = Surface(st)
            val reader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
            imageReader = reader

            reader.setOnImageAvailableListener({ ir ->
                val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    if (consumed.get()) return@setOnImageAvailableListener
                    val plane = img.planes.firstOrNull() ?: return@setOnImageAvailableListener
                    val buf = plane.buffer
                    val rowStride = plane.rowStride
                    val w = img.width
                    val h = img.height
                    val yBytes = ByteArray(w * h)
                    if (rowStride == w) {
                        buf.get(yBytes, 0, w * h)
                    } else {
                        val rowBuf = ByteArray(rowStride)
                        for (row in 0 until h) {
                            val toRead = if (row == h - 1) minOf(rowStride, buf.remaining()) else rowStride
                            buf.get(rowBuf, 0, toRead)
                            System.arraycopy(rowBuf, 0, yBytes, row * w, w)
                        }
                    }
                    val source = PlanarYUVLuminanceSource(yBytes, w, h, 0, 0, w, h, false)
                    val bitmap = BinaryBitmap(HybridBinarizer(source))
                    val decoded = runCatching { qrReader.decode(bitmap, qrHints) }.getOrNull()
                    qrReader.reset()
                    val text = decoded?.text?.trim()
                    if (!text.isNullOrEmpty() && consumed.compareAndSet(false, true)) {
                        activity.runOnUiThread {
                            dialog.dismiss()
                            if (!handleRawQrPayload(activity, text)) {
                                showAuthResultModal(
                                    activity = activity,
                                    ok = false,
                                    message = "That QR code is not a Kokoho Hub invitation or web session code.",
                                    isSeatClaim = false,
                                    fromDeepLink = false
                                )
                            }
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    img.close()
                }
            }, handler)

            runCatching {
                mgr.openCamera(camId, object : CameraDevice.StateCallback() {
                    override fun onOpened(cam: CameraDevice) {
                        cameraDevice = cam
                        runCatching {
                            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(previewSurface)
                                addTarget(reader.surface)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }
                            @Suppress("DEPRECATION")
                            cam.createCaptureSession(
                                listOf(previewSurface, reader.surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        captureSession = session
                                        runCatching { session.setRepeatingRequest(req.build(), null, handler) }
                                    }
                                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                                },
                                handler
                            )
                        }
                    }

                    override fun onDisconnected(cam: CameraDevice) {
                        cam.close()
                    }

                    override fun onError(cam: CameraDevice, error: Int) {
                        cam.close()
                    }
                }, handler)
            }
        }

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                startCameraPreview(surface)
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        dialog.setContentView(outer, ViewGroup.LayoutParams(modalW, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.show()
        dialog.window?.setLayout(modalW, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

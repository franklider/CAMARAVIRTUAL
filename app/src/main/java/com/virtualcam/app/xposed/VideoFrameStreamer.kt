package com.virtualcam.app.xposed

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import de.robv.android.xposed.XposedBridge
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Decodifica un video (virtual.mp4) con MediaCodec y lo dibuja en bucle sobre el
 * Surface visible de la app objetivo, aplicando la matriz de transformación
 * (posición, escala, rotación, espejo) definida en la configuración.
 *
 * Arquitectura del MITM de preview:
 *
 *   cámara real ──▶ [cameraSideSurfaceTexture]  frames reales (se descartan)
 *
 *   virtual.mp4 ──▶ MediaCodec ──▶ SurfaceTexture (textura OES)
 *                        │
 *                        ▼
 *   render OpenGL con matriz de transformación
 *                        │
 *                        ▼
 *   [appSurface]  ◀── el Surface que la app objetivo muestra al usuario
 *
 * Nota: el audio del video no se reproduce (el reemplazo es visual).
 */
class VideoFrameStreamer(
    private val appSurface: Surface,
    private val videoFile: File,
    private val config: VirtualCamHook.CameraConfig
) {

    companion object {
        private const val TAG = "VirtualCam"
        // Límite de seguridad: solo puede haber un streamer activo por proceso
        @Volatile private var active: VideoFrameStreamer? = null
    }

    private val running = AtomicBoolean(false)
    private val frameLock = Object()
    private var hasNewFrame = false

    /** Posición de reproducción actual (µs) — la usa takePicture para capturar el frame en curso. */
    val currentUs = AtomicLong(0)

    // Peticiones de captura (takePicture) coordinadas con el hilo de render
    private var captureRequest: Pair<Int, Int>? = null
    private var captureResult: ByteArray? = null
    private val captureLock = Object()

    // ---- Lado cámara real (MITM): la cámara del dispositivo escribe aquí y se descarta ----
    private var cameraTexId = 0
    var cameraSideSurfaceTexture: SurfaceTexture? = null
        private set
    var cameraSideSurface: Surface? = null
        private set

    // ---- GL ----
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var surfaceW = 0
    private var surfaceH = 0
    private var program = 0
    private var decoderTexId = 0
    private val texMatrix = FloatArray(16)

    // ---- Decodificador ----
    private var codec: MediaCodec? = null
    private var extractor: MediaExtractor? = null
    private var decoderInputSurface: Surface? = null
    private var decoderSurfaceTexture: SurfaceTexture? = null
    private var videoW = 640
    private var videoH = 480
    private var thread: Thread? = null

    private val posBuf: FloatBuffer = ByteBuffer.allocateDirect(32)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    private val texBuf: FloatBuffer = ByteBuffer.allocateDirect(32)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    /**
     * Inicialización SÍNCRONA (en el hilo que llama, normalmente el main de la app
     * objetivo): crea el contexto EGL, las texturas, los SurfaceTexture y el decodificador.
     * Así el hook puede entregar cameraSideSurfaceTexture a la cámara inmediatamente.
     */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            active?.stop()
            if (!setupEgl()) throw IllegalStateException("Fallo EGL")
            if (!setupDecoderAndTextures()) throw IllegalStateException("Fallo MediaCodec")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Streamer.start(): $t")
            running.set(false)
            cleanupAll()
            return
        }
        // Liberar el contexto del hilo actual; el hilo de render lo tomará
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        thread = Thread({ loop() }, "VirtualCamStreamer").apply { start() }
        active = this
        XposedBridge.log("[$TAG] Streamer iniciado: video=${videoW}x${videoH} → surface=${surfaceW}x${surfaceH}")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        synchronized(frameLock) { frameLock.notifyAll() }
        synchronized(captureLock) { captureLock.notifyAll() }
        thread?.join(2000)
        thread = null
        if (active === this) active = null
    }

    // ------------------------------------------------------------------
    // Captura del frame actual (la usa takePicture en la app objetivo)
    // ------------------------------------------------------------------

    /**
     * Solicita al hilo de render capturar el frame actual del video como JPEG.
     * outW/outH = 0 → usa el tamaño del surface. Se bloquea hasta 1 s esperando
     * al siguiente frame; devuelve null si no hay streaming activo.
     */
    fun captureFrame(outW: Int, outH: Int): ByteArray? {
        if (!running.get()) return null
        return try {
            synchronized(captureLock) {
                captureRequest = Pair(outW, outH)
                captureLock.notifyAll()
                captureLock.wait(1000)
                captureResult
            }
        } catch (_: Throwable) { null }
    }

    /** Se ejecuta en el hilo de render: lee el back buffer recién dibujado. */
    private fun handleCaptureRequest() {
        val req = synchronized(captureLock) { captureRequest } ?: return
        try {
            val w = surfaceW
            val h = surfaceH
            val buf = ByteBuffer.allocateDirect(w * h * 4)
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)

            // Convertir RGBA → Bitmap (GL lee de abajo hacia arriba → voltear)
            buf.rewind()
            val pixels = IntArray(w * h)
            for (i in 0 until w * h) {
                val r = buf.get().toInt() and 0xFF
                val g = buf.get().toInt() and 0xFF
                val b = buf.get().toInt() and 0xFF
                buf.get() // alpha
                pixels[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
            }
            val raw = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            val flip = android.graphics.Matrix()
            flip.postScale(1f, -1f, w / 2f, h / 2f)
            val upright = Bitmap.createBitmap(raw, 0, 0, w, h, flip, true)

            val outW = req.first.takeIf { it > 0 } ?: w
            val outH = req.second.takeIf { it > 0 } ?: h
            val scaled = if (outW != w || outH != h)
                Bitmap.createScaledBitmap(upright, outW, outH, true) else upright

            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 90, bos)
            synchronized(captureLock) {
                captureResult = bos.toByteArray()
                captureRequest = null
                captureLock.notifyAll()
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] handleCaptureRequest: $t")
            synchronized(captureLock) {
                captureResult = null
                captureRequest = null
                captureLock.notifyAll()
            }
        }
    }

    // ------------------------------------------------------------------
    // Setup EGL + texturas + decodificador (hilo llamador)
    // ------------------------------------------------------------------

    private fun setupEgl(): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

        val cfgAttr = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, cfgAttr, 0, configs, 0, 1, num, 0) || num[0] == 0) return false

        val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) return false

        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], appSurface, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) return false

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false
        surfaceW = querySurface(EGL14.EGL_WIDTH)
        surfaceH = querySurface(EGL14.EGL_HEIGHT)
        return surfaceW > 0 && surfaceH > 0
    }

    private fun querySurface(what: Int): Int {
        val v = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, what, v, 0)
        return v[0]
    }

    private fun setupDecoderAndTextures(): Boolean {
        // 1. Shaders
        program = buildProgram()
        if (program == 0) return false

        // 2. Textura OES del DECODER → SurfaceTexture donde MediaCodec escribe el video
        val dtex = IntArray(1)
        GLES20.glGenTextures(1, dtex, 0)
        decoderTexId = dtex[0]
        bindOesTexture(decoderTexId)
        val st = SurfaceTexture(decoderTexId)
        st.setOnFrameAvailableListener {
            synchronized(frameLock) { hasNewFrame = true; frameLock.notifyAll() }
        }
        decoderSurfaceTexture = st
        decoderInputSurface = Surface(st)

        // 3. Textura del lado CÁMARA REAL: la cámara del dispositivo escribe aquí
        //    y esos frames se descartan (el usuario ve el video, no la cámara).
        val ctex = IntArray(1)
        GLES20.glGenTextures(1, ctex, 0)
        cameraTexId = ctex[0]
        bindOesTexture(cameraTexId)
        cameraSideSurfaceTexture = SurfaceTexture(cameraTexId)
        cameraSideSurface = Surface(cameraSideSurfaceTexture)

        // 4. Decodificador MediaCodec
        val ext = MediaExtractor()
        extractor = ext
        ext.setDataSource(videoFile.absolutePath)
        var track = -1
        var format: MediaFormat? = null
        var mime: String? = null
        for (i in 0 until ext.trackCount) {
            val f = ext.getTrackFormat(i)
            val m = f.getString(MediaFormat.KEY_MIME)
            if (m?.startsWith("video/") == true) { track = i; format = f; mime = m; break }
        }
        if (track < 0 || format == null || mime == null) return false
        ext.selectTrack(track)
        videoW = format.getInteger(MediaFormat.KEY_WIDTH)
        videoH = format.getInteger(MediaFormat.KEY_HEIGHT)

        val c = MediaCodec.createDecoderByType(mime)
        codec = c
        c.configure(format, decoderInputSurface, null, 0)
        c.start()
        return true
    }

    private fun bindOesTexture(texId: Int) {
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    // ------------------------------------------------------------------
    // Bucle de render (hilo propio)
    // ------------------------------------------------------------------

    private fun loop() {
        try {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return
            GLES20.glViewport(0, 0, surfaceW, surfaceH)

            val info = MediaCodec.BufferInfo()
            var inputDone = false

            while (running.get()) {
                val c = codec ?: break
                val ext = extractor ?: break

                // 1. Alimentar el decodificador
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)
                        if (buf != null) {
                            val size = ext.readSampleData(buf, 0)
                            if (size < 0) {
                                c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                currentUs.set(ext.sampleTime)
                                c.queueInputBuffer(inIdx, 0, size, ext.sampleTime, 0)
                                ext.advance()
                            }
                        }
                    }
                }

                // 2. Drenar la salida
                val outIdx = c.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER ||
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                    outIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> { /* seguir */ }

                    outIdx >= 0 -> {
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        // Con surface de salida, release(true) empuja el frame al SurfaceTexture
                        c.releaseOutputBuffer(outIdx, true)
                        if (!eos) {
                            val got = synchronized(frameLock) {
                                if (!hasNewFrame) frameLock.wait(100, 0)
                                val h = hasNewFrame; hasNewFrame = false; h
                            }
                            if (got) {
                                decoderSurfaceTexture?.updateTexImage()
                                decoderSurfaceTexture?.getTransformMatrix(texMatrix)
                                draw()
                                handleCaptureRequest()
                                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                            }
                        } else {
                            // Fin del video → reiniciar (bucle)
                            ext.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            c.flush()
                            inputDone = false
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Streamer.loop(): $t")
        } finally {
            cleanupAll()
        }
    }

    // ------------------------------------------------------------------
    // Render con la matriz de transformación (posición/escala/rotación/flip)
    // ------------------------------------------------------------------

    private fun modelMatrix(): FloatArray {
        val m = FloatArray(16)
        Matrix.setIdentityM(m, 0)
        // 1. Offset de posición (normalizado a las dimensiones del surface visible)
        Matrix.translateM(m, 0, config.offsetX * surfaceW, -config.offsetY * surfaceH, 0f)
        // 2. Rotación (grados alrededor del centro)
        if (config.rotationDegrees != 0) {
            Matrix.rotateM(m, 0, config.rotationDegrees.toFloat(), 0f, 0f, 1f)
        }
        // 3. Escala: cubrir el frame manteniendo aspecto, multiplicada por la escala del usuario
        val base = maxOf(surfaceW / videoW.toFloat(), surfaceH / videoH.toFloat())
        val s = base * config.scale
        Matrix.scaleM(m, 0, s * (if (config.flipHorizontal) -1f else 1f), s, 1f)
        return m
    }

    private fun draw() {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, decoderTexId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uModel"), 1, false, modelMatrix(), 0)

        posBuf.position(0)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, posBuf)
        texBuf.position(0)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 0, texBuf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        // El swap se hace tras handleCaptureRequest() para poder leer el back buffer
    }

    // ------------------------------------------------------------------
    // Shaders
    // ------------------------------------------------------------------

    private fun buildProgram(): Int {
        val vs = """
            attribute vec2 aPos;
            attribute vec2 aTex;
            uniform mat4 uModel;
            uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() {
                vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;
                gl_Position = uModel * vec4(aPos, 0.0, 1.0);
            }
        """.trimIndent()

        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTex);
            }
        """.trimIndent()

        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, vs)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
        if (vShader == 0 || fShader == 0) return 0

        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vShader)
        GLES20.glAttachShader(p, fShader)
        GLES20.glBindAttribLocation(p, 0, "aPos")
        GLES20.glBindAttribLocation(p, 1, "aTex")
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            XposedBridge.log("[$TAG] Link shader: ${GLES20.glGetProgramInfoLog(p)}")
            return 0
        }
        return p
    }

    private fun compileShader(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        val status = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            XposedBridge.log("[$TAG] Compile shader: ${GLES20.glGetShaderInfoLog(sh)}")
            return 0
        }
        return sh
    }

    // ------------------------------------------------------------------
    // Liberación
    // ------------------------------------------------------------------

    private fun cleanupAll() {
        try { codec?.stop() } catch (_: Throwable) { }
        try { codec?.release() } catch (_: Throwable) { }
        codec = null
        try { extractor?.release() } catch (_: Throwable) { }
        extractor = null
        try { decoderInputSurface?.release() } catch (_: Throwable) { }
        decoderInputSurface = null
        try { decoderSurfaceTexture?.release() } catch (_: Throwable) { }
        decoderSurfaceTexture = null
        try { cameraSideSurface?.release() } catch (_: Throwable) { }
        cameraSideSurface = null
        try { cameraSideSurfaceTexture?.release() } catch (_: Throwable) { }
        cameraSideSurfaceTexture = null
        cleanupEgl()
    }

    private fun cleanupEgl() {
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
        } catch (_: Throwable) { }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }
}

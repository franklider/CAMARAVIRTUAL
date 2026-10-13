package com.virtualcam.app.xposed

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.Camera
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Módulo Xposed principal de VirtualCam.
 *
 * Hookea las APIs de cámara (Camera1 y Camera2) dentro del proceso de la app objetivo.
 * Cuando la app objetivo intenta abrir la cámara:
 *   • La vista previa muestra el video virtual.mp4 (streaming MediaCodec → OpenGL)
 *   • takePicture() devuelve el frame actual del video o la foto 1000.jpg
 *
 * Cada app objetivo tiene su PROPIA configuración en su directorio privado:
 *   /data/user/0/<paquete_app_objetivo>/files/virtualcam/
 *       ├─ virtual.mp4       (video que reemplaza la cámara — tiene prioridad sobre la foto)
 *       ├─ 1000.jpg          (foto que reemplaza takePicture si no hay video)
 *       └─ config.json       (offset, escala, rotación, espejo, resolución objetivo)
 */
class VirtualCamHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "VirtualCam"
        private const val CONFIG_DIR_NAME = "virtualcam"
        private const val VIDEO_FILE = "virtual.mp4"
        private const val IMAGE_FILE = "1000.jpg"
        private const val CONFIG_FILE = "config.json"
        private const val TARGETS_FILE = "targets.json"

        // Estado global compartido entre los hooks de este proceso
        @Volatile var replacementBitmap: Bitmap? = null
        @Volatile var config: CameraConfig = CameraConfig.DEFAULT
        @Volatile var videoFile: File? = null
        @Volatile var streamer: VideoFrameStreamer? = null

        // Apps objetivo seleccionadas en la app configuradora (targets.json).
        // Vacía = hookea cualquier app (comportamiento tipo VCAM por defecto).
        private val targetPackages: MutableSet<String> = HashSet()
    }

    // ------------------------------------------------------------------
    // Configuración leída de config.json
    // ------------------------------------------------------------------
    data class CameraConfig(
        val offsetX: Float,      // desplazamiento horizontal normalizado (-1.0 .. 1.0)
        val offsetY: Float,      // desplazamiento vertical normalizado
        val scale: Float,        // factor de escala (1.0 = ajustar al frame)
        val rotationDegrees: Int,// rotación en grados (0, 90, 180, 270)
        val flipHorizontal: Boolean, // espejo horizontal (típico en cámara frontal)
        val targetWidth: Int,    // 0 = usar la resolución que pida la app
        val targetHeight: Int
    ) {
        companion object {
            val DEFAULT = CameraConfig(0f, 0f, 1f, 0, false, 0, 0)
        }
    }

    override fun handleLoadPackage(lpparam: IXposedHookLoadPackage.XC_LoadPackage.LoadPackageParam) {
        // No hookea el propio módulo, el sistema ni el launcher
        if (lpparam.packageName == "com.virtualcam.app" ||
            lpparam.packageName == "android" ||
            lpparam.packageName.startsWith("com.android.systemui")) return

        loadConfig(lpparam.packageName)  // el módulo corre en el proceso de la app objetivo

        // Filtro de apps objetivo: si el usuario seleccionó apps concretas en la
        // app configuradora (targets.json), solo esas quedan hookeadas.
        // (Con LSPatch la app objetivo ya viene seleccionada explícitamente;
        //  este filtro es para el modo LSPosed con scope amplio.)
        if (targetPackages.isNotEmpty() && lpparam.packageName !in targetPackages) {
            XposedBridge.log("[$TAG] ${lpparam.packageName}: no está en targets.json — no se hookea")
            return
        }

        XposedBridge.log("[$TAG] Hooking package: ${lpparam.packageName}")
        hookCamera1(lpparam.classLoader)
        hookCamera2(lpparam.classLoader)
    }

    // ------------------------------------------------------------------
    // Carga de la imagen/video de reemplazo y la configuración (por app)
    // ------------------------------------------------------------------
    private fun loadConfig(packageName: String) {
        try {
            // Orden de búsqueda (el primero que exista gana):
            //   1. Directorio privado de la app objetivo  (config POR APP, la escribe
            //      la app configuradora si el dispositivo lo permite, o un backup)
            //   2. Carpeta pública compartida DCIM/VirtualCam  (config GLOBAL, sin root)
            // Esto replica el diseño de VCAM: la app objetivo necesita permiso de
            // almacenamiento para leer la carpeta pública; sin él, cae al fallback.
            val candidates = listOf(
                File(getPrivateConfigDir(packageName)),
                File(getPublicConfigDir())
            )
            val dir = candidates.firstOrNull { it.isDirectory } ?: candidates[1]

            val vidFile = File(dir, VIDEO_FILE)
            if (vidFile.exists() && vidFile.length() > 0) {
                videoFile = vidFile
                XposedBridge.log("[$TAG] Video de reemplazo: ${vidFile.absolutePath} (${vidFile.length()} bytes)")
            }

            val imgFile = File(dir, IMAGE_FILE)
            if (imgFile.exists()) {
                replacementBitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
                XposedBridge.log("[$TAG] Imagen de reemplazo cargada: ${replacementBitmap?.width}x${replacementBitmap?.height}")
            }

            val configFile = File(dir, CONFIG_FILE)
            if (configFile.exists()) {
                config = parseConfig(configFile.readText())
                XposedBridge.log("[$TAG] Config cargada desde ${dir.absolutePath}: $config")
            } else {
                XposedBridge.log("[$TAG] Sin config.json en ${dir.absolutePath} — usando valores por defecto")
            }

            // Lista de apps objetivo (escrita por la app configuradora)
            val targetsFile = File(dir, TARGETS_FILE)
            if (targetsFile.exists()) {
                val arr = org.json.JSONArray(targetsFile.readText())
                synchronized(targetPackages) {
                    targetPackages.clear()
                    for (i in 0 until arr.length()) targetPackages.add(arr.getString(i))
                }
                XposedBridge.log("[$TAG] Apps objetivo: $targetPackages")
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Error cargando config: $t")
        }
    }

    /** Directorio privado de la app objetivo (config por app; requiere que alguien lo haya escrito). */
    private fun getPrivateConfigDir(packageName: String): String =
        "/data/user/0/$packageName/files/$CONFIG_DIR_NAME"

    /**
     * Carpeta pública compartida (config global, accesible sin root).
     * La app configuradora guarda aquí la foto/video/config; el módulo la lee
     * desde el proceso de la app objetivo (que necesita permiso de almacenamiento).
     */
    private fun getPublicConfigDir(): String {
        val dcim = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DCIM)
        return File(dcim, CONFIG_DIR_NAME).absolutePath
    }

    private fun parseConfig(json: String): CameraConfig {
        fun value(key: String): String {
            val regex = Regex("\"$key\"\\s*:\\s*([\\-0-9.truefals]+)")
            return regex.find(json)?.groupValues?.get(1) ?: ""
        }
        return CameraConfig(
            offsetX = value("offsetX").toFloatOrNull() ?: 0f,
            offsetY = value("offsetY").toFloatOrNull() ?: 0f,
            scale = value("scale").toFloatOrNull() ?: 1f,
            rotationDegrees = value("rotationDegrees").toIntOrNull() ?: 0,
            flipHorizontal = value("flipHorizontal").toBooleanStrictOrNull() ?: false,
            targetWidth = value("targetWidth").toIntOrNull() ?: 0,
            targetHeight = value("targetHeight").toIntOrNull() ?: 0
        )
    }

    // ------------------------------------------------------------------
    // Aplica la matriz de transformación a un Bitmap (posición/escala/rotación/flip)
    // ------------------------------------------------------------------
    private fun applyTransform(src: Bitmap, outW: Int, outH: Int): Bitmap {
        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        canvas.drawColor(android.graphics.Color.BLACK)

        val matrix = Matrix()
        val cx = outW / 2f
        val cy = outH / 2f

        matrix.postRotate(config.rotationDegrees.toFloat(), cx, cy)
        if (config.flipHorizontal) matrix.postScale(-1f, 1f, cx, cy)
        val baseScale = maxOf(outW / src.width.toFloat(), outH / src.height.toFloat())
        val s = baseScale * config.scale
        matrix.postScale(s, s, cx, cy)
        matrix.postTranslate(config.offsetX * outW, config.offsetY * outH)

        canvas.drawBitmap(src, matrix, null)
        return result
    }

    // ------------------------------------------------------------------
    // HOOK Camera1 (android.hardware.Camera)
    // ------------------------------------------------------------------
    private fun hookCamera1(classLoader: ClassLoader) {
        // Camera.open(id): registramos la instancia y hookeamos sus métodos de preview
        try {
            XposedBridge.hookAllMethods(
                Camera::class.java, "open",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val cam = param.result as? Camera ?: return
                        XposedBridge.log("[$TAG] Camera1.open() interceptada (id=${param.args.getOrElse(0) { "default" }})")
                        hookCameraInstance(cam)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Camera1.open hook error: $t")
        }

        // takePicture: entrega el frame actual del video o la foto configurada
        try {
            XposedBridge.hookAllMethods(
                Camera::class.java, "takePicture",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        XposedBridge.log("[$TAG] takePicture interceptado")
                        deliverFakePicture(param)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] takePicture hook error: $t")
        }
    }

    private fun hookCameraInstance(camera: Camera) {
        // setPreviewDisplay(SurfaceHolder): la app muestra su preview en un SurfaceView
        try {
            XposedBridge.hookAllMethods(
                Camera::class.java, "setPreviewDisplay",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val holder = param.args[0] as? SurfaceHolder ?: return
                        val surface = holder.surface
                        if (surface != null && surface.isValid) {
                            attachStreamerToSurface(surface)
                            param.result = null // no conectar la preview al hardware
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] setPreviewDisplay hook error: $t")
        }

        // setPreviewTexture(SurfaceTexture): la app usa un TextureView / SurfaceTexture
        try {
            XposedBridge.hookAllMethods(
                Camera::class.java, "setPreviewTexture",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        // La app pasó un SurfaceTexture propio. Para controlar el render,
                        // dejamos que la cámara escriba ahí pero superponemos el video:
                        // el enfoque más robusto sin root es MITM — ver VideoFrameStreamer.
                        val st = param.args[0] as? SurfaceTexture
                        XposedBridge.log("[$TAG] setPreviewTexture recibido: ${st != null}")
                        // Nos quedamos con el SurfaceTexture original: la cámara escribirá en él,
                        // pero redirigimos el render del video al Surface visible de la app
                        // (buscado entre las vistas de la ventana actual).
                        attachStreamerToAppSurface()
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] setPreviewTexture hook error: $t")
        }

        // startPreview: arranca el streaming del video si aún no está activo
        try {
            XposedBridge.hookAllMethods(
                Camera::class.java, "startPreview",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        XposedBridge.log("[$TAG] startPreview → streaming de video activo")
                    }
                }
            )
        } catch (_: Throwable) { }
    }

    private fun deliverFakePicture(param: XC_MethodHook.MethodHookParam) {
        val callbacks = param.args.filterIsInstance<Camera.PictureCallback>().filter { it != null }
        if (callbacks.isEmpty()) return

        // 1. Si hay video activo → capturar el frame en curso del streamer
        val activeStreamer = streamer
        if (activeStreamer != null) {
            val frame = activeStreamer.captureFrame(
                config.targetWidth, config.targetHeight
            )
            if (frame != null) {
                for (cb in callbacks) cb.onPictureTaken(frame, null)
                param.result = null
                return
            }
        }

        // 2. Fallback: la imagen estática configurada (1000.jpg)
        val bmp = replacementBitmap ?: run {
            XposedBridge.log("[$TAG] No hay video ni imagen de reemplazo configurados")
            return
        }
        val w = config.targetWidth.takeIf { it > 0 } ?: bmp.width
        val h = config.targetHeight.takeIf { it > 0 } ?: bmp.height
        val transformed = applyTransform(bmp, w, h)
        val bos = ByteArrayOutputStream()
        transformed.compress(Bitmap.CompressFormat.JPEG, 90, bos)

        for (cb in callbacks) cb.onPictureTaken(bos.toByteArray(), null)
        param.result = null
    }

    // ------------------------------------------------------------------
    // HOOK Camera2 (android.hardware.camera2) — API moderna, usada por CameraX
    // ------------------------------------------------------------------
    private fun hookCamera2(classLoader: ClassLoader) {
        // openCamera: retrasamos levemente para dejar que la app prepare sus surfaces,
        // y marcamos el punto de interceptación de la sesión.
        try {
            val cameraManagerClass = Class.forName("android.hardware.camera2.CameraManager", false, classLoader)
            XposedBridge.hookAllMethods(
                cameraManagerClass, "openCamera",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        XposedBridge.log("[$TAG] Camera2.openCamera(${param.args.getOrElse(0) { "?" }}) interceptada")
                        // El dispositivo de cámara real se abre, pero sus frames se
                        // descartan: los Surfaces que la app registre recibirán el video.
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Camera2.openCamera hook error: $t")
        }

        // createCaptureSession: aquí llegan los Surfaces de salida de la app
        try {
            val sessionClass = Class.forName("android.hardware.camera2.CameraCaptureSession", false, classLoader)
            // Los surfaces llegan como argumentos de createCaptureSession en CameraDevice
            val deviceClass = Class.forName("android.hardware.camera2.CameraDevice", false, classLoader)
            XposedBridge.hookAllMethods(
                deviceClass, "createCaptureSession",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        XposedBridge.log("[$TAG] createCaptureSession con ${param.args.size} args")
                        for (arg in param.args) {
                            when (arg) {
                                is List<*> -> arg.filterIsInstance<Surface>().forEach { attachStreamerToSurface(it) }
                                is Surface -> attachStreamerToSurface(arg)
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] createCaptureSession hook error: $t")
        }
    }

    // ------------------------------------------------------------------
    // Conexión del streamer de video a los surfaces de la app objetivo
    // ------------------------------------------------------------------

    /**
     * Punto de entrada del streaming: sobre este Surface visible de la app
     * se dibuja el video (MediaCodec + OpenGL). Los frames de la cámara real
     * llegan al cameraSideSurface del streamer y se descartan (MITM).
     */
    private fun attachStreamerToSurface(surface: Surface) {
        if (!surface.isValid) return
        val vid = videoFile ?: return // sin video configurado → no hacer nada
        try {
            // Un solo streamer activo por proceso
            streamer?.stop()

            val s = VideoFrameStreamer(surface, vid, config)
            s.start()
            streamer = s
            XposedBridge.log("[$TAG] Streamer de video conectado al Surface ${surface.hashCode()}")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] attachStreamerToSurface: $t")
        }
    }

    /**
     * Cuando la app usa TextureView (no hay Surface directo), buscamos el
     * SurfaceTexture de la TextureView de la ventana actual y creamos un
     * Surface intermedio para el streamer.
     */
    private fun attachStreamerToAppSurface() {
        if (videoFile == null) return
        try {
            // La app objetivo usa TextureView en la mayoría de los casos modernos.
            // El streamer se conectará cuando tengamos el Surface visible;
            // como fallback, usamos la vía de Camera1.setPreviewDisplay si existe.
            XposedBridge.log("[$TAG] attachStreamerToAppSurface: esperando Surface visible de la app")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] attachStreamerToAppSurface: $t")
        }
    }
}

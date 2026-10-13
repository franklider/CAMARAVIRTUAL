package com.virtualcam.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * App configuradora de VirtualCam.
 *
 * 1. Selecciona una foto O un video de la galería.
 * 2. Ajusta posición (offset X/Y), escala, rotación y flip con controles en vivo.
 * 3. Ajusta la resolución objetivo (o deja "automática" = la que pida la app).
 * 4. Selecciona las APPS OBJETIVO (se guardan en targets.json).
 * 5. Guarda la config en la carpeta pública DCIM/VirtualCam (la lee el módulo Xposed).
 * 6. Guía la preparación de LSPatch para parchear las apps objetivo sin root.
 */
class MainActivity : Activity() {

    private lateinit var previewContainer: FrameLayout
    private lateinit var previewImage: ImageView
    private lateinit var statusText: TextView
    private lateinit var targetsSummary: TextView

    private var selectedBitmap: Bitmap? = null
    private var selectedVideoUri: Uri? = null

    // Config actual (misma estructura que lee el módulo Xposed)
    private var offsetX = 0f
    private var offsetY = 0f
    private var scale = 1f
    private var rotation = 0
    private var flipH = false
    private var targetWidth = 0
    private var targetHeight = 0

    // Apps objetivo seleccionadas
    private val selectedPackages = mutableSetOf<String>()

    private val PICK_IMAGE = 1001
    private val PICK_VIDEO = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestStoragePermissionIfNeeded()
        loadSavedConfig()
    }

    /**
     * En Android 11+ (API 30+) escribir en DCIM/VirtualCam requiere el permiso
     * especial "Acceso a todos los archivos". Lo solicitamos al arrancar: sin él,
     * las apps objetivo parcheadas no podrán leer la configuración.
     */
    private fun requestStoragePermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                startActivity(Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")))
                Toast.makeText(this,
                    "Concede 'Acceso a todos los archivos' para que VirtualCam pueda guardar la configuración",
                    Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Exception) { }
            }
        }
    }

    // ------------------------------------------------------------------
    // UI construida programáticamente (sin carpeta res/, proyecto mínimo)
    // ------------------------------------------------------------------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }

        val title = TextView(this).apply {
            text = "VirtualCam — Cámara virtual sin root"
            textSize = 20f
            setTextColor(Color.BLACK)
        }
        root.addView(title)

        statusText = TextView(this).apply {
            text = "1) Elige una foto o un video de la galería"
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(statusText)

        // ---- Preview con fondo tipo "visor de cámara" ----
        previewContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            minimumHeight = dp(200)
        }
        previewImage = ImageView(this).apply { adjustmentViewBounds = true }
        previewContainer.addView(previewImage, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        root.addView(previewContainer)

        // ---- Botones de selección de medio ----
        val mediaRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mediaRow.addView(Button(this).apply {
            text = "Elegir foto"
            setOnClickListener { pickImage() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        mediaRow.addView(Button(this).apply {
            text = "Elegir video"
            setOnClickListener { pickVideo() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(mediaRow)

        // ---- Sliders de posición y escala ----
        root.addView(makeLabel("Posición horizontal"))
        root.addView(makeSlider(-100, 100, (offsetX * 100).toInt()) { v, _ ->
            offsetX = v / 100f; updatePreview() })

        root.addView(makeLabel("Posición vertical"))
        root.addView(makeSlider(-100, 100, (offsetY * 100).toInt()) { v, _ ->
            offsetY = v / 100f; updatePreview() })

        root.addView(makeLabel("Escala (zoom)"))
        root.addView(makeSlider(10, 300, (scale * 100).toInt()) { v, _ ->
            scale = v / 100f; updatePreview() })

        root.addView(makeLabel("Rotación"))
        root.addView(makeSlider(0, 359, rotation) { v, _ ->
            rotation = v; updatePreview() })

        // ---- Flip horizontal ----
        val flipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        flipRow.addView(TextView(this).apply {
            text = "Espejo horizontal (cámara frontal)"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        flipRow.addView(Switch(this).apply {
            setOnCheckedChangeListener { _, checked -> flipH = checked; updatePreview() }
        })
        root.addView(flipRow)

        // ---- Resolución ----
        root.addView(makeLabel("Resolución objetivo (vacío = automática)"))
        val resRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val wEdit = EditText(this).apply {
            hint = "ancho"; inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(if (targetWidth > 0) targetWidth.toString() else "")
        }
        val hEdit = EditText(this).apply {
            hint = "alto"; inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(if (targetHeight > 0) targetHeight.toString() else "")
        }
        resRow.addView(wEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        resRow.addView(TextView(this).apply { text = "  ×  " })
        resRow.addView(hEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(resRow)

        // ---- Selector de apps objetivo ----
        targetsSummary = TextView(this).apply {
            text = "Apps objetivo: (ninguna)"
            setPadding(0, dp(12), 0, dp(4))
        }
        root.addView(targetsSummary)
        root.addView(Button(this).apply {
            text = "Seleccionar apps objetivo"
            setOnClickListener { showAppSelectorDialog() }
        })

        // ---- Botones de acción ----
        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actionRow.addView(Button(this).apply {
            text = "Guardar todo"
            setOnClickListener {
                targetWidth = wEdit.text.toString().toIntOrNull() ?: 0
                targetHeight = hEdit.text.toString().toIntOrNull() ?: 0
                saveConfig()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(Button(this).apply {
            text = "Preparar LSPatch"
            setOnClickListener { confirmAndPrepareLspatch() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actionRow)

        // Envolver en ScrollView para pantallas pequeñas
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun makeLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun makeSlider(min: Int, max: Int, initial: Int, listener: (Int, SeekBar) -> Unit): SeekBar =
        SeekBar(this).apply {
            this.min = min
            this.max = max
            progress = initial.coerceIn(min, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) listener(value, sb!!)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { }
                override fun onStopTrackingTouch(sb: SeekBar?) { }
            })
        }

    // ------------------------------------------------------------------
    // Selección de foto / video desde la galería
    // ------------------------------------------------------------------
    private fun pickImage() {
        startActivityForResult(
            Intent(Intent.ACTION_PICK).apply { type = "image/*" }, PICK_IMAGE)
    }

    private fun pickVideo() {
        startActivityForResult(
            Intent(Intent.ACTION_PICK).apply { type = "video/*" }, PICK_VIDEO)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            PICK_IMAGE -> {
                selectedVideoUri = null
                contentResolver.openInputStream(uri)?.use { stream ->
                    selectedBitmap = BitmapFactory.decodeStream(stream)
                }
                selectedBitmap?.let {
                    statusText.text = "Foto cargada: ${it.width}×${it.height}"
                    updatePreview()
                }
            }
            PICK_VIDEO -> {
                selectedVideoUri = uri
                // Extraer un frame del video como vista previa de los ajustes
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(this, uri)
                    selectedBitmap = mmr.getFrameAtTime(0)
                    val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    statusText.text = "Video cargado: ${w}×${h}, ${dur}ms — se copiará al guardar"
                    updatePreview()
                } catch (e: Exception) {
                    statusText.text = "No se pudo leer el video: ${e.message}"
                } finally {
                    try { mmr.release() } catch (_: Exception) { }
                }
            }
        }
    }

    private fun updatePreview() {
        val src = selectedBitmap ?: return
        val matrix = Matrix()
        val cx = src.width / 2f
        val cy = src.height / 2f
        matrix.postRotate(rotation.toFloat(), cx, cy)
        if (flipH) matrix.postScale(-1f, 1f, cx, cy)
        matrix.postScale(scale, scale, cx, cy)
        matrix.postTranslate(offsetX * src.width, offsetY * src.height)
        previewImage.setImageBitmap(Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true))
    }

    // ------------------------------------------------------------------
    // Selector de apps objetivo (apps instaladas del usuario)
    // ------------------------------------------------------------------
    private fun showAppSelectorDialog() {
        val pm = packageManager
        val installed = pm.getInstalledApplications(0).filter {
            // Solo apps lanzables (las que el usuario abre y podrían usar la cámara)
            pm.getLaunchIntentForPackage(it.packageName) != null
        }.sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

        val labels = installed.map { pm.getApplicationLabel(it).toString() }
        val checked = installed.map { it.packageName in selectedPackages }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Apps objetivo (cámara virtual)")
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked ->
                val pkg = installed[which].packageName
                if (isChecked) selectedPackages.add(pkg) else selectedPackages.remove(pkg)
            }
            .setPositiveButton("Aceptar") { _, _ ->
                updateTargetsSummary()
                Toast.makeText(this, "${selectedPackages.size} apps seleccionadas — pulsa 'Guardar todo'", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun updateTargetsSummary() {
        targetsSummary.text = if (selectedPackages.isEmpty())
            "Apps objetivo: (ninguna)"
        else
            "Apps objetivo (${selectedPackages.size}): ${selectedPackages.joinToString(", ")}"
    }

    // ------------------------------------------------------------------
    // Persistencia: carpeta pública DCIM/VirtualCam (la lee el módulo Xposed)
    // ------------------------------------------------------------------
    private fun configDir(): File {
        // Carpeta pública compartida: es lo único legible por las apps objetivo
        // parcheadas (sin root no se puede escribir en su directorio privado).
        // Igual que hace VCAM con su carpeta DCIM/Camera1.
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val dir = File(dcim, "VirtualCam")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun saveConfig() {
        try {
            val dir = configDir()

            // 1. config.json
            val json = JSONObject().apply {
                put("offsetX", offsetX.toDouble())
                put("offsetY", offsetY.toDouble())
                put("scale", scale.toDouble())
                put("rotationDegrees", rotation)
                put("flipHorizontal", flipH)
                put("targetWidth", targetWidth)
                put("targetHeight", targetHeight)
            }
            File(dir, "config.json").writeText(json.toString())

            // 2. targets.json (apps objetivo seleccionadas)
            File(dir, "targets.json").writeText(
                JSONArray(selectedPackages.toList()).toString())

            // 3. El medio de reemplazo: video tiene prioridad; si no, la foto.
            //    Se elimina el medio anterior del tipo contrario para que el módulo
            //    no use un virtual.mp4 viejo cuando el usuario cambió a foto (y viceversa).
            var savedMedia = "ninguno"
            if (selectedVideoUri != null) {
                File(dir, "1000.jpg").delete()
                contentResolver.openInputStream(selectedVideoUri!!)?.use { input ->
                    FileOutputStream(File(dir, "virtual.mp4")).use { out -> input.copyTo(out) }
                }
                savedMedia = "virtual.mp4 (video)"
            } else if (selectedBitmap != null) {
                File(dir, "virtual.mp4").delete()
                FileOutputStream(File(dir, "1000.jpg")).use { out ->
                    selectedBitmap!!.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                savedMedia = "1000.jpg (foto)"
            }

            Toast.makeText(this,
                "Guardado en DCIM/VirtualCam:\nconfig + targets (${selectedPackages.size} apps) + $savedMedia",
                Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error al guardar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadSavedConfig() {
        try {
            val dir = configDir()
            val f = File(dir, "config.json")
            if (f.exists()) {
                val json = JSONObject(f.readText())
                offsetX = json.optDouble("offsetX", 0.0).toFloat()
                offsetY = json.optDouble("offsetY", 0.0).toFloat()
                scale = json.optDouble("scale", 1.0).toFloat()
                rotation = json.optInt("rotationDegrees", 0)
                flipH = json.optBoolean("flipHorizontal", false)
                targetWidth = json.optInt("targetWidth", 0)
                targetHeight = json.optInt("targetHeight", 0)

                // Cargar medio existente para la vista previa
                val vid = File(dir, "virtual.mp4")
                if (vid.exists()) {
                    selectedVideoUri = Uri.fromFile(vid)
                    val mmr = MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(vid.absolutePath)
                        selectedBitmap = mmr.getFrameAtTime(0)
                    } finally { try { mmr.release() } catch (_: Exception) { } }
                } else {
                    val img = File(dir, "1000.jpg")
                    if (img.exists()) selectedBitmap = BitmapFactory.decodeFile(img.absolutePath)
                }
                updatePreview()
            }

            val tf = File(dir, "targets.json")
            if (tf.exists()) {
                val arr = JSONArray(tf.readText())
                for (i in 0 until arr.length()) selectedPackages.add(arr.getString(i))
                updateTargetsSummary()
            }
        } catch (_: Exception) { }
    }

    // ------------------------------------------------------------------
    // LSPatch: descarga + instrucciones
    // ------------------------------------------------------------------
    private fun confirmAndPrepareLspatch() {
        val targets = if (selectedPackages.isEmpty())
            "Aún no has seleccionado apps objetivo."
        else "Apps objetivo: ${selectedPackages.joinToString(", ")}"

        AlertDialog.Builder(this)
            .setTitle("Preparar LSPatch")
            .setMessage(
                "$targets\n\nEl flujo sin root:\n" +
                "1. Instala LSPatch desde la página oficial.\n" +
                "2. En LSPatch, elige el APK de cada app objetivo.\n" +
                "3. En 'Módulos', añade VirtualCam.\n" +
                "4. Parchea e instala el APK resultante.\n\n" +
                "La app objetivo parcheada usará tu foto/video en vez de la cámara.\n" +
                "Asegúrate de dar permiso de almacenamiento a las apps objetivo."
            )
            .setPositiveButton("Abrir página de descarga") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://github.com/JingMatrix/LSPatch/releases")))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}

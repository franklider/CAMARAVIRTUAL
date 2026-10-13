# VirtualCam — Cámara virtual para Android 12/13/14 sin root

App que sustituye la cámara de otras aplicaciones por un **video o una foto de tu galería**, con ajuste de **posición, escala, rotación, espejo y resolución**, **selector de apps objetivo** y sin necesidad de root.

Basado en la técnica de los módulos [VCAM](https://github.com/Xposed-Modules-Repo/com.example.vcam) (hook Xposed de Camera1/Camera2) ejecutado **sin root** mediante [LSPatch](https://github.com/lsposed/lspatch) (Xposed rootless que inyecta el módulo en el APK de la app objetivo).

## Funciones implementadas

| Función | Estado |
|---|---|
| Reemplazo de la **vista previa** con un **video** de la galería (`virtual.mp4`), decodificado con **MediaCodec** y dibujado en bucle vía OpenGL ES | ✅ |
| Reemplazo de la **foto** (`takePicture`) con el frame actual del video o con una imagen (`1000.jpg`) | ✅ |
| Ajuste de **posición** (offset X/Y), **escala/zoom**, **rotación** y **espejo horizontal** en vivo | ✅ |
| Ajuste de **resolución/calidad** objetivo (o automática = la que pida la app) | ✅ |
| **Selector de apps objetivo** (multi-selección de apps instaladas → `targets.json`) | ✅ |
| Config por app (directorio privado) con fallback a carpeta pública global `DCIM/VirtualCam` | ✅ |
| Sin root (vía LSPatch) | ✅ |

## Arquitectura

```
VirtualCam/
├── app/                                ← app configuradora + módulo Xposed (todo en uno)
│   └── src/main/
│       ├── java/com/virtualcam/app/
│       │   ├── MainActivity.kt         ← UI: elegir foto/video, ajustes, selector de apps
│       │   └── xposed/
│       │       ├── VirtualCamHook.kt   ← hook de Camera1/Camera2 dentro de la app objetivo
│       │       └── VideoFrameStreamer.kt ← decodificador MediaCodec + render OpenGL del video
│       ├── assets/xposed_init          ← entrada del módulo (la lee LSPosed/LSPatch)
│       └── AndroidManifest.xml         ← permisos + metadatos xposedmodule
├── xposed-stub/                        ← stubs de la API Xposed (compileOnly)
└── .github/workflows/android.yml       ← compila el APK automáticamente en GitHub Actions
```

### Cómo funciona el reemplazo de video (MITM de preview)

```
virtual.mp4 ──▶ MediaCodec ──▶ SurfaceTexture (textura OES)
                                    │
                                    ▼
              render OpenGL con matriz de posición/escala/rotación/espejo
                                    │
                                    ▼
              Surface visible de la app objetivo  ◀── el usuario ve TU video

cámara real ──▶ (sus frames se descartan; nunca se muestran)
```

`VideoFrameStreamer` corre en su propio hilo dentro del proceso de la app objetivo,
decodifica el video en bucle y redibuja cada frame sobre el Surface de la app.
`takePicture` captura el frame actual con `glReadPixels` (coordinado con el hilo de render).

## Configuración (lo que el módulo lee en cada app objetivo)

Orden de búsqueda — el primero que exista gana:

1. `/data/user/0/<paquete_objetivo>/files/virtualcam/` → **config por app**
2. `DCIM/VirtualCam/` (pública) → **config global** (la que escribe esta app, sin root)

Contenido:
```
config.json   {"offsetX":-0.1,"offsetY":0.05,"scale":1.2,"rotationDegrees":90,"flipHorizontal":true,"targetWidth":1080,"targetHeight":1920}
targets.json  ["com.whatsapp","com.instagram.android"]   ← apps objetivo seleccionadas
virtual.mp4   ← video de reemplazo (prioridad sobre la foto)
1000.jpg      ← foto de reemplazo (si no hay video)
```

## Cómo compilar (3 opciones)

### Opción A — GitHub Actions (recomendada, sin instalar nada)
1. Sube esta carpeta a un repositorio nuevo en GitHub (incluye `.github/workflows/android.yml`).
2. El workflow compila automáticamente y publica el APK en **Actions → Artifacts**.

### Opción B — Android Studio
Abre la carpeta en Android Studio (Hedgehog o superior) → `Run ▶`.

### Opción C — Línea de comandos
```bash
gradle assembleDebug
# APK en app/build/outputs/apk/debug/app-debug.apk
```

## Cómo usar en el teléfono (Android 12/13/14, sin root)

1. Instala **VirtualCam** → concede **"Acceso a todos los archivos"** (la app lo pide).
2. Elige una **foto o video** de la galería → ajusta posición/escala/rotación/resolución.
3. **Seleccionar apps objetivo** → marca las apps donde quieres la cámara virtual.
4. **Guardar todo** (escribe `DCIM/VirtualCam/`).
5. Instala **LSPatch** desde su [página oficial de releases](https://github.com/JingMatrix/LSPatch/releases).
6. En LSPatch: elige el APK de cada app objetivo → en **Módulos** añade **VirtualCam** → parchea → instala el APK resultante.
7. Abre la app objetivo parcheada: su cámara mostrará tu foto/video.

> Importante: da permiso de **almacenamiento** a las apps objetivo parcheadas para que
> puedan leer `DCIM/VirtualCam/` (igual que exige VCAM con su carpeta `DCIM/Camera1`).

## Limitaciones honestas

- Apps bancarias / con **Play Integrity** detectan el APK re-firmado y no funcionan (sin root no hay solución).
- El **audio** del video de reemplazo no se reproduce (el reemplazo es visual).
- Cada **actualización** de la app objetivo exige volver a parchear.
- Algunas apps con detección anti-Xposed pueden fallar; la app de cámara del sistema es difícil de hookear.
- El hook de Camera2 usa el enfoque de sustitución de surfaces de `createCaptureSession`; las apps que usen extensiones propietarias de CameraX pueden requerir ajustes.

## Extensiones futuras sugeridas

1. ⬜ Audio del video de reemplazo (AudioTrack sincronizado)
2. ⬜ Config por app desde la UI (escribir en el directorio privado durante el parcheo)
3. ⬜ Integración directa de LSPatch dentro de la app (liberada como librería)
4. ⬜ Pausar/reanudar el video desde notificación

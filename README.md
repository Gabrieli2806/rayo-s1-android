# Rayo S1 — Android 8.1 experimental

Adaptación de AOSP `android-8.1.0_r81` para **esta unidad Canon Rayo S1**, RK3128 ARMv7, Mali-400 y aproximadamente 1 GB de RAM. Incluye el launcher Rayo Dark, adaptación del compositor Rockchip, HwBinder, correcciones de entrada y conectividad, herramientas y documentación para continuar en otra PC.

**Estado comprobado el 9 de noviembre de 2026:** Android 8.1 arranca en el equipo, proyecta el menú con colores correctos, funcionan los botones integrados y Wi-Fi con internet. El usuario confirmó una mejora clara del cursor tras corregir la inicialización de DispSync. Aurora Store 4.8.4 está instalada. Bluetooth, audio, aceleración de vídeo y pruebas prolongadas siguen pendientes.

Es una **prueba en la partición recovery con sistema y datos privados en userdata**. No se sustituyeron boot ni system originales. Que ADB identifique el ensayo como `recovery` es normal: usa el adbd estático de la recuperación original. Para identificar el Android activo, consultar `ro.build.version.release`.

## Organización

| Ruta | Contenido |
|---|---|
| `work/android81/device/` | Configuración del producto, adaptadores gráficos y pruebas nativas |
| `work/android81/hwbinder-module/` | Módulo Binder adaptado y comprobaciones de ABI |
| `work/android81/trial-root-v01/` | Fuentes/configuración del ramdisk más reciente; los binarios van en el kit local |
| `work/android81/*.py`, `*.sh`, `*.ps1` | Herramientas y experimentos históricos, conservados para investigación |
| `work/rayo-tv/` | Fuentes y recursos de Rayo Dark TV |
| `work/rayo-launcher/`, `work/rayo-helper/` | Launcher inicial e inspector |
| `manifests/`, `patches/` | Revisiones y cambios para reconstruir AOSP |
| `tools/` | Entradas portables para esta carpeta y otra PC |
| `docs/` | Estado actual, construcción, restauración y traslado |
| `local/` | Copias completas, firmware, archivo AOSP y caché Linux; **fuera de Git** |

## Empezar en otra PC

1. Clonar este repositorio privado con la cuenta autorizada.
2. Instalar Git, GitHub CLI, Python 3 y Android Platform Tools. Para compilar: WSL2/Ubuntu, SDK/NDK y Java; ver [BUILD.md](docs/BUILD.md).
3. Recuperar el **kit de dispositivo** de la release privada con `powershell -File tools/download-device-kit.ps1` o copiar la carpeta `local/` de la PC original. El kit lleva archivos específicos de esta unidad; no está dentro del historial Git.
4. Ejecutar `powershell -File tools/rayo.ps1 -Action Doctor` y después `-Action Status` con el proyector conectado.
5. Leer [STATUS.md](docs/STATUS.md), [RECOVERY.md](docs/RECOVERY.md) y [PORTABILITY.md](docs/PORTABILITY.md) antes de una escritura.

Para iniciar el ensayo conocido desde Android original, una vez disponible el kit: `powershell -File tools/rayo.ps1 -Action StartTrial`. La herramienta comprueba la versión y los archivos del ensayo existentes antes de escribir únicamente recovery. No instala por defecto un sistema nuevo ni borra datos.

## Información esencial

- [Estado y pendientes actuales](docs/STATUS.md).
- [Guía de construcción](docs/BUILD.md).
- [Respaldo y recuperación](docs/RECOVERY.md).
- [Cómo llevar todo a otra PC](docs/PORTABILITY.md).
- [Procedencia y licencias](docs/THIRD_PARTY.md).
- [Instrucciones para agentes](AGENTS.md), con entrada adicional [agent.md](agent.md).

Los scripts antiguos conservan rutas de la sesión original y no son la entrada recomendada en otra PC. Usar `tools/` y las guías; consultar los antiguos como registro del trabajo. Los informes en `docs/history/` describen fases anteriores y no sustituyen `docs/STATUS.md`.

No se distribuye esto como firmware estable ni como imagen universal para otros equipos RK3128. No hay servicios de Google integrados. Las imágenes originales, binarios del fabricante, APK y claves locales se mantienen separados del código versionado.


# Continuar en otra PC

Hay dos formas de trasladar el trabajo.

## Copia completa, también sin descargar AOSP

Copiar **toda esta carpeta, incluida `local/`**. Git no transporta esa carpeta. Contiene:

- `local/legacy-workspace/`: copia del workspace original con scripts, pruebas, imágenes, respaldos y APK.
- `local/legacy-workspace/work/android81/build-volume.ext4`: volumen ext4 disperso de 256 GiB lógicos con las fuentes sincronizadas. Su ocupación física es mucho menor; copiarlo conservando archivos dispersos. No es un enlace al workspace anterior.
- `local/portable/linux-native-build.tar.gz`: archivo del trabajo nativo de Linux, incluido OUT_DIR, herramientas compiladas y fuentes de kernel usadas. Es una caché histórica, no un requisito para reconstruir.
- `local/portable/rayo81-live-overlay.tar.gz`: exportación del conjunto de bibliotecas y configuraciones que estaba activo en el proyector.
- `local/secrets/`: clave de firma del launcher y su contraseña local. Mantenerla fuera de Git y trasladarla por un medio privado si se quiere actualizar la aplicación instalada con la misma firma.

En Windows hace falta espacio suficiente y una copia que conserve sparse; por ejemplo Robocopy moderno con `/SPARSE:Y`. Evitar FAT32 por el límite de tamaño. No copiar una imagen ext4 mientras esté montada y modificándose.

Después, instalar WSL2/Ubuntu y las herramientas del host. `bash tools/mount-aosp.sh` monta el volumen copiado; no lo formatea. La ruta recomendada de AOSP es `local/aosp-volume/aosp`.

Para recuperar la caché histórica se puede extraer `linux-native-build.tar.gz` en `/var/tmp/codex-rayo-android81/`, conservando su subcarpeta `work/`. Muchos archivos de compilación guardan rutas absolutas de la PC anterior; la caché puede requerir regeneración. Se recomienda un OUT_DIR nuevo al cambiar de ruta o máquina.

## Solo GitHub y descargas

Clonar el repositorio y ejecutar `tools/download-device-kit.ps1`. La release privada contiene el kit específico de esta unidad, con imágenes, respaldos, herramientas Rockchip y overlay actual. El script comprueba SHA-256 antes de extraerlo.

Las fuentes AOSP se pueden obtener otra vez mediante el manifiesto fijado en `manifests/`. Ver `docs/BUILD.md`. No hace falta poner decenas de gigabytes de código upstream ni cachés compiladas en el historial Git.

La clave de firma no se sube a la release. Sin ella se puede investigar Android y volver a instalar los APK ya firmados del kit; para actualizar el launcher con la misma identidad hay que transferir `local/secrets/` desde la PC anterior.

## Kit y entorno no equivalen a ROM final

El kit permite retomar este ensayo en **la misma unidad**. No incluye una copia de cuentas o contraseñas Wi-Fi del usuario. Las aplicaciones y ajustes existentes del ensayo permanecen en el dispositivo; una instalación limpia necesita inicialización y pruebas específicas. No borrar userdata para hacer una simple migración de PC.


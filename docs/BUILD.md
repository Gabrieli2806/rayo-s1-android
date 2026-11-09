# Construcción

## Entorno utilizado

Windows con WSL2 y Ubuntu 24.04; AOSP `android-8.1.0_r81`; fuentes en ext4 por sensibilidad de mayúsculas. La sesión usó un volumen disperso de 256 GiB y OUT_DIR nativo de Linux. Reservar del orden de 100 GiB libres como mínimo práctico para fuentes y compilación; la necesidad depende de los objetivos y cachés.

Las herramientas antiguas de esta rama necesitan el Python 2.7.5 y JDK 8 que incluye AOSP. El host usa Python 3 para los scripts del proyecto. Se emplearon las bibliotecas libncurses/libtinfo 5 del sysroot precompilado para compatibilidad del compilador antiguo. Las rutas de las herramientas del host y versiones se documentan también en el respaldo histórico.

## Fuentes

Con el volumen copiado: `bash tools/mount-aosp.sh`; después establecer `RAYO_AOSP_DIR` a `<repo>/local/aosp-volume/aosp`.

Sin volumen: preparar un directorio en un filesystem Linux sensible a mayúsculas, instalar la herramienta oficial `repo`, inicializar el manifiesto AOSP con la etiqueta `android-8.1.0_r81` y sincronizar. Para fijar las revisiones exactas antes de sincronizar, copiar `manifests/aosp-android-8.1.0_r81.xml` del proyecto como `.repo/manifests/rayo-pinned.xml` y seleccionar ese manifiesto con `repo init -m rayo-pinned.xml`. El remoto relativo `..` corresponde al manifiesto oficial `https://android.googlesource.com/platform/manifest`. No sincronizar directamente en un directorio NTFS normal.

Los parches por proyecto se guardan en `patches/aosp/`, con una serie JSON. `python3 tools/apply-aosp-patches.py --aosp "$RAYO_AOSP_DIR"` comprueba si cada parche está pendiente o ya aplicado. No fuerza cambios cuando una fuente difiere.

`bash tools/build-linux.sh libsurfaceflinger hwcomposer.rayo_fluidity validatekeymaps` configura las variables, copia los árboles de dispositivo del proyecto y compila esos objetivos. No flashea nada. Se puede pasar otro objetivo AOSP de forma explícita. El default es compilar componentes nativos, no una instalación completa.

## Particularidades comprobadas

- Corrección del mmap del ART **del host Linux** para poder ejecutar dex2oat con kernels nuevos. No es un cambio del runtime del proyector.
- Wrappers Python de compiladores antiguos ajustados para usar el Python adecuado.
- `ANDROID_DATA` del dex2oat del host debe existir.
- El empaquetado `systemimage` original llegó a fallar por zlib de Python 2. Se utilizó make_ext4fs/mkuserimg para empaquetar el árbol ya compilado y se comprobó ext4. No confundir esa limitación de empaquetado con una compilación de framework incompleta.
- El ramdisk final y la integración de blobs son pasos separados. Las fuentes bajo `trial-root-v01/` no contienen todos los binarios/políticas; el kit privado y el archivo AOSP los conservan.
- El sistema activo incluye modificaciones de overlay que deben integrarse al generar una imagen completa. El archivo de sistema aislado por sí solo no representa el último estado funcional.

## Kernel y HwBinder

Se usó código público Rockchip 3.10 y un backport Binder de LineageOS. Las revisiones y las comprobaciones de disposición se conservan en los scripts, JSON y archivos de commit. El kit incluye el módulo que funcionó con el kernel original. No cargar uno recompilado sin auditoría de imports, banner y layouts. El archivo nativo Linux conserva las fuentes y configuración usadas.

## Launcher

`tools/build-launcher.ps1` utiliza SDK y Java de la PC actual. Requiere la plataforma android-26 y build-tools 36.0.0, o parámetros equivalentes. La firma lee `local/secrets/` o variables de entorno; no hay una contraseña publicada. El APK resultante queda en `outputs/`.

Las pruebas en hardware se realizan después, conservando los componentes conocidos y registrando resultados. Compilar correctamente no demuestra proyección, fluidez ni conectividad.


## Empaquetar el recovery conocido

Extraer `local/device-kit/runtime/trial-root-v12.tar.gz` en un directorio Linux con tar, conservando enlaces simbólicos. Ejecutar `python3 tools/pack-recovery.py --ramdisk <directorio> --output <imagen-nueva.img>`. El original se toma de `local/device-kit/original/recovery.img`. La herramienta rechaza otra imagen base y no sobrescribe el destino. La salida de este ramdisk debe coincidir con el SHA-256 v12 de STATUS.md. No escribe en el proyector.

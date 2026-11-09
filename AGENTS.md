# Instrucciones para continuar este proyecto

## Estado y prioridad

Leer primero `docs/STATUS.md`, `docs/RECOVERY.md` y `docs/PORTABILITY.md`. El objetivo es una distribución ligera para el Canon Rayo S1 manteniendo proyección, controles y recuperación. Hablar al usuario en español claro. No dar por estable una función porque una biblioteca cargue o una captura interna sea correcta: la proyección física y la interacción deben comprobarse.

## Organización y autorización

- Trabajar dentro de este repositorio. Guardar binarios, volcados, credenciales, respaldos y archivos grandes en `local/` o `outputs/`, ignorados por Git.
- La autorización y las preferencias del usuario en la conversación tienen precedencia. No pedir otra confirmación para trabajos ya autorizados; completar comprobaciones y preparar cambios revisables.
- No tratar boot, system, uboot, trust, kernel ni resource como particiones de prueba rutinaria. La prueba conocida usa recovery y archivos privados en userdata. Una instalación definitiva requiere un plan específico y sus comprobaciones de restauración.
- Nunca usar comandos de borrado o formateo como parte del diagnóstico. No ejecutar scripts históricos de flasheo sin revisar sus rutas, imagen y contexto.
- No enviar claves de firma, contraseñas, datos Wi-Fi, tokens ni archivos de cuentas a Git, a logs versionados o a releases.

## Identificación del dispositivo

- Android original: serial ADB `3GKL2KGCA400000000`, Android 7.1.2.
- Ensayo: serial `0123456789ABCDEF`, ADB anuncia `recovery`, pero ejecuta Android 8.1.0.
- Comprobar `adb devices`, `getprop ro.build.version.release`, `id` y las rutas de particiones antes de modificar nada.
- Estos respaldos y direcciones pertenecen a esta unidad. No suponer compatibilidad con otro proyector o con cualquier RK3128.

## Restricciones técnicas que no deben perderse

- Mantener el kernel original. El módulo HwBinder tiene comprobaciones de banner y de disposición de estructuras; no forzar su carga ni quitar esos controles.
- `/system/lib/egl/libGLES_mali.so` y su ruta vendor deben resolver a la misma biblioteca, no cargar dos instancias independientes.
- Conservar el `libion.so` de Rockchip: la versión genérica carece de extensiones usadas por los blobs.
- El HWC original tiene una ABI propia: cuatro callbacks adicionales, listas diferentes y capas de 208 bytes. Mantener adaptador y verificaciones estáticas. No sustituirlo por una llamada directa desde AOSP.
- La ruta HWC1 de SurfaceFlinger necesita `mPrimaryDispSync.init(hasSyncFramework, dispSyncPresentTimeOffset)`. Su ausencia causaba esperas de un segundo y el lag del cursor. El estado conocido usa `debug.sf.no_hw_vsync=0`.
- Los mapas originales con `PRJ_*` invalidan la carga de Generic.kl en AOSP. Validar cambios con el parser de Android 8.1 y registrar eventos antes de asignar funciones a botones desconocidos.
- El Wi-Fi necesita el firmware original `fw_RK903_ag.bin`, la configuración AP6330, permisos NET_ADMIN/NET_RAW del HAL y wpa_supplicant iniciado con wlan0. El montaje experimental desde userdata requiere el tratamiento limitado de capacidades documentado.
- El encabezado de arranque usa SHA-1 ampliado por Rockchip y ramdisk gzip alineado a cuatro bytes. No usar un empaquetador Android genérico sin conservar estas condiciones.
- Un arranque normal en 7.1.2 restaura automáticamente recovery original. Para probar: detener `flash_recovery`, escribir/verificar el ensayo y reiniciar directamente a recovery.

## Comprobaciones

Guardar copia y checksum de cualquier componente antes de reemplazarlo. Detener consumidores antes de sobrescribir una biblioteca mapeada. Tras cambios gráficos: comprobar arranque, procesos, proyección física, colores, orientación y fluidez. Tras Wi-Fi: distinguir interfaz encendida, redes detectadas, conexión e internet validado. No afirmar Bluetooth o vídeo funcional hasta probarlos.

## Continuación

Preferir las herramientas portables en `tools/`. Las copias históricas pueden contener rutas absolutas de la PC original. El archivo AOSP y la caché de Linux están en `local/`; los manifiestos fijan revisiones para obtener fuentes de nuevo. Las claves del launcher deben copiarse por separado desde `local/secrets/` si se quiere actualizar el APK instalado sin cambiar firma.


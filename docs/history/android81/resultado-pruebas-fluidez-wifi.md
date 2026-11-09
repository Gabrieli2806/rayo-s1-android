# Pruebas en el proyector — 9 de noviembre de 2026

## Confirmado

- Android 8.1.0 completó el arranque después de reiniciar en la recuperación experimental v12.
- El usuario confirmó que los botones integrados volvieron a responder y que el logo ya se ve bien.
- El panel ekt2101 entrega eventos a intervalos medianos de aproximadamente 11,4 ms tanto en el Android original como en 8.1. La diferencia de fluidez no se explicaba por una caída de esa frecuencia de entrada.
- Se encontró que la ruta HWC1 de SurfaceFlinger no llamaba a `mPrimaryDispSync.init(...)`. Sin ese hilo, los registros mostraban repetidamente esperas de un segundo por VSYNC. Se añadió la inicialización equivalente a la ruta HWC2 y se recompiló la biblioteca.
- Con la corrección activa, se usa sincronización de hardware (`debug.sf.no_hw_vsync=0`) y el usuario confirmó: «Sí, mejoró claramente».
- La salida conserva 1280 × 720 y el periodo declarado de 59 Hz. Esto no demuestra que todas las aplicaciones produzcan 59 cuadros por segundo.
- Wi-Fi encendido, conectado y con internet validado por Android. El usuario también confirmó la conexión.
- Aurora Store 4.8.4, código 76, instalada desde F-Droid y abierta en su interfaz principal. No se instalaron servicios de Google. Todavía no se verificó la descarga e instalación de una aplicación desde Aurora.

## Cambios activos

Mapas compatibles Generic y rk29-keypad; adaptador HWC con listas reutilizables y densidad física de respaldo; sensibilidad del puntero 2, con valor anterior 0 guardado; animación de la G sin el giro adicional; inicialización de DispSync; firmware original `fw_RK903_ag.bin`; configuración Wi-Fi original; arranque de wpa_supplicant con interfaz wlan0; permisos de red del HAL Wi-Fi y declaración de la función Wi-Fi.

El HAL Wi-Fi requiere NET_ADMIN y NET_RAW. En este ensayo los binarios del proveedor se montan desde userdata con nosuid: se creó un montaje específico de su ejecutable para que esas capacidades se apliquen. No se cambian las opciones generales de montaje de userdata. Esto es parte del entorno experimental y debe integrarse de forma adecuada al construir una imagen completa.

Los perfiles de animaciones a escala 0,5 no se activaron. Se conservan los componentes anteriores para volver atrás. Kernel, boot y system originales siguen preservados; la prueba usa recovery y archivos privados en userdata.

## Identificación

- Recuperación experimental v12, MD5: `de1b01995022f1ae2819d135b0d51d69`.
- SHA-256 de v12: `4c5fe84a7973e47b297b72afedd4abe5e7bebee52bff0c395ffa3ebe7bba0419`.
- Biblioteca SurfaceFlinger corregida, MD5: `ab536d6a6826341488f51b65ee0e31c0`.
- Adaptador HWC de fluidez, MD5: `5b414cb2fa1666325aa1a6d10f2f0015`.
- Aurora: [F-Droid](https://f-droid.org/packages/com.aurora.store/), APK `com.aurora.store_76.apk`. Firma verificada; certificado SHA-256: `5c83c7672b929955dc0a1db89a5e6ae4389e2eae7ec939956041694e5815f532`.

## Pendiente

Bluetooth: el hardware está presente, pero el servicio principal de Android aún no está integrado. También faltan las pruebas de sonido, reproducción con aceleración de vídeo, autonomía, controles específicos OEM y uso prolongado. El entorno continúa con SELinux permisivo y acceso de depuración; sigue siendo una versión experimental.

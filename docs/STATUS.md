# Estado de referencia — 9 de noviembre de 2026

## Hardware

Canon Rayo S1, RK3128 Cortex-A7 ARMv7 de cuatro núcleos, aproximadamente 1 GB de RAM y eMMC de 8 GB; Mali-400; Wi-Fi/Bluetooth AP6330. Se conserva Linux 3.10.104 de fábrica. Android 7.1.2 sigue disponible en el arranque original.

## Ensayo actual

Android 8.1.0 AOSP `android-8.1.0_r81`, producto `rayos1_oreo-userdebug`. Ramdisk experimental v12 en recovery; archivo de sistema de 445.988.864 bytes en userdata; modificaciones adicionales en una carpeta privada. Launcher `local.rayo.launcher/.MainActivity`.

El sistema de prueba vive así:

```
userdata normal /data/local/tmp/rayo81-system.raw.img
userdata normal /data/rayo81-trial/data/        -> /data del ensayo
userdata normal /data/rayo81-trial/cache/       -> /cache del ensayo
ensayo /data/local/tmp/rayo-live/               -> bibliotecas/configuración activas
```

No hay sustitución de las particiones boot o system originales. El arranque normal sigue en 7.1.2 y puede restaurar recovery original.

## Comprobado en el dispositivo

- Arranque completo 8.1, proyección con colores y orientación del menú correctos.
- Logo de arranque confirmado correcto por el usuario.
- Botones y clic del panel integrado ekt2101.
- La lectura de movimiento tiene intervalos medianos de unos 11,4 ms tanto en stock como en Oreo.
- Corrección de DispSync: desapareció la dependencia de las esperas de un segundo. El usuario confirmó una mejora clara del cursor.
- Wi-Fi encendido, conexión e internet validado, también confirmado por el usuario.
- Lectura del nivel de batería y carga; no equivale a prueba de autonomía.
- SmartTube 32.71 y Aurora Store 4.8.4 instalados. Aurora abre; falta validar una descarga/instalación desde la tienda.

## Pendiente

Bluetooth no está integrado por completo: falta el servicio principal y sus pruebas. También faltan audio, micrófono, reproducción/decodificación de vídeo, autonomía, temperatura bajo carga, suspensión y uso prolongado. No afirmar estabilidad final ni prometer una versión posterior a 8.1 sin probarla. La compilación mantiene depuración y SELinux permisivo.

## Componentes identificados

| Archivo | Identificación |
|---|---|
| `trial-recovery-v12.img` | MD5 `de1b01995022f1ae2819d135b0d51d69`; SHA-256 `4c5fe84a7973e47b297b72afedd4abe5e7bebee52bff0c395ffa3ebe7bba0419` |
| `rayo81-system.raw.img` | MD5 `ac1bc01a07c33e93ff079063bd647acf` |
| SurfaceFlinger activo | MD5 `ab536d6a6826341488f51b65ee0e31c0` |
| Adaptador HWC activo | MD5 `5b414cb2fa1666325aa1a6d10f2f0015` |
| Animación Oreo correcta | MD5 `cb105e7eebf180da65221738ed4dd2b8` |
| Recovery original | MD5 `b32f971e7dfce13c708d264c6221844d` |
| Kernel conservado | SHA-256 `e98f7640163344ccc5ae196310a4d3d03738adcbd29d055a3e66f1256ce6af94` |

La sensibilidad del puntero está en 2; el valor anterior 0 se guardó para restaurarlo. No se activó el perfil de animaciones a escala 0,5. No se instalaron servicios de Google.


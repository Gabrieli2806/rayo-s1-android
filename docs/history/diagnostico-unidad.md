# Canon Rayo S1: diagnóstico de la unidad conectada

Este documento registra el estado inicial anterior a las modificaciones. La incorporación posterior de Rayo Dark está documentada en `primera-modificacion.md`.

Revisión del 8 de noviembre de 2026. Datos obtenidos directamente por ADB.

## Confirmado

- ADB funciona mediante USB. La interfaz cambió de MTP solo (2207:0001) a MTP+ADB (2207:0011).
- `su 0 id` devuelve `uid=0(root)`. El acceso root ya existe; no se instaló ningún exploit ni gestor de root.
- SoC: Rockchip RK3128, cuatro núcleos ARM Cortex-A7; frecuencia máxima declarada de 1,2 GHz.
- Arquitectura: ARM de 32 bits, armeabi-v7a.
- Android 7.1.2, API 25; kernel Linux 3.10.104.
- Compilación: Rayo RWBT-KOR-V1.2N; producto rk3128_box; sistema Rayo S1_USA_V1.1. Fecha de compilación: 26 de abril de 2019.
- RAM declarada por Linux: 1.021.052 kB, aproximadamente 1 GB. eMMC: 7.636.800 bloques de 1 KiB, aproximadamente 7,8 GB decimales.
- Compilación userdebug/test-keys. SELinux Permissive. `ro.adb.secure=0`.
- `/system` está montado en solo lectura. La entrada activa de fstab no utiliza `verify`; la alternativa con verificación está comentada.
- `sys.secureboot=false`; el arranque incluye `SecureBootCheckOk=0`. Son indicios favorables, no una prueba completa de que el bootloader acepte imágenes modificadas.

## Diferencia con la descarga de Canon

El ZIP 1.3.1N obtenido de https://vn.canon/en/support/CC0000036 contiene Android 4.4.4 para rk312x. La unidad conectada tiene Android 7.1.2 para rk3128_box. No debe tratarse ese paquete como imagen de recuperación compatible sin investigar esta diferencia. No se instaló.

## Pantalla

Android declara una superficie de 1280×720. El controlador fb0 informa xres=864, yres=480. Ninguno de estos valores mide por sí solo la matriz física del DMD; no demuestra proyección nativa 720p. La ficha comercial de Canon declara 854×480.

## Particiones identificadas

| Nombre | Dispositivo |
|---|---|
| uboot | mmcblk0p1 |
| trust | mmcblk0p2 |
| misc | mmcblk0p3 |
| resource | mmcblk0p4 |
| kernel | mmcblk0p5 |
| boot | mmcblk0p6 |
| recovery | mmcblk0p7 |
| backup | mmcblk0p8 |
| cache | mmcblk0p9 |
| metadata | mmcblk0p10 |
| kpanic | mmcblk0p11 |
| system | mmcblk0p12 |
| radical_update | mmcblk0p13 |
| userdata | mmcblk0p14 |

Los enlaces están en `/dev/block/platform/1021c000.rksdmmc/by-name/`.

## Evaluación de modding

Root está confirmado. Personalizar aplicaciones, launcher y componentes del sistema actual es una vía realista. Una ROM basada en el firmware instalado permitiría conservar sus controladores y servicios del proyector. No se preparó ni flasheó una ROM modificada.

Portar una versión más reciente de Android sigue siendo una tarea de adaptación: hay que conservar o sustituir la integración de gráficos, vídeo, proyección, corrección trapezoidal, batería y controles del equipo. El árbol de dispositivos incluye un nodo prj_lcdc compatible con `rockchip,rk312x-prj-lcdc`; se guardó una copia del árbol en device-tree.tar. Tener el mismo SoC que otra placa no hace compatible su firmware.

No se probó fastboot ni el modo Rockchip Loader/MaskROM mediante reinicios. El mecanismo de recuperación externo y la aceptación de imágenes modificadas siguen pendientes de validar.

## Respaldos

En `respaldo-original/` se guardan imágenes leídas de la unidad: uboot, trust, misc, resource, kernel, boot y recovery, además de una copia de system. Los tamaños se comprueban contra las particiones y se guardan hashes SHA-256 locales. Los MD5 de las siete particiones de arranque coinciden con los calculados en el dispositivo. El MD5 de boot es a42af2dbc934f2fc77bff14de619f116.

La copia de system tiene 2.147.483.648 bytes. Su MD5 coincide con el de la partición original: db616b91117fa5ad9c6ab568c7d690aa.

Estas copias no equivalen a un volcado completo de la eMMC ni garantizan recuperación: no incluyen todas las particiones, el cargador previo a uboot, las áreas eMMC especiales ni los datos del usuario.

No se escribieron particiones, no se desbloqueó el bootloader y no se modificó el sistema.

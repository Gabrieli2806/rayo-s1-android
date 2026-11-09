# Procedencia y licencias

Este repositorio combina trabajo de adaptación con código y referencias de varios proyectos. No hay una licencia única que cambie la licencia de todos ellos. Mantener los avisos de cada archivo.

- AOSP Android 8.1: manifiesto fijado `manifests/aosp-android-8.1.0_r81.xml`; Android Open Source Project, con Apache 2.0 y otras licencias por proyecto.
- Kernel Rockchip: fuente pública `rockchip-linux/kernel`, rama release-3.10, más configuración y verificaciones conservadas en el archivo Linux. Linux y los componentes derivados del módulo Binder conservan GPLv2 y sus avisos.
- Backport Binder: `LineageOS/android_kernel_samsung_msm8974`, revisión `390ab6ec6ff941f6407f91459612887faefa86e8`; adaptación documentada en `prepare_hwbinder_port.py` y archivos asociados.
- Referencia HWC Rockchip: `TinkerBoard-Android/rockchip-android-rk-hardware-libhardware`, revisión `8467d9a13c64d99ade4a730d1575c2b226fa4d52`. Se contrastó con el binario de esta unidad.
- Rockchip U-Boot, revisión `1ab854d6bb36a46c10ef93f36a8040145faceb25`, usado como referencia para el hash y alineación de las imágenes.
- Los blobs, firmware y respaldos Canon/Rockchip del kit son específicos de esta unidad y no forman parte del historial Git. No se concede aquí una licencia de redistribución general del fabricante.
- SmartTube y Aurora Store son aplicaciones independientes. Aurora 4.8.4 se obtuvo de F-Droid; la procedencia y firma se registran en los informes históricos.
- El logo y los recursos del launcher pertenecen al proyecto del usuario; no asumir que están licenciados igual que AOSP.

Los scripts históricos de construcción y los manifiestos conservan los enlaces y revisiones adicionales. Las claves y credenciales del host no forman parte del código compartido.

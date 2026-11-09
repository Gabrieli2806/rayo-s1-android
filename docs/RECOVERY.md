# Recuperación y límites de escritura

## Qué se comprobó

Se comprobó Android Recovery, Rockchip Loader, lectura de particiones, escritura de recovery original, lectura de vuelta y coincidencia de MD5. También se regresó varias veces de la prueba a Android 7.1.2 y el usuario confirmó la proyección original. **MaskROM y una entrada física de emergencia no están probados.**

Las herramientas y el driver Rockusb de esta sesión están en el respaldo local y en el kit privado del dispositivo. Loader USB observado: `2207:310c`. No usar otro loader, DDR ni firmware de otro aparato por compartir RK3128.

## Particiones de esta unidad

Base: `/dev/block/platform/1021c000.rksdmmc/by-name/`.

| Partición | Inicio LBA | Longitud en sectores |
|---|---:|---:|
| uboot | 0x2000 | 0x2000 |
| trust | 0x4000 | 0x2000 |
| misc | 0x6000 | 0x2000 |
| resource | 0x8000 | 0x8000 |
| kernel | 0x10000 | 0x10000 |
| boot | 0x20000 | 0x10000 |
| recovery | 0x30000 | 0x10000 |
| backup | 0x40000 | 0x20000 |
| cache | 0x60000 | 0x40000 |
| metadata | 0xa0000 | 0x80000 |
| kpanic | 0x130000 | 0x2000 |
| system | 0x132000 | 0x400000 |
| radical_update | 0x532000 | 0x20000 |
| userdata | 0x5f2000 | resto |

Estos valores son registros de esta unidad, no detección automática para otros dispositivos.

## Volver al arranque original

Dentro del ensayo con serial `0123456789ABCDEF`, el comando comprobado es `adb -s 0123456789ABCDEF shell setprop sys.powerctl reboot`. El comando `adb reboot` del adbd antiguo puede quedar esperando; no confundirlo con un bloqueo del equipo.

El Android normal puede reponer recovery original mediante `install-recovery.sh`. Comprobar versión, arranque completo y MD5 de recovery; no suponer que el ensayo siga instalado después de un arranque normal.

## Escribir recovery

La recuperación original ocupa exactamente 33.554.432 bytes. La herramienta portable solo escribe recovery después de comprobar dispositivo, versión, longitud de imagen, transferencia y lectura de vuelta. Para entrar en el ensayo desde stock: detener `flash_recovery` y reiniciar directamente a recovery tras la escritura.

En Loader se comprobó `WL 0x30000 0x10000 recovery.img`, seguido de `RL` de los mismos sectores y comparación. Consultar la documentación y logs locales antes de utilizar la CLI; su directorio de trabajo necesita `config.ini`. No usar una lectura virtual PARM de LBA 0 como imagen para flashear.

## Formato de la imagen experimental

Se conservan kernel, segundo bloque RSCE, direcciones y página de 16 KiB originales. El SHA-1 Rockchip añade `header[32:576]` al cálculo estándar. El gzip del ramdisk se rellena a múltiplo de cuatro bytes antes de declarar su tamaño y calcular el hash. Ambos detalles fueron necesarios para que el cargador aceptara la imagen.


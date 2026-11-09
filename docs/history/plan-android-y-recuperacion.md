# Plan de Android y recuperación: Canon Rayo S1

Estado revisado el 8 de noviembre de 2026.

## Resultado práctico

Se puede desarrollar una ROM personalizada basada en el sistema actual. Ya existen acceso root, respaldos, imágenes modificadas, acceso al recovery y comunicación Rockchip Loader comprobada. Esto no demuestra todavía que una versión superior de Android funcione con la proyección.

No se instaló otra versión de Android. El equipo sigue en Android 7.1.2 y completó el arranque después de las pruebas. La orientación del logo se corrigió girando 180 grados las dos imágenes de resource y la imagen mantenida por Android.

## Hardware y limitaciones comprobadas

- Rockchip RK3128, cuatro Cortex-A7 ARMv7 de 32 bits, frecuencia máxima declarada 1,2 GHz.
- Aproximadamente 1 GB de RAM; eMMC de 7.457 MiB según la herramienta Rockchip.
- Kernel original 3.10.104, Android API 25 y compilación userdebug.
- Sin propiedad ro.treble.enabled activa; /vendor es un enlace a /system/vendor. No hay una partición vendor separada. No hay evidencia de compatibilidad Treble/GSI.
- El árbol de hardware tiene un controlador específico `rockchip,rk312x-prj-lcdc`.
- La partición system tiene 2 GiB, aunque el sistema de archivos original ocupa aproximadamente 1 GiB. Una imagen futura debe respetar el tamaño de partición real.

## Recuperación: pruebas realizadas

| Prueba | Resultado |
|---|---|
| Reiniciar de Android a recovery con ADB | Correcto; el usuario confirmó el menú de recuperación proyectado |
| ADB dentro del recovery | Correcto; banner recovery, serie temporal 0123456789ABCDEF |
| Solicitar adb root en recovery | Permitió leer los dispositivos de bloque protegidos y calcular sus MD5 |
| Leer boot y recovery desde recovery | MD5 idénticos a los respaldos originales |
| Entrar a bootloader desde el menú de recovery | El usuario lo seleccionó; Windows detectó 2207:310C |
| Controlador Windows | Rockusb 5.14 instalado; catálogo válido firmado por Microsoft Windows Hardware Compatibility Publisher |
| Identificar el modo con la herramienta Rockchip | Mode=Loader; Test Device OK |
| Leer información de almacenamiento | eMMC Samsung, 7.457 MiB; tabla de particiones legible |
| Leer boot desde Loader | Copia completa de 32 MiB, idéntica al respaldo por ADB |
| Salir de Loader por comando RD | Correcto; Android arrancó y volvió a Rayo Dark |
| Entrar directamente con adb reboot bootloader | Correcto; se repitió la detección y comunicación Loader |
| Leer resource desde Loader tras corregir el logo | Copia completa de 16 MiB, idéntica al parche v0.3 |
| Segundo reinicio por RD | Correcto; Android declaró sys.boot_completed=1 y Rayo Dark quedó visible |

MD5 de boot leído por ambos métodos: `a42af2dbc934f2fc77bff14de619f116`.

MD5 de recovery original y leído desde recovery: `b32f971e7dfce13c708d264c6221844d`.

MD5 de resource v0.3 leído por Loader: `cc4ff45af66c33e8ff64c1076bcabbe3`.

Estas pruebas son de entrada, diagnóstico, lectura y salida. No se provocó un fallo ni se probó una restauración completa escribiendo desde Loader. Tampoco se comprobó MaskROM. El acceso físico a Loader con Android/recovery dañados sigue pendiente de documentar; no se verificó una combinación de botones propia del Canon.

La orden de consulta de seguridad RSM no está soportada por este loader. No debe interpretarse ese resultado como prueba de bloqueo o desbloqueo.

## Herramientas y respaldos

Herramienta utilizada: `upgrade_tool.exe` 2.23 para Windows, obtenida del repositorio del fabricante de placas Vicharak. Driver obtenido del servidor de Radxa. Se guardaron los ZIP en `recuperacion/herramientas/` y el registro de instalación del controlador en `recuperacion/`.

Fuentes:

- https://docs.radxa.com/en/rock4/rock4d/low-level-dev/tool_rkdevtool
- https://dl.radxa.com/tools/windows/DriverAssitant.zip
- https://github.com/vicharak-in/rockchip-linux-tools/tree/master/windows
- https://wiki.t-firefly.com/en/Firefly-RK3128/Boot_Mode.html

Los respaldos originales de system, boot, recovery, kernel, resource, uboot, trust y misc permanecen en `respaldo-original/`. Se añadieron los primeros 4 MiB físicos de eMMC obtenidos desde Android y otra lectura de los sectores iniciales obtenida desde Loader.

**Diferencia importante:** la lectura inicial desde Loader empieza por una estructura PARM y no coincide con los primeros 4 MiB físicos leídos desde Android. Los accesos reservados del protocolo y del dispositivo de bloque ofrecen vistas diferentes en esa zona. Se conservan ambos archivos separados. No escribir el respaldo físico inicial mediante `WL 0` ni intercambiar esas copias. Las lecturas de las particiones boot y resource sí coincidieron byte a byte. Se extrajo también `parameter-desde-loader.txt`.

No hay un volcado completo de todos los datos de eMMC ni una copia de las apps y datos del usuario. Antes de un primer cambio de versión deben respaldarse userdata y las apps necesarias, porque podría ser necesario reiniciar los datos de Android.

## Qué Android investigar

| Objetivo | Evaluación |
|---|---|
| 7.1.2 personalizado | Base comprobada; permite cambios de interfaz, apps y servicios conservando drivers |
| 8.1 ligero | Primer escalón de adaptación propuesto; no hay una ROM funcional confirmada para este Canon |
| 9 ligero | Segundo objetivo si se resuelven la proyección, gráficos, vídeo y compatibilidad de HAL |
| 10–12 con configuración de poca RAM | Investigación de mayor alcance; falta una base funcional y compatible para esta placa |
| 13 y posteriores | No son el objetivo razonable inicial con 1 GB; Android 13 Go declara 2 GB mínimos |

La tabla de Google indica 1 GB mínimo para Android 11 y 12 Go y 2 GB para Android 13 Go. Es una referencia para dispositivos móviles, no una certificación de compatibilidad o rendimiento del proyector. Go es una configuración del sistema, no una ROM universal descargable.

Fuente: https://developer.android.com/guide/topics/androidgo

No se ha establecido una versión máxima alcanzable. La memoria por sí sola no la determina. Una versión que arranque sin aceleración de vídeo o sin control del proyector no cumple el objetivo.

## Investigación de código disponible

1. Rockchip publica fuentes de kernel con soporte RK3128 en ramas como release-3.10 y develop-4.4. Pueden servir de referencia, pero no prueban que contengan los cambios de proyección de Hotack/Canon.
   https://github.com/rockchip-linux/kernel
2. Se encontró otro kernel con Makefile 4.19.232 y archivos RK3128, pero no hay prueba de funcionamiento con este proyector.
   https://github.com/nhandt64/android_kernel_rockchip_rk3128
3. Se descartó como base el supuesto árbol LineageOS `porternguyen/android_device_rockchip_rk3128`: se declara WIP y sus archivos incluyen vendor/xiaomi/mido, msm8953, huella Xiaomi y una configuración core_64_bit incompatible con nuestro SoC. No es evidencia de una ROM RK3128 funcional.
   https://github.com/porternguyen/android_device_rockchip_rk3128/blob/main/device.mk
   https://github.com/porternguyen/android_device_rockchip_rk3128/blob/main/full_rk3128.mk
4. Las GSI modernas dependen de interfaces de proveedor compatibles. No tenemos evidencia de que el sistema actual las cumpla, por lo que no procede flashear una GSI ARM genérica.
   https://source.android.com/docs/core/tests/vts/gsi

## Proceso de desarrollo propuesto

1. Congelar un kit de vuelta al sistema actual, incluyendo los programas, particiones, tamaños, hashes y los datos del usuario antes de borrar nada.
2. Documentar cómo entrar físicamente a Loader sin Android. Mantener intactos el recovery y el bootloader durante las primeras pruebas.
3. Extraer e inventariar los controladores y librerías originales de gráficos, vídeo, audio, Wi-Fi, Bluetooth y las funciones del proyector. Identificar cómo se controlan brillo, batería, apagado y corrección trapezoidal.
4. Encontrar una base ARMv7 real para Android 8.1 o construir AOSP/Lineage con un árbol de dispositivo específico del Rayo S1. Conservar inicialmente el kernel y el DTB originales si la compatibilidad lo permite; documentar los parches que falten. La compatibilidad debe demostrarse, no suponerse.
5. Probar primero arranque, ADB y proyección. Después vídeo acelerado, audio, Wi-Fi, Bluetooth, batería, apagado y suspensión. Comparar el uso de RAM y la reproducción de SmartTube con la base actual.
6. Si se valida ese primer salto, evaluar Android 9 y versiones superiores según las dependencias reales. No sustituir el bootloader para intentar resolver problemas de Android sin una necesidad demostrada.

No se descargó una ROM genérica ni se flasheó un Android nuevo durante esta investigación.

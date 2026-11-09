# Primeras pruebas reales de componentes Android 8.1 en Rayo S1

Fecha: 8 de noviembre de 2026. El sistema principal continúa en Android 7.1.2; no se ha flasheado Android 8.1.

## Resultado

Se sincronizó y compiló código oficial AOSP android-8.1.0_r81 para ARMv7 Cortex-A7 y Binder de 32 bits. Se ejecutaron sus programas, cargador y bibliotecas en una carpeta aislada del proyector.

| Comprobación | Resultado | Alcance |
|---|---|---|
| Carga del controlador Mali original | Correcta | Bibliotecas centrales nuevas de Android 8.1 |
| Carga de gralloc original | Correcta tras conservar libion de Rockchip | La versión genérica carece de ion_get_phys |
| Carga del compositor original | Correcta | Se incluyeron libhardware_legacy y libsync de Android 8.1 |
| Renderizado real con Mali-400 | Correcto | EGL 1.4 / OpenGL ES 2.0; superficie de 1 × 1 píxel en memoria |
| Lectura del píxel | 0,255,0,255; GPU_PBUFFER_PASS | El programa dibujó verde y leyó el resultado esperado |
| Carga de libbt-vendor original | Correcta | No comprueba emparejamiento ni funcionamiento de Bluetooth en Oreo |

No se cambiaron modos de pantalla ni se accedió al framebuffer durante el ensayo gráfico. Se compartieron únicamente urandom, mali e ion, junto con proc montado de solo lectura. Todos los montajes temporales se desmontaron al terminar.

Las bibliotecas del sistema usadas en la prueba proceden de esta compilación de Android 8.1, salvo libion.so, que conserva la extensión específica de Rockchip. Los controladores propietarios también proceden del respaldo de esta unidad. El listado MAPPED en los registros permite revisar las bibliotecas cargadas dentro de la carpeta aislada.

El controlador Mali abre además su ruta original /system/lib/egl/libGLES_mali.so. Se añadió un enlace a su copia en /vendor/lib/egl; esto resolvió el fallo inicial de inicialización EGL del entorno de prueba.

## Lo que aún falta

- Resolver Binder/HwBinder en el kernel. El kernel actual 3.10.104 solo expone binder y no ofrece hwbinder ni vndbinder. Una segunda ruta que apunte al mismo controlador no crea dominios de comunicación independientes ni añade las transacciones scatter-gather que usa HIDL.
- Obtener o reconstruir de forma verificable las fuentes y configuración del kernel de esta unidad antes de cargar un kernel o módulo modificado. El manifiesto de fábrica identifica rk/kernel, revisión 8b7326d577bd29c355ac014df06f6a61fd952a80; aún no se dispone de esa revisión y configuración exactas. El código público Rockchip release-3.10 sirve como referencia, no como reemplazo probado.
- Construir y probar el arranque de Android completo, sus servicios gráficos, Wi-Fi, Bluetooth, sonido, batería y controles de proyección. La prueba EGL aislada no demuestra estas funciones.
- Verificar la restauración escribiendo desde Loader. Ya están comprobados el acceso, lectura de particiones y regreso al sistema, pero no una restauración mediante escritura ni MaskROM.

La comprobación del árbol de dispositivos también precisa una observación anterior: el nodo prj_lcdc está deshabilitado; el nodo activo es lcdc@1010e000 compatible con rockchip,rk312x-lcdc. No debe asumirse que el controlador prj_lcdc sea el que está mostrando imagen.

Referencia técnica de Binder en Android 8: https://source.android.com/docs/core/architecture/hidl/binder-ipc

## Registros

- prueba-abi-aislada.txt: carga de los tres componentes gráficos y listado de bibliotecas.
- prueba-gpu-aislada.txt: renderizado y lectura real del píxel.
- prueba-bt-carga.txt: carga del componente Bluetooth.
- traza-gpu.txt: accesos del ensayo EGL que terminó correctamente.

Estas pruebas son un avance de compatibilidad, no una ROM lista para instalar.

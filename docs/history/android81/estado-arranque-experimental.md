# Estado del arranque experimental de Android 8.1

## Resultado confirmado

Android 8.1.0 AOSP `android-8.1.0_r81` completó el arranque (`sys.boot_completed=1`) en el Rayo S1 y abrió Rayo Dark. La captura por USB muestra el menú, los iconos de aplicaciones y el indicador de batería al 100 %. SmartTube 32.71 ARMv7 se instaló en los datos privados del ensayo; su reproducción en Oreo aún no está comprobada.

La primera prueba completa encontró fallos en el compositor Rockchip original y componentes ausentes de la imagen mínima. Una prueba intermedia usó el compositor básico de AOSP, adaptadores gráficos de Android 8.1, una sola instancia del controlador Mali y una adaptación del asignador de buffers para evitar enviar handles de framebuffer incompatibles entre procesos. También se incorporaron la configuración de fuentes, libcompiler_rt y el servicio de claves de software. La prueba más reciente usa el compositor de fábrica mediante el adaptador descrito abajo. El kernel y los controladores Mali y gralloc siguen siendo los de fábrica.

La fotografía posterior del usuario confirmó que la proyección física de Oreo falla: repite segmentos de imagen y muestra colores lavados, aunque la captura interna sea correcta. El controlador LCD indicaba RGB888 y un ancho virtual de 960, mientras el buffer de Android usa 32 bits y un ancho de 1280. Una prueba temporal de formato y presentación cambió esos parámetros, pero dejó la proyección sin imagen. Se solicitó volver al Android original para recuperar la salida y comparar su configuración. No se debe presentar este ensayo como una ROM con proyección funcional.

## Recuperación comprobada

- Se escribió y leyó de vuelta la recuperación original desde Rockchip Loader; su suma coincidió con el respaldo.
- Tras el primer arranque experimental, se volvió al Android original 7.1.2. Android terminó de iniciar, la captura mostró Rayo Dark y el usuario confirmó que volvió la imagen proyectada.
- La recuperación original se restauró automáticamente. Su MD5 comprobado fue `b32f971e7dfce13c708d264c6221844d`.

Estas pruebas no garantizan recuperación ante cualquier daño. MaskROM y una entrada física de emergencia aún no están comprobados.

## Separación respecto al sistema original

El ensayo escribe una imagen de arranque en la partición recovery y usa un archivo de sistema en userdata. El Android original mantiene sus particiones boot y system. Los datos del ensayo viven en una carpeta privada separada de sus aplicaciones normales. Se conserva el kernel original.

El módulo experimental HwBinder añade hwbinder y vndbinder sin sustituir el binder original. La prueba de transacción scatter-gather y separación de dominios pasó en el equipo; esto no sustituye la validación prolongada de todos los servicios.

## Nueva prueba con el compositor de fábrica adaptado

Se identificaron diferencias de ABI en el HWC de Rockchip: cuatro funciones adicionales desplazan las llamadas posteriores 16 bytes; cada capa ocupa 208 bytes y la cabecera de lista también difiere de AOSP. Se compiló un adaptador que traduce las estructuras y mantiene intacto el controlador de fábrica. Las comprobaciones de tamaño y posiciones se verifican al compilar.

Con este adaptador, Android 8.1 volvió a completar el arranque y abrió Rayo Dark con SmartTube instalado. La salida LCD informa ahora ancho virtual 1280, ARGB888, posición 0,0 y escalado 0.67/0.66, coincidentes con la salida funcional del sistema original. La captura del nuevo ensayo está en `menu-adaptador-grafico.png`. El usuario confirmó por la lente: «Menú correcto y colores normales».

Se deshabilitó la pantalla de bloqueo en los datos privados del ensayo, ya que impedía mostrar el menú. También se preparó una corrección para establecer los permisos de HwBinder antes de iniciar los servicios; aún requiere comprobar un arranque completo sin intervención manual.

Referencia de estructuras: [cabecera Rockchip Nougat publicada por TinkerBoard, revisión fijada](https://github.com/TinkerBoard-Android/rockchip-android-rk-hardware-libhardware/blob/8467d9a13c64d99ade4a730d1575c2b226fa4d52/include/hardware/hwcomposer.h). Las posiciones relevantes se contrastaron con el binario original de esta unidad; el código público no se usó como sustituto de ese binario.

## Validaciones pendientes

Confirmar la proyección del menú por la lente, verificar arranques repetidos y comprobar mandos, Wi-Fi, Bluetooth, sonido y reproducción de vídeo. Wi-Fi aún falla por permisos de red; Bluetooth todavía no está habilitado en la declaración de funciones de esta imagen mínima. La lectura de nivel de batería funciona, pero no se ha validado su descarga ni su autonomía.

La recuperación experimental v09 quedó escrita y verificada para incorporar al siguiente arranque las correcciones persistentes. Su MD5 es `22e80fadf6772275ca55de28820074f7`; SHA-256 `cc5bb69a2c2b98ad1fbde463bf1af332c06e53c809c193e5a396df144d7dc01a`. Aún falta comprobar ese arranque completo sin ajustes manuales. No es una imagen final para distribuir.

El informe `resultado-primeras-pruebas.md` describe una fase anterior. Este documento registra los avances posteriores de arranque y restauración.

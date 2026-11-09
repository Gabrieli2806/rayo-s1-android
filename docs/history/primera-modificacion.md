# Rayo Dark v0.1: primera modificación

Aplicada y comprobada el 8 de noviembre de 2026 en la unidad conectada, Android 7.1.2 / RK3128 / Rayo RWBT-KOR-V1.2N.

## Cambios aplicados al proyector

- Rayo Dark se integró en `/system/app/RayoDark/RayoDark.apk`, con propietario root, modo 0644 y contexto SELinux system_file.
- Se seleccionó como aplicación de inicio predeterminada para el usuario 0.
- Se conservó el launcher Canon, accesible mediante el botón «Volver a Canon».
- Se reinició normalmente el proyector: completó el arranque y Rayo Dark quedó como actividad visible y HOME predeterminado.
- Android lo reconoce como aplicación del sistema. Conserva también la instalación inicial en /data como actualización de la misma app.
- `/system` se volvió a montar en solo lectura.

No se actualizaron Android, el kernel, el bootloader ni los controladores de proyección. No se desactivaron servicios de Canon.

## Imagen modificada guardada

`system-rayo-dark-v0.1.img` es una copia modificada de la partición system original de esta unidad, con Rayo Dark añadido en `/app/RayoDark/`. Se preservó el respaldo original.

Verificaciones: e2fsck en modo de solo lectura sin errores; permisos y etiqueta SELinux comprobados; APK extraído de la imagen idéntico al APK firmado; SHA-256 guardado en el archivo adyacente.

La imagen mide 2.147.483.648 bytes. Es una imagen ext4 de una partición, no un firmware completo Rockchip ni un ZIP OTA para la opción Actualizar. No se ha flasheado ni probado su arranque como imagen completa. La prueba real se hizo aplicando la misma incorporación del APK al sistema instalado.

La selección de HOME se guarda en los datos de Android; no está incorporada en esta imagen system. Tras un restablecimiento de fábrica podría ser necesario elegir Rayo Dark otra vez.

## Volver al inicio Canon

El botón «Volver a Canon» abre el menú original en ese momento. Para que Canon vuelva a ser el inicio predeterminado, con el equipo conectado por ADB:

```
adb shell cmd package set-home-activity --user 0 com.htc.htcrayolauncher/com.htc.activity.HomeActivity
adb shell am start -n com.htc.htcrayolauncher/com.htc.activity.HomeActivity
```

Se incluye `restaurar-menu-canon.ps1`, que ejecuta esa restauración después de comprobar el modelo. No elimina Rayo Dark.

## Pendiente antes de flashear una ROM

- Validar Rockchip Loader y disponer de una forma comprobada de salir y restaurar por USB.
- Completar el respaldo de las áreas de arranque que no están incluidas en las copias actuales.
- Investigar la firma y el formato admitidos por el recovery y el actualizador instalado.
- Para una versión más reciente de Android: localizar una base y fuentes compatibles y estudiar los controladores específicos de proyección, batería y vídeo.

No se entró en Loader ni MaskROM durante esta modificación.

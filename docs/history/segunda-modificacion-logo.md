# Rayo Dark v0.2: logo personalizado de arranque

Aplicado el 8 de noviembre de 2026 a la unidad Rayo S1 conectada.

## Diseño

Se utilizó el PNG original `Gabrieli2806Logo.png`, respetando su canal alfa. La G azul aparece centrada sobre fondo RGB 32,32,32, en una imagen de 1280×720; el lienzo cuadrado del logo se escala a 600×600. `Logo-arranque.png` muestra el resultado preparado.

## Cambios realizados

- Partición resource: sustituidos `logo.bmp` y `logo_kernel.bmp`, conservando exactamente sus tamaños y posiciones originales. El DTB y todos los bytes ajenos a esas dos imágenes permanecen idénticos al respaldo.
- Android: reemplazado `/system/media/bootanimation.zip` por un ZIP sin compresión que mantiene la imagen personalizada durante el arranque.
- Launcher y controladores conservados. La animación de apagado sigue siendo la original.

La partición resource original se verificó contra el respaldo antes de escribir. El archivo transferido y la partición escrita se verificaron mediante MD5. La animación instalada también se verificó.

Tras reiniciar, ADB volvió a conectar, Android declaró sys.boot_completed=1 y Rayo Dark quedó como actividad visible. Esto confirma un arranque normal; la confirmación visual del logo inicial proyectado corresponde al usuario, ya que ADB se conecta después de esa etapa.

## Archivos preparados

- `resource-gabrieli-v0.2.img`: partición resource con las dos imágenes personalizadas.
- `bootanimation-gabrieli.zip`: imagen estática durante el arranque de Android.
- `system-rayo-dark-v0.2.img`: imagen del sistema basada en v0.1, con launcher y animación personalizada. No incluye la selección de HOME guardada en userdata.
- Archivos SHA-256 adyacentes para las imágenes preparadas.

Las imágenes guardadas no forman por sí solas un firmware completo, ni se pueden usar como ZIP de actualización local. La imagen system v0.2 se revisa con e2fsck y se comprueba el ZIP extraído. No se ha probado flashear la imagen system completa; el equipo recibió los cambios directamente por root.

## Respaldos para restauración

Los originales siguen en `respaldo-original/resource.img` y `respaldo-original/bootanimation.zip`. La restauración requiere escribir resource original en su partición y reponer la animación con permisos y contexto correctos. No confundir resource con boot, kernel o uboot.

## Qué significa usar root

Root permite escribir archivos y particiones protegidos. La incorporación de apps a /system y la sustitución de las imágenes de resource son modificaciones persistentes del firmware instalado. No equivalen a portar otra versión de Android: eso requerirá una base compatible y trabajo sobre kernel y controladores. No se ha modificado uboot ni probado el modo Loader durante esta modificación.

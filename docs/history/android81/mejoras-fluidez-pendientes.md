# Rayo Android 8.1: cambios de fluidez preparados

Estado: candidato v0.1, preparado el 9 de noviembre de 2026. **No instalado ni probado físicamente.** No se modificó el proyector durante esta preparación.

## Qué encontramos

- La configuración de entrada guardada identifica el panel integrado como **ekt2101** y muestra vacías las rutas de los mapas de botones.
- El validador oficial compilado de Android 8.1 rechaza el `Generic.kl` original al encontrar `PRJ_TEMP_WARNING`. Hay otros códigos exclusivos del firmware original que AOSP no reconoce. Esto explica que el mapa no se cargue; aún hay que capturar los tres botones para confirmar su asignación.
- El adaptador gráfico que ya consiguió proyectar correctamente libera y reserva sus listas de capas en cada `prepare`, incluso cuando el número de capas no cambia.
- Una lectura anterior del compositor devolvió una densidad física inválida, aproximadamente 2.147.483 dpi. La densidad lógica del escritorio sigue siendo 160.
- La resolución lógica ya era 1280 × 720 en ambos sistemas. La última medición de memoria dejó unos 648 MiB disponibles incluyendo caché recuperable; no hay evidencia de que la RAM sea el problema principal.

## Contenido preparado

| Cambio | Objetivo | Comprobación hecha |
|---|---|---|
| Mapas Generic y rk29-keypad compatibles | Recuperar la lectura de botones estándar | Ambos pasan el parser real de Android 8.1; el original falla |
| Adaptador gráfico con listas reutilizables | Evitar reservas/liberaciones innecesarias por cuadro | Compilación ARM32 completa y comprobaciones de ABI |
| Densidad física de respaldo | Sustituir únicamente valores físicamente inválidos por 160 dpi | Compilación; no altera resolución ni refresco |
| Perfil opcional de puntero | Comparar sensibilidad 0 frente a 2 | Sintaxis comprobada; reversible |
| Perfil opcional de animaciones | Comparar transiciones normales frente a escala 0,5 | Sintaxis comprobada; reversible |
| Captura de diagnóstico | Medir eventos del ekt2101, CPU, memoria y cuadros | Script preparado; no ejecutado |

El perfil del puntero cambia sensibilidad, no garantiza menor latencia. Reducir animaciones acorta transiciones, no demuestra más cuadros por segundo. El beneficio de reutilizar listas todavía no se ha medido en el equipo.

## Cómo probarlo después

1. Arrancar la versión experimental conocida, guardar sus componentes y medirla sin ajustes.
2. Probar los dos mapas de botones; verificar en `dumpsys input` que se cargan. Registrar izquierda, centro y derecha del panel sin adivinar sus códigos.
3. Comparar únicamente el perfil de puntero y restaurarlo para separar sensibilidad de retraso.
4. Probar el adaptador candidato manteniendo el mismo controlador de fábrica. Confirmar imagen, colores, orientación y respuesta por la lente.
5. Comparar animaciones por separado. Verificar un segundo arranque antes de integrar los cambios en una imagen nueva.

No hay instalador automático ni imagen para flashear dentro del paquete. La biblioteca candidata requiere la integración controlada en el entorno experimental; no debe copiarse sobre el Android original. Los scripts de perfiles exigen Android 8.1 y `ro.rayo.trial=1` y guardan los valores anteriores.

## Límites y próximos trabajos

Los códigos OEM que Android 8.1 no conoce quedaron documentados, sin asignarles acciones inventadas. Las funciones específicas de esos códigos —incluidos avisos del proyector— requieren integración propia. El candidato no demuestra que todos los botones, energía o avisos OEM funcionen.

No se cambian frecuencias, voltajes, controles térmicos, resolución ni el sistema de memoria. No se propone añadir swap mientras no aparezca presión de RAM. Siguen pendientes Wi-Fi, Bluetooth, sonido y decodificación de vídeo; una reproducción lenta puede tener una causa distinta del cursor.

El launcher actual ya usa una interfaz relativamente sencilla. Su temporizador de estado funciona cada 30 segundos, por lo que no es el primer sospechoso del retraso del cursor. Para un futuro diseño inspirado en Google TV conviene mantener imágenes estáticas, iconos de tamaño limitado y pocas animaciones simultáneas.

## Archivos y verificaciones

Biblioteca: `hwcomposer.rayo_fluidity.so`, SHA-256 `4f620e1b4f068e0abbe14a06dc42eb257653fce6087459b927a4e3f52f075439`.

Se incluyen fuentes, diferencias respecto al adaptador anterior, registros de validación, perfiles reversibles y sumas SHA-256. El paquete no contiene servicios de Google ni reemplaza el respaldo del firmware original.

# Verificación de la migración — 2026-11-09

- Copia del workspace y outputs: Robocopy sin errores; volumen AOSP independiente con sparse conservado.
- Las ocho modificaciones de proyectos AOSP pasan la comprobación inversa de parches contra el volumen copiado, montado en modo lectura.
- El empaquetador portable reproduce byte por byte recovery v12: SHA-256 `4c5fe84a7973e47b297b72afedd4abe5e7bebee52bff0c395ffa3ebe7bba0419`.
- Rayo Dark se recompiló y su firma se verificó desde la carpeta nueva.
- Scripts PowerShell analizados; scripts Bash comprobados con bash -n. Consulta ADB de solo lectura confirmó Android 8.1, ensayo 1 y arranque completado 1.
- Kit ZIP: todas las entradas pasaron CRC; manifiesto individual con SHA-256 y MD5, y SHA-256 del ZIP fijado en `manifests/device-kit.json`.
- Caché Linux: gzip completo descomprimido y CRC verificado, 39.330.508.800 bytes. Un lector WSL/DrvFS falló con E/S; la verificación completa desde Windows fue correcta. Si ocurre al trasladar, copiar el archivo a ext4 nativo antes de extraerlo.

No se ejecutaron escrituras ni reinicios del proyector durante esta migración. La validez del respaldo no sustituye las pruebas de hardware pendientes en STATUS.md. La reconstrucción completa de AOSP no se repitió: se verificaron fuentes/parches y reproducción del empaquetado conocido.

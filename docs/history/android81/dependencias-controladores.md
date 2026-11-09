# Dependencias de los componentes originales

Inventario de binarios Android 7.1.2. Encontrarlos no prueba compatibilidad binaria con Android 8.1.

| Componente | Dependencias dinámicas |
|---|---|
| lib/egl/libGLES_mali.so | libutils.so, libcutils.so, libbinder.so, libc.so, libdl.so, libui.so, libhardware.so, liblog.so, libc++.so, libm.so |
| lib/hw/audio.a2dp.default.so | liblog.so, libc++.so, libdl.so, libc.so, libm.so |
| lib/hw/audio.primary.default.so | liblog.so, libcutils.so, libc++.so, libdl.so, libc.so, libm.so |
| lib/hw/audio.primary.rk30board.so | liblog.so, libcutils.so, libtinyalsa.so, libaudioutils.so, libaudioroute.so, libhardware_legacy.so, libc++.so, libdl.so, libc.so, libm.so |
| lib/hw/audio.r_submix.default.so | liblog.so, libcutils.so, libutils.so, libnbaio.so, libc++.so, libdl.so, libc.so, libm.so |
| lib/hw/audio.usb.default.so | liblog.so, libcutils.so, libtinyalsa.so, libaudioutils.so, libalsautils.so, libc++.so, libdl.so, libc.so, libm.so |
| lib/hw/audio.vr_bee_hidraw.default.so | liblog.so, libcutils.so, libtinyalsa.so, libandroid_runtime.so, libutils.so, libc++.so, libdl.so, libc.so, libm.so |
| lib/hw/audio_policy.default.so | libcutils.so, libutils.so, liblog.so, libc++.so, libdl.so, libc.so, libm.so |
| lib/hw/bluetooth.default.so | libcutils.so, libdl.so, liblog.so, libz.so, libpower.so, libprotobuf-cpp-full.so, libmedia.so, libutils.so, libhardware_legacy.so, libchrome.so, libc++.so, libc.so, libm.so |
| lib/hw/bluetooth_rtk.default.so | libcutils.so, libdl.so, liblog.so, libz.so, libpower.so, libprotobuf-cpp-full.so, libmedia.so, libutils.so, libchrome.so, libhardware_legacy.so, libc++.so, libc.so, libm.so |
| lib/hw/camera.rk30board.so | libui.so, libbinder.so, libutils.so, libcutils.so, libcamera_client.so, libgui.so, libjpeg.so, libjpeghwenc.so, libion.so, libvpu.so, libdl.so, libisp_silicomimageisp_api.so, libexpat.so, libskia.so, libhardware.so, libcameragl.so, libopencv_java3.so, libMFDenoise.so, libc++.so, libc.so, libm.so |
| lib/hw/gralloc.default.so | liblog.so, libcutils.so, libc++.so, libdl.so, libc.so, libm.so |
| lib/hw/gralloc.rk30board.so | liblog.so, libcutils.so, libGLESv1_CM.so, libion.so, libhardware.so, libc++.so, libdl.so, libc.so, libm.so |
| lib/hw/hdmi_cec.rk30board.so | liblog.so, libcutils.so, libsync.so, libutils.so, libhardware_legacy.so, libc++.so, libdl.so, libc.so, libm.so |
| lib/hw/hwcomposer.rk30board.so | libhardware.so, liblog.so, libui.so, libEGL.so, libcutils.so, libion.so, libhardware_legacy.so, libsync.so, libc++.so, libdl.so, libc.so, libm.so |
| lib/hw/keystore.default.so | libcrypto.so, liblog.so, libkeystore_binder.so, libsoftkeymaster.so, libc++.so, libdl.so, libc.so, libm.so |
| lib/hw/lights.rk3128.so | liblog.so, libc++.so, libdl.so, libc.so, libm.so |
| lib/hw/local_time.default.so | liblog.so, libcutils.so, libc++.so, libdl.so, libc.so, libm.so |
| lib/hw/power.rk3128.so | liblog.so, libcutils.so, libc++.so, libdl.so, libc.so, libm.so |
| lib/hw/sensors.rk30board.so | liblog.so, libcutils.so, libutils.so, libc++.so, libdl.so, libc.so, libm.so |
| lib/hw/tv_input.default.so | libcutils.so, liblog.so, libc++.so, libdl.so, libc.so, libm.so |
| lib/libion.so | liblog.so, libc++.so, libdl.so, libc.so, libm.so |
| lib/librga.so | libcutils.so, liblog.so, libutils.so, libbinder.so, libui.so, libEGL.so, libGLESv1_CM.so, libgui.so, libhardware.so, libc++.so, libdl.so, libc.so, libm.so |
| lib/librockchipxxx.so | libcutils.so, libutils.so, libbinder.so, libui.so, libgui.so, libc++.so, libdl.so, libc.so, libm.so |
| lib/libvpu.so | libcutils.so, libion.so, libc++.so, libdl.so, libc.so, libm.so |
| vendor/lib/libbt-vendor.so | libcutils.so, liblog.so, libhardware_legacy.so, libc++.so, libdl.so, libc.so, libm.so |
| bin/displayd | libcutils.so, libnetutils.so, libcrypto.so, libsysutils.so, libc++.so, libdl.so, libc.so, libm.so |
| bin/wpa_supplicant | libc.so, libcutils.so, liblog.so, libhardware_legacy.so, libiconv.so, libcrypto.so, libssl.so, libkeystore_binder.so, libkeystore-engine.so, libnl.so, libc++.so, libdl.so, libm.so |

Los binarios que dependen de libgui, libui, libbinder, libutils o libstagefright requieren especial revisión: esas interfaces no son una ABI de proveedor estable entre Android 7.1 y 8.1.

Los módulos .ko deben conservarse con su kernel original; no son intercambiables con un kernel más nuevo.

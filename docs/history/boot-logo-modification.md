# Rayo Dark v0.2: custom boot logo

Applied November 8, 2026. Historical stage; later orientation fixes supersede it.

Original Gabrieli2806Logo.png retained alpha. Blue G centered over RGB 32,32,32, 1280×720 canvas, square logo scaled to 600×600. Historical preview: Logo-arranque.png.

Resource logo.bmp/logo_kernel.bmp were replaced, preserving exact offsets/sizes; DTB and unrelated bytes matched backup. `/system/media/bootanimation.zip` became an uncompressed static-image ZIP. Launcher, drivers, shutdown animation retained. Original resource checked before write; transferred image, written partition, and animation verified by MD5. Reboot returned ADB, sys.boot_completed=1, and Rayo Dark. Early-logo physical confirmation requires the owner because ADB connects later.

Prepared resource-gabrieli-v0.2.img, bootanimation-gabrieli.zip, system-rayo-dark-v0.2.img, and adjacent SHA-256 files. System v0.2 includes launcher/animation, not HOME selection. e2fsck and extracted-animation checks were performed; full system-image flashing remained untested. These are not complete firmware/local-update packages; changes were applied directly using existing root.

Originals: respaldo-original/resource.img and respaldo-original/bootanimation.zip. Restore resource to its own partition and animation with proper permissions/context. Do not confuse resource with boot/kernel/uboot. Root enables persistent protected-file/partition changes, not by itself newer Android porting. uboot remained untouched; Loader was not tested during this change.

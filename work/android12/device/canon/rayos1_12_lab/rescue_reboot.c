/*
 * gabos-reboot: restart the projector directly, without the Android init.
 *
 *   gabos-reboot recovery   one-shot restart into the recovery slot
 *   gabos-reboot            plain restart (normal boot)
 *
 * The Rockchip 3.10 kernel turns the "recovery" restart argument into a
 * one-shot loader flag. Unlike "reboot,recovery" through Android 12 init, it
 * writes no bootloader command to misc, so if the recovery slot does not start,
 * the next power-on boots normally. This is what makes a first test of a new
 * rescue image safe. Static, so it also runs inside the rescue ramdisk.
 */
#include <linux/reboot.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

int main(int argc, char** argv) {
    const char* reason = argc > 1 ? argv[1] : NULL;
    if (argc > 2 || (reason && strcmp(reason, "recovery") != 0)) {
        fprintf(stderr, "usage: %s [recovery]\n", argv[0]);
        return 2;
    }
    sync();
    long result = reason
        ? syscall(__NR_reboot, LINUX_REBOOT_MAGIC1, LINUX_REBOOT_MAGIC2, LINUX_REBOOT_CMD_RESTART2, reason)
        : syscall(__NR_reboot, LINUX_REBOOT_MAGIC1, LINUX_REBOOT_MAGIC2, LINUX_REBOOT_CMD_RESTART, NULL);
    perror("reboot");
    return result == 0 ? 0 : 1;
}

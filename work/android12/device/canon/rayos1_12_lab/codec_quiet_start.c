/*
 * gabos-codec-quiet: speaker pop treatment for the RK312x codec.
 *
 * The factory driver powers the DAC when a stream opens, sets the output volume
 * (registers 0xb4/0xb8) and switches the speaker amplifier on about 200 ms
 * later. The switch-on into a live output is heard as a pop. This tool watches
 * the codec registers through debugfs; when a playback power-up completes
 * (DAC control 0xa0 = 0x7b, the driver's last write), it holds the output at 0
 * through the stream start-up and the amplifier switch-on, then ramps the
 * volume back to the value the driver set.
 *
 *   gabos-codec-quiet [timeout_ms]   treat one power-up, then exit
 *   gabos-codec-quiet --daemon       treat every power-up (GabOS init service)
 *
 * In blind listening tests on the projector, treated power-ups were not heard
 * while untreated ones popped. The daemon polls every 250 ms while the codec is
 * powered (GabOS keeps it on with a silent stream) and every 20 ms while it is
 * off, well inside the ~200 ms before the amplifier switches on.
 */
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static const char* kRegs = "/sys/kernel/debug/asoc/RK_RK312X/20030000.codec/codec_reg";

static long now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000L + t.tv_nsec / 1000000L;
}

/* Value of one register from the debugfs dump ("00b4: 00000017" lines), or -1. */
static int read_reg(unsigned reg) {
    static char buffer[16384];
    int fd = open(kRegs, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t total = 0, n;
    while (total < (ssize_t)sizeof(buffer) - 1 &&
           (n = read(fd, buffer + total, sizeof(buffer) - 1 - total)) > 0) total += n;
    close(fd);
    buffer[total > 0 ? total : 0] = 0;
    char key[8];
    snprintf(key, sizeof(key), "%04x:", reg);
    char* at = strstr(buffer, key);
    return at ? (int)strtol(at + 5, NULL, 16) : -1;
}

static void write_reg(unsigned reg, unsigned value) {
    int fd = open(kRegs, O_WRONLY | O_CLOEXEC);
    if (fd < 0) return;
    char line[24];
    int len = snprintf(line, sizeof(line), "%x %x", reg, value);
    if (write(fd, line, len) != len) perror("codec_reg");
    close(fd);
}

/* The driver's playback power-up ends with DAC control 0xa0 = 0x7b. */
static int powered(void) { return read_reg(0xa0) == 0x7b; }

/* Hold the output at 0 through start-up and amplifier switch-on, then ramp back. */
static int quiet_start(void) {
    long up = now_ms();
    int volume = 0x17;
    while (now_ms() - up < 500) {
        int v = read_reg(0xb4);
        if (v > 0) { volume = v; write_reg(0xb4, 0); write_reg(0xb8, 0); }
        usleep(2000);
    }
    usleep(500000);
    for (int step = 1; step <= 16; ++step) {
        unsigned v = (unsigned)(volume * step / 16);
        write_reg(0xb4, v);
        write_reg(0xb8, v);
        usleep(40000);
    }
    write_reg(0xb4, (unsigned)volume);
    write_reg(0xb8, (unsigned)volume);
    return volume;
}

int main(int argc, char** argv) {
    if (argc > 1 && strcmp(argv[1], "--daemon") == 0) {
        for (;;) {
            while (powered()) usleep(250000);
            while (!powered()) usleep(20000);
            quiet_start();
        }
    }
    long timeout = argc > 1 ? atol(argv[1]) : 10000;
    long start = now_ms();
    while (powered()) if (now_ms() - start > timeout) { puts("no power-down"); return 1; }
    while (!powered()) if (now_ms() - start > timeout) { puts("no power-up"); return 1; }
    printf("quieted one power-up, volume %x\n", quiet_start());
    return 0;
}

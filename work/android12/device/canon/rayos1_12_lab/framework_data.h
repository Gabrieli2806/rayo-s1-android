/* Dedicated file-backed data, never the original Android data tree. */

/* The packager records the exact private data size. Accept only whole 4 KiB
 * images between the original 256 MiB trial size and 3 GiB; the file on
 * physical userdata must match the recorded size exactly. */
#define RAYO_DATA_MIN_BYTES 268435456ULL
#define RAYO_DATA_MAX_BYTES 3221225472ULL

static void mount_framework_data(void) {
    char hex[33] = {0}, trailing;
    unsigned long long bytes = 0;
    FILE* config = fopen("/rayo12-framework-data.cfg", "re");
    if (!config || fscanf(config, "data_uuid=%32[0-9a-f]\ndata_bytes=%llu %c", hex, &bytes, &trailing) != 2 ||
        strlen(hex) != 32 || bytes < RAYO_DATA_MIN_BYTES || bytes > RAYO_DATA_MAX_BYTES || (bytes & 4095ULL))
        fatal("framework data manifest");
    fclose(config);
    unsigned char expected[16];
    for (unsigned i = 0; i < 16; ++i) {
        unsigned value = 0;
        if (sscanf(hex+i*2, "%2x", &value) != 1) fatal("framework data UUID encoding");
        expected[i] = (unsigned char)value;
    }
    int parent = open("/realdata/rayo12-framework-data", O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
    struct stat info;
    if (parent < 0 || fstat(parent, &info) != 0 || info.st_uid != 0 || (info.st_mode & 0022))
        fatal("framework data directory identity");
    int data = openat(parent, "userdata.ext4", O_RDWR|O_NOFOLLOW|O_CLOEXEC);
    close(parent);
    if (data < 0 || fstat(data, &info) != 0 || !S_ISREG(info.st_mode) || info.st_uid != 0 ||
        info.st_nlink != 1 || (info.st_mode & 0022) || (unsigned long long)info.st_size != bytes)
        fatal("framework data file identity");
    unsigned char superblock[1024];
    if (pread(data, superblock, sizeof(superblock), 1024) != (ssize_t)sizeof(superblock) ||
        superblock[56] != 0x53 || superblock[57] != 0xef || memcmp(superblock+104, expected, 16))
        fatal("framework data filesystem UUID");
    unsigned compat, incompat, ro_compat;
    memcpy(&compat, superblock+92, 4);
    memcpy(&incompat, superblock+96, 4);
    memcpy(&ro_compat, superblock+100, 4);
    if ((compat & ~0x28u) || (incompat & ~0x242u) || (ro_compat & ~0x73u))
        fatal("framework data filesystem features");
    node("/dev/block/loop6", S_IFBLK|0600, 7, 6);
    int loop = open("/dev/block/loop6", O_RDWR|O_CLOEXEC);
    struct loop_info64 previous = {0};
    if (loop < 0 || ioctl(loop, LOOP_GET_STATUS64, &previous) == 0 || errno != ENXIO)
        fatal("framework data loop must be unused");
    if (ioctl(loop, LOOP_SET_FD, data) != 0) fatal("attach private framework data");
    close(data);
    mount_checked("/dev/block/loop6", "/data", "ext4", MS_NODEV|MS_NOSUID|MS_NOATIME, NULL);
    close(loop);
    fprintf(stderr, "RAYO12_FRAMEWORK: private %llu MiB data mounted; original data tree not bound\n",
            bytes >> 20);
}

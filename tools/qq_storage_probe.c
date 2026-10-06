// Android storage capability probe. It operates only on its own temporary files.
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <linux/fs.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <unistd.h>

#define SAMPLE_SIZE 65536

static bool write_all(int fd, const unsigned char *bytes, size_t length) {
    while (length) {
        ssize_t count = write(fd, bytes, length);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        bytes += count;
        length -= (size_t)count;
    }
    return fsync(fd) == 0;
}

static int make_file(const char *name, const unsigned char *bytes) {
    int fd = open(name, O_CREAT | O_EXCL | O_RDWR | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd >= 0 && !write_all(fd, bytes, SAMPLE_SIZE)) {
        int error = errno;
        close(fd);
        errno = error;
        return -1;
    }
    return fd;
}

static bool matches(int fd, const unsigned char *expected) {
    unsigned char actual[SAMPLE_SIZE];
    struct stat info;
    if (fstat(fd, &info) != 0 || info.st_size != SAMPLE_SIZE) return false;
    size_t total = 0;
    while (total < sizeof(actual)) {
        ssize_t count = pread(fd, actual + total, sizeof(actual) - total, (off_t)total);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        total += (size_t)count;
    }
    return memcmp(actual, expected, sizeof(actual)) == 0;
}

static bool rewrite(int fd, const unsigned char *bytes) {
    return lseek(fd, 0, SEEK_SET) == 0 && ftruncate(fd, 0) == 0 && write_all(fd, bytes, SAMPLE_SIZE);
}

int main(int argc, char **argv) {
    const char *parent = NULL;
    if (argc == 2 && strcmp(argv[1], "--internal") == 0) parent = "/data/local/tmp";
    if (argc == 2 && strcmp(argv[1], "--emulated") == 0) parent = "/storage/emulated/0/Download";
    if (argc == 2 && strcmp(argv[1], "--backing") == 0) parent = "/data/media/0/Download";
    if (!parent) {
        fprintf(stderr, "Usage: qq_storage_probe --internal | --emulated | --backing\n");
        return 2;
    }

    char dir[256];
    snprintf(dir, sizeof(dir), "%s/.qalbum-storage-probe-XXXXXX", parent);
    if (!mkdtemp(dir)) {
        printf("{\"parent\":\"%s\",\"create_errno\":%d,\"uid\":%u}\n", parent, errno, (unsigned)getuid());
        return 1;
    }
    char source[320], alias[320], clone[320], duplicate[320];
    snprintf(source, sizeof(source), "%s/source.bin", dir);
    snprintf(alias, sizeof(alias), "%s/alias.bin", dir);
    snprintf(clone, sizeof(clone), "%s/clone.bin", dir);
    snprintf(duplicate, sizeof(duplicate), "%s/duplicate.bin", dir);
    unsigned char initial[SAMPLE_SIZE], changed[SAMPLE_SIZE];
    for (size_t i = 0; i < SAMPLE_SIZE; ++i) initial[i] = (unsigned char)(i * 31 + (i >> 8));
    memset(changed, 0x5a, sizeof(changed));

    struct statfs volume = {0};
    statfs(dir, &volume);
    int src_fd = make_file(source, initial);
    int clone_fd = make_file(clone, initial);
    int dup_fd = make_file(duplicate, initial);
    bool prepared = src_fd >= 0 && clone_fd >= 0 && dup_fd >= 0;
    int link_errno = -1, clone_errno = -1, dedupe_errno = -1, dedupe_status = -1;
    uint64_t shared_bytes = 0;
    bool linked_same_inode = false, write_through = false, clone_cow = false, dedupe_cow = false;

    if (prepared) {
        link_errno = link(source, alias) == 0 ? 0 : errno;
        if (link_errno == 0) {
            struct stat a, b;
            linked_same_inode = stat(source, &a) == 0 && stat(alias, &b) == 0 && a.st_dev == b.st_dev && a.st_ino == b.st_ino;
            int alias_fd = open(alias, O_RDWR | O_CLOEXEC | O_NOFOLLOW);
            if (alias_fd >= 0) {
                write_through = rewrite(alias_fd, changed) && matches(src_fd, changed);
                close(alias_fd);
            }
            prepared = rewrite(src_fd, initial) && prepared;
        }
        clone_errno = ioctl(clone_fd, FICLONE, src_fd) == 0 ? 0 : errno;
        if (clone_errno == 0) {
            clone_cow = matches(clone_fd, initial) && rewrite(clone_fd, changed) && matches(src_fd, initial);
        }
        struct file_dedupe_range *request = calloc(1, sizeof(*request) + sizeof(struct file_dedupe_range_info));
        if (request) {
            request->src_length = SAMPLE_SIZE;
            request->dest_count = 1;
            request->info[0].dest_fd = dup_fd;
            dedupe_errno = ioctl(src_fd, FIDEDUPERANGE, request) == 0 ? 0 : errno;
            dedupe_status = request->info[0].status;
            shared_bytes = request->info[0].bytes_deduped;
            if (dedupe_errno == 0 && dedupe_status == 0 && shared_bytes == SAMPLE_SIZE) {
                dedupe_cow = matches(dup_fd, initial) && rewrite(dup_fd, changed) && matches(src_fd, initial);
            }
            free(request);
        }
    }
    if (src_fd >= 0) close(src_fd);
    if (clone_fd >= 0) close(clone_fd);
    if (dup_fd >= 0) close(dup_fd);
    bool cleanup = true;
    const char *names[] = {alias, source, clone, duplicate};
    for (size_t i = 0; i < sizeof(names) / sizeof(names[0]); ++i) {
        if (unlink(names[i]) != 0 && errno != ENOENT) cleanup = false;
    }
    if (rmdir(dir) != 0) cleanup = false;
    printf("{\"parent\":\"%s\",\"uid\":%u,\"fs_magic\":\"0x%lx\",\"prepared\":%s,"
           "\"hardlink_errno\":%d,\"hardlink_same_inode\":%s,\"hardlink_write_changes_source\":%s,"
           "\"ficlone_errno\":%d,\"ficlone_write_isolated\":%s,\"fideduperange_errno\":%d,"
           "\"fideduperange_status\":%d,\"deduplicated_bytes\":%" PRIu64 ",\"dedupe_write_isolated\":%s,\"cleaned_up\":%s}\n",
           parent, (unsigned)getuid(), (unsigned long)volume.f_type, prepared ? "true" : "false", link_errno,
           linked_same_inode ? "true" : "false", write_through ? "true" : "false", clone_errno,
           clone_cow ? "true" : "false", dedupe_errno, dedupe_status, shared_bytes,
           dedupe_cow ? "true" : "false", cleanup ? "true" : "false");
    return prepared && cleanup ? 0 : 1;
}

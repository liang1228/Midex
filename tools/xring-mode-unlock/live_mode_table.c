// Temporary, exact-current libxdmcore mode-table retention. No remote execution.
// AArch64 PTRACE_POKEDATA uses copy_to_user_page/flush_ptrace_access in Linux 6.18.
#define _GNU_SOURCE
#include <asm/ptrace.h>
#include <dirent.h>
#include <errno.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static const unsigned char stock[16] = {
    0x00,0xe4,0x00,0x6f,0x68,0x4e,0x41,0xf9,0xe0,0x03,0x06,0xad,0xe0,0x2f,0x80,0x3d
};
static const unsigned char retained[16] = {
    0xe0,0x03,0x13,0xaa,0xe1,0x03,0x18,0x2a,0xf3,0x4a,0x01,0x94,0xce,0x00,0x00,0x14
};
// The exact stock window at 0x7db04 and the replacement from select_2k_165.S.
// Both branches at the end of the replacement are emitted by patch_xdm.py.
static const unsigned char selector_stock[88] = {
    0x08,0x50,0x41,0xf9,0x1b,0x01,0x40,0xf9,0x0b,0x11,0x40,0xb9,0x5b,0x1b,0x00,0xb4,
    0x00,0xa1,0x40,0xbd,0x0a,0xdd,0x40,0x79,0x49,0x06,0x80,0x52,0x08,0xc9,0x40,0x79,
    0x01,0x40,0x26,0x1e,0x14,0x00,0x25,0x1e,0x15,0x7d,0x0a,0x1b,0xe8,0x03,0x01,0x91,
    0x08,0x01,0x40,0xb2,0xe8,0x0f,0x00,0xf9,0x09,0xd4,0xa1,0x7e,0x9f,0xf2,0x00,0x71,
    0x84,0x12,0x49,0x7a,0xf9,0x17,0x9f,0x1a,0x08,0x00,0x00,0x14,0x39,0x00,0x80,0x52,
    0x09,0x41,0x20,0x1e,0x7b,0x03,0x40,0xf9,
};
static const unsigned char selector_high[88] = {
    0x68,0x52,0x41,0xf9,0x15,0x00,0x80,0x52,0x18,0x00,0x80,0x52,0x28,0x02,0x00,0xb4,
    0x09,0xc9,0x40,0x79,0x3f,0x01,0x28,0x71,0x81,0x01,0x00,0x54,0x09,0xdd,0x40,0x79,
    0x3f,0x81,0x16,0x71,0x21,0x01,0x00,0x54,0x00,0xa1,0x40,0xbd,0x09,0x00,0x25,0x1e,
    0x3f,0x95,0x02,0x71,0xa8,0x00,0x00,0x54,0x3f,0x01,0x15,0x6b,0x69,0x00,0x00,0x54,
    0x18,0x11,0x40,0xb9,0xf5,0x03,0x09,0x2a,0x08,0x01,0x40,0xf9,0xf0,0xff,0xff,0x17,
    0x75,0x33,0x00,0x34,0xc9,0x00,0x00,0x14,
};
// Exact-current SF: continue through the existing mode controller for an external display.
// Only b.ne at 0x4e3a20 changes to b; the next three instructions stay identical.
static const unsigned char sf_stock[16] = {
    0xa1,0x02,0x00,0x54,0xe1,0xe1,0xff,0xd0,0x21,0x38,0x1a,0x91,0xe2,0xe1,0xff,0xf0
};
static const unsigned char sf_external[16] = {
    0x15,0x00,0x00,0x14,0xe1,0xe1,0xff,0xd0,0x21,0x38,0x1a,0x91,0xe2,0xe1,0xff,0xf0
};
static pid_t tids[512];
static unsigned count;
static volatile sig_atomic_t interrupted;
static void on_signal(int sig) { (void)sig; interrupted = 1; }
static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000LL + ts.tv_nsec / 1000000;
}
static int read_words(pid_t pid, uintptr_t address, unsigned char *out, size_t size) {
    for (size_t i = 0; i < size; i += sizeof(long)) {
        errno = 0;
        long word = ptrace(PTRACE_PEEKDATA, pid, (void *)(address + i), 0);
        if (errno) return -1;
        memcpy(out + i, &word, sizeof(word));
    }
    return 0;
}
static int write_words(pid_t pid, uintptr_t address, const unsigned char *bytes, size_t size) {
    for (size_t i = 0; i < size; i += sizeof(long)) {
        long word;
        memcpy(&word, bytes + i, sizeof(word));
        if (ptrace(PTRACE_POKEDATA, pid, (void *)(address + i), (void *)word)) return -1;
    }
    unsigned char actual[sizeof(selector_stock)];
    return read_words(pid, address, actual, size) || memcmp(actual, bytes, size) ? -1 : 0;
}
static void detach_all(void) {
    while (count) {
        pid_t tid = tids[--count];
        if (ptrace(PTRACE_DETACH, tid, 0, 0) && errno != ESRCH)
            fprintf(stderr, "DETACH failed tid=%d errno=%d\n", tid, errno);
    }
}
static int stop_all(pid_t pid) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/task", pid);
    long long deadline = now_ms() + 1000;
    for (unsigned pass = 0; pass < 8; ++pass) {
        unsigned old_count = count;
        DIR *dir = opendir(path);
        if (!dir) return -1;
        struct dirent *entry;
        while ((entry = readdir(dir))) {
            char *end;
            long n = strtol(entry->d_name, &end, 10);
            if (*end || n <= 0) continue;
            unsigned i;
            for (i = 0; i < count && tids[i] != n; ++i) {}
            if (i != count) continue;
            if (count == 512 || interrupted || now_ms() >= deadline) { closedir(dir); return -1; }
            pid_t tid = (pid_t)n;
            if (ptrace(PTRACE_SEIZE, tid, 0, 0)) {
                if (errno == ESRCH) continue;
                closedir(dir); return -1;
            }
            tids[count++] = tid;
            if (ptrace(PTRACE_INTERRUPT, tid, 0, 0)) { closedir(dir); return -1; }
            int status = 0;
            pid_t waited;
            while ((waited = waitpid(tid, &status, __WALL | WNOHANG)) == 0) {
                if (now_ms() >= deadline) { closedir(dir); return -1; }
                usleep(1000);
            }
            if (waited != tid || !WIFSTOPPED(status)) { closedir(dir); return -1; }
        }
        closedir(dir);
        if (count == old_count) return count ? 0 : -1;
    }
    return -1;
}
struct instruction_window {
    uintptr_t address;
    size_t size;
    const unsigned char *expected;
    const unsigned char *replacement;
};

static int transaction(pid_t pid, const struct instruction_window *windows, unsigned count_windows,
                       uintptr_t pc_start, uintptr_t pc_end) {
    int result = -1;
    long long started = now_ms();
    if (stop_all(pid)) goto done;
    if (pc_start) {
        for (unsigned i = 0; i < count; ++i) {
            struct user_pt_regs regs;
            struct iovec io = { &regs, sizeof(regs) };
            if (ptrace(PTRACE_GETREGSET, tids[i], (void *)1, &io)) goto done;
            // Do not change a selector that is currently executing.
            if ((regs.pc >= pc_start && regs.pc < pc_end) ||
                (regs.regs[30] >= pc_start && regs.regs[30] < pc_end)) goto done;
        }
    }
    unsigned char actual[sizeof(selector_stock)];
    for (unsigned i = 0; i < count_windows; ++i) {
        const struct instruction_window *w = &windows[i];
        if (read_words(pid, w->address, actual, w->size)) goto done;
        printf("MEMORY=");
        for (size_t j = 0; j < w->size; ++j) printf("%02x", actual[j]);
        putchar('\n');
        if (memcmp(actual, w->expected, w->size) || interrupted) goto done;
    }
    for (unsigned i = 0; i < count_windows; ++i) {
        const struct instruction_window *w = &windows[i];
        if (w->replacement && (write_words(pid, w->address, w->replacement, w->size) || interrupted)) {
            for (unsigned j = i + 1; j > 0; --j) {
                const struct instruction_window *undo = &windows[j - 1];
                if (undo->replacement && write_words(pid, undo->address, undo->expected, undo->size))
                    fprintf(stderr, "CRITICAL: original instruction restoration failed\n");
            }
            goto done;
        }
    }
    result = 0;
done:
    detach_all();
    printf("RESULT=%s pause_ms=%lld\n", result ? "REFUSED" : "OK", now_ms() - started);
    return result;
}

extern int smoke_value(void);
__asm__(".text\n.p2align 3\n.global smoke_value\nsmoke_value:\n"
        "mov w0, #60\nret\nnop\nnop\n");
static int selftest(void) {
    volatile int *value = mmap(NULL, 4096, PROT_READ | PROT_WRITE,
                               MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    if (value == MAP_FAILED) return 1;
    pid_t child = fork();
    if (child < 0) return 1;
    if (!child) { for (;;) { *value = smoke_value(); usleep(1000); } }
    unsigned char before[16], after[16];
    memcpy(before, (void *)smoke_value, 16);
    memcpy(after, before, 16);
    uint32_t mov144 = 0x52801200;
    memcpy(after, &mov144, 4);
    usleep(20000);
    int ok = *value == 60;
    struct instruction_window test = { (uintptr_t)smoke_value, sizeof(before), before, after };
    ok = ok && !transaction(child, &test, 1, 0, 0);
    usleep(20000);
    ok = ok && *value == 144;
    printf("CHILD_AFTER_PATCH=%d\n", *value);
    test.expected = after;
    test.replacement = before;
    if (!transaction(child, &test, 1, 0, 0)) {
        usleep(20000);
        ok = ok && *value == 60;
    } else ok = 0;
    printf("CHILD_AFTER_RESTORE=%d\nSELFTEST=%s\n", *value, ok ? "PASS" : "FAIL");
    kill(child, SIGKILL);
    waitpid(child, NULL, 0);
    munmap((void *)value, 4096);
    return ok ? 0 : 1;
}
int main(int argc, char **argv) {
    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    if (argc == 2 && !strcmp(argv[1], "selftest")) return selftest();
    int sf = argc > 1 && !strncmp(argv[1], "sf-", 3);
    int high = argc > 1 && !strncmp(argv[1], "dp", 2) &&
               (!strncmp(argv[1] + 2, "144-", 4) || !strncmp(argv[1] + 2, "165-", 4));
    int manual = argc > 1 && !strncmp(argv[1], "manual-", 7);
    const char *action = argc > 1 ? argv[1] + (sf ? 3 : high ? 6 : manual ? 7 : 0) : "";
    if (argc != (manual ? 4 : 3) || (strcmp(action, "check") && strcmp(action, "apply") &&
                      strcmp(action, "restore"))) {
        fprintf(stderr, "Usage: %s selftest | [sf-|dp144-|dp165-]ACTION PID | manual-ACTION PID HZ\n", argv[0]);
        return 2;
    }
    char *end;
    long n = strtol(argv[2], &end, 10);
    if (*end || n <= 1 || n > 4194304 || getuid() != 0) return 2;
    long hz = 0;
    if (manual) {
        hz = strtol(argv[3], &end, 10);
        if (*end || hz < 61 || hz > 240) return 2;
    }
    pid_t pid = (pid_t)n;
    char path[64], cmdline[256] = {0}, line[1024];
    snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
    FILE *file = fopen(path, "r");
    if (!file) return 1;
    size_t got = fread(cmdline, 1, sizeof(cmdline) - 1, file);
    fclose(file);
    const char *expected_cmd = sf ? "/system/bin/surfaceflinger" :
            "/vendor/bin/hw/android.hardware.graphics.composer3-service.xring";
    if (!got || strcmp(cmdline, expected_cmd)) {
        fprintf(stderr, "Unexpected target command\n"); return 1;
    }
    snprintf(path, sizeof(path), "/proc/%d/maps", pid);
    file = fopen(path, "r");
    if (!file) return 1;
    uintptr_t base = 0;
    unsigned long start, finish, offset;
    char perms[8];
    while (fgets(line, sizeof(line), file)) {
        if (sscanf(line, "%lx-%lx %7s %lx", &start, &finish, perms, &offset) == 4 &&
            !offset && !strcmp(perms, "r--p") &&
            strstr(line, sf ? " /system_ext/lib64/libsurfaceflinger.so\n" :
                             " /vendor/lib64/libxdmcore.so\n")) {
            if (base) { fclose(file); return 1; }
            base = start;
        }
    }
    fclose(file);
    if (!base) { fprintf(stderr, "Expected stock HAL mapping unavailable\n"); return 1; }
    printf("PID=%d BASE=%lx\n", pid, (unsigned long)base);
    int restoring = !strcmp(action, "restore");
    int checking = !strcmp(action, "check");
    const unsigned char *original = sf ? sf_stock : stock;
    const unsigned char *modified = sf ? sf_external : retained;
    struct instruction_window windows[2] = {{ base + (sf ? 0x4e3a20 : 0x7de7c), 16,
        restoring ? modified : original, checking ? NULL : restoring ? original : modified }};
    unsigned count_windows = 1;
    unsigned char selected[sizeof(selector_high)];
    if (high || manual) {
        memcpy(selected, selector_high, sizeof(selected));
        if (manual) {
            const uint32_t rate = 0x7100013fU | ((uint32_t)hz << 10); // cmp w9, #Hz
            const uint32_t skip = 0x540000a1U; // b.ne next_mode
            memcpy(selected + 48, &rate, sizeof(rate));
            memcpy(selected + 52, &skip, sizeof(skip));
        } else if (argv[1][3] == '4') {
            const uint32_t cmp144 = 0x7102413f; // cmp w9, #144 at selector offset 48.
            memcpy(selected + 48, &cmp144, sizeof(cmp144));
        }
        windows[count_windows++] = (struct instruction_window) { base + 0x7db04,
            sizeof(selected), restoring ? selected : selector_stock,
            checking ? NULL : restoring ? selector_stock : selected };
    }
    return transaction(pid, windows, count_windows,
                       base + (sf ? 0x4e38cc : 0x7dacc),
                       base + (sf ? 0x4e42e4 : 0x7e1fc)) ? 1 : 0;
}

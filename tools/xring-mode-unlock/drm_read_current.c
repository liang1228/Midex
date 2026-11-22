/* Read-only DRM mode queries, using Linux DRM UAPI layouts. */
#include <stdint.h>
#include <stdio.h>
#include <fcntl.h>
#include <string.h>
#include <unistd.h>
#include <sys/ioctl.h>

struct resources {
    uint64_t fb_ptr, crtc_ptr, connector_ptr, encoder_ptr;
    uint32_t fbs, crtcs, connectors, encoders;
    uint32_t min_w, max_w, min_h, max_h;
};
struct mode {
    uint32_t clock;
    uint16_t w, hss, hse, ht, hskew, h, vss, vse, vt, vscan;
    uint32_t vrefresh, flags, type;
    char name[32];
};
struct crtc {
    uint64_t connectors_ptr;
    uint32_t connectors, id, fb, x, y, gamma_size, mode_valid;
    struct mode mode;
};
struct connector {
    uint64_t encoders_ptr, modes_ptr, props_ptr, values_ptr;
    uint32_t modes, props, encoders, encoder_id;
    uint32_t id, type, type_id, connection, mm_w, mm_h, subpixel, pad;
};
struct encoder {
    uint32_t id, type, crtc_id, possible_crtcs, possible_clones;
};

static double mode_hz(const struct mode *m) {
    double hz = m->ht && m->vt ? m->clock * 1000.0 / m->ht / m->vt : 0;
    if (m->flags & 16) hz *= 2;
    if (m->flags & 32) hz /= 2;
    if (m->vscan > 1) hz /= m->vscan;
    return hz;
}

static int get_connector(int fd, uint32_t id, struct connector *c) {
    struct mode dummy;
    // A zero mode count can trigger a forced EDID probe on a DRM master fd.
    *c = (struct connector){ .id = id, .modes = 1, .modes_ptr = (uintptr_t)&dummy };
    return ioctl(fd, _IOWR('d', 0xa7, struct connector), c);
}

static int print_external_mode(int fd) {
    uint32_t ids[256];
    struct resources r = { .connector_ptr = (uintptr_t)ids, .connectors = 256 };
    if (ioctl(fd, _IOWR('d', 0xa0, struct resources), &r) || r.connectors > 256) return 1;
    struct connector external = {0};
    for (unsigned i = 0; i < r.connectors; i++) {
        struct connector c;
        if (get_connector(fd, ids[i], &c)) return 1;
        // DRM DisplayPort (10), HDMI-A (11), HDMI-B (12), connected (1).
        if (c.type < 10 || c.type > 12 || c.connection != 1) continue;
        if (external.id) return 1;
        external = c;
    }
    struct encoder e = { .id = external.encoder_id };
    if (!e.id || ioctl(fd, _IOWR('d', 0xa6, struct encoder), &e) || !e.crtc_id) return 1;
    struct crtc c = { .id = e.crtc_id };
    if (ioctl(fd, _IOWR('d', 0xa1, struct crtc), &c) || !c.mode_valid) return 1;
    struct connector current;
    if (get_connector(fd, external.id, &current) || current.connection != 1 ||
        current.encoder_id != e.id) return 1;
    double hz = mode_hz(&c.mode);
    if (!c.mode.w || !c.mode.h || hz <= 0) return 1;
    printf("%u %u %.5f\n", c.mode.w, c.mode.h, hz);
    return 0;
}

int main(int argc, char **argv) {
    _Static_assert(sizeof(struct resources) == 64, "resources UAPI size");
    _Static_assert(sizeof(struct mode) == 68, "mode UAPI size");
    _Static_assert(sizeof(struct crtc) == 104, "CRTC UAPI size");
    _Static_assert(sizeof(struct connector) == 80, "connector UAPI size");
    _Static_assert(sizeof(struct encoder) == 20, "encoder UAPI size");
    if (argc > 2 || (argc == 2 && strcmp(argv[1], "--external") != 0)) return 2;
    int fd = open("/dev/dri/card0", O_RDONLY | O_CLOEXEC);
    if (fd < 0) { perror("open card0"); return 1; }
    if (argc == 2) {
        int status = print_external_mode(fd);
        close(fd);
        return status;
    }
    uint32_t ids[256];
    struct resources r = { .crtc_ptr = (uintptr_t)ids, .crtcs = 256 };
    if (ioctl(fd, _IOWR('d', 0xa0, struct resources), &r)) {
        perror("GETRESOURCES"); close(fd); return 2;
    }
    int failed = 0;
    for (unsigned i = 0; i < r.crtcs && i < 256; i++) {
        struct crtc c = { .id = ids[i] };
        if (ioctl(fd, _IOWR('d', 0xa1, struct crtc), &c)) {
            perror("GETCRTC"); failed = 1; continue;
        }
        struct mode *m = &c.mode;
        double hz = mode_hz(m);
        printf("CRTC id=%u valid=%u fb=%u %ux%u@%.5f clock_kHz=%u htotal=%u vtotal=%u flags=0x%x\n",
            c.id, c.mode_valid, c.fb, m->w, m->h, hz, m->clock, m->ht, m->vt, m->flags);
    }
    close(fd);
    return failed;
}

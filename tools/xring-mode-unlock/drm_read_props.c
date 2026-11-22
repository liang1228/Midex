/* Connector property inventory; no modeset, property write or DRM master.
 * SET_CLIENT_CAP only exposes atomic properties on this private file handle. */
#include <stdint.h>
#include <stdio.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>

struct resources {
    uint64_t fb_ptr, crtc_ptr, connector_ptr, encoder_ptr;
    uint32_t fbs, crtcs, connectors, encoders, min_w, max_w, min_h, max_h;
};
struct mode {
    uint32_t clock;
    uint16_t w, hss, hse, ht, hskew, h, vss, vse, vt, vscan;
    uint32_t vrefresh, flags, type;
    char name[32];
};
struct connector {
    uint64_t encoders_ptr, modes_ptr, props_ptr, values_ptr;
    uint32_t modes, props, encoders, encoder_id;
    uint32_t id, type, type_id, connection, mm_w, mm_h, subpixel, pad;
};
struct property {
    uint64_t values_ptr, enum_ptr;
    uint32_t id, flags;
    char name[32];
    uint32_t values, enums;
};
struct client_cap { uint64_t capability, value; };
int main(void) {
    _Static_assert(sizeof(struct resources) == 64, "resources UAPI");
    _Static_assert(sizeof(struct connector) == 80, "connector UAPI");
    _Static_assert(sizeof(struct property) == 64, "property UAPI");
    int fd = open("/dev/dri/card0", O_RDONLY | O_CLOEXEC);
    if (fd < 0) { perror("open"); return 1; }
    struct client_cap atomic = {.capability = 3, .value = 1};
    if (ioctl(fd, _IOW('d', 0x0d, struct client_cap), &atomic)) {
        perror("atomic client capability"); close(fd); return 1;
    }
    uint32_t ids[256];
    struct resources r = {.connector_ptr = (uintptr_t)ids, .connectors = 256};
    if (ioctl(fd, _IOWR('d', 0xa0, struct resources), &r)) { perror("resources"); return 2; }
    int failed = 0;
    for (unsigned i = 0; i < r.connectors && i < 256; i++) {
        struct mode modes[256];
        uint32_t props[256], encoders[256];
        uint64_t values[256];
        struct connector c = {.id = ids[i], .modes = 256, .props = 256, .encoders = 256,
            .modes_ptr = (uintptr_t)modes, .props_ptr = (uintptr_t)props,
            .values_ptr = (uintptr_t)values, .encoders_ptr = (uintptr_t)encoders};
        if (ioctl(fd, _IOWR('d', 0xa7, struct connector), &c)) { perror("connector"); failed = 1; continue; }
        printf("CONNECTOR id=%u type=%u properties=%u\n", c.id, c.type, c.props);
        for (unsigned j = 0; j < c.props && j < 256; j++) {
            struct property p = {.id = props[j]};
            if (ioctl(fd, _IOWR('d', 0xaa, struct property), &p)) { perror("property"); failed = 1; continue; }
            printf("  id=%u name=%.32s flags=0x%x value=%llu values=%u enums=%u\n",
                p.id, p.name, p.flags, (unsigned long long)values[j], p.values, p.enums);
        }
    }
    close(fd);
    return failed;
}

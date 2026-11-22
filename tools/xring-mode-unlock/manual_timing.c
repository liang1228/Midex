#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

enum { WIDTH = 2560, HEIGHT = 1440, HTOTAL = WIDTH + 160, MIN_HZ = 61, MAX_HZ = 240 };
enum { DRIVER_CLOCK_LIMIT_KHZ = 594000 };

static int number(const char *value, int maximum) {
    char *end;
    long parsed = strtol(value, &end, 10);
    return end != value && !*end && parsed > 0 && parsed <= maximum ? (int)parsed : 0;
}

static int read_edid(const char *path, uint8_t *edid, size_t capacity, size_t *size) {
    static const uint8_t header[] = {0, 255, 255, 255, 255, 255, 255, 0};
    FILE *file = fopen(path, "rb");
    if (!file) return 0;
    *size = fread(edid, 1, capacity, file);
    int complete = !ferror(file) && fgetc(file) == EOF && !ferror(file);
    fclose(file);
    if (!complete || *size < 128 || memcmp(edid, header, sizeof(header)) ||
        edid[18] != 1 || *size != 128U * (1 + edid[126]) || !(edid[54] | edid[55])) return 0;
    for (size_t block = 0; block < *size; block += 128) {
        uint8_t sum = 0;
        for (size_t i = block; i < block + 128; ++i) sum += edid[i];
        if (sum) return 0;
    }
    return 1;
}

static int clock_for(int hz, int budget, const uint8_t *edid, int *vtotal) {
    // Reduced blanking at 160 pixels horizontally and at least 460 us vertically.
    int frame_window = 1000000 - hz * 460;
    *vtotal = (HEIGHT * 1000000 + frame_window - 1) / frame_window;
    if (*vtotal < HEIGHT + 14) *vtotal = HEIGHT + 14;
    // DTD uses 10 kHz units. Stay just below the integer rate used by the HAL.
    int clock = HTOTAL * *vtotal * hz / 10000 * 10;
    if (clock >= budget || clock > DRIVER_CLOCK_LIMIT_KHZ) return 0;
    for (int offset = 72; offset <= 108; offset += 18) {
        const uint8_t *range = edid + offset;
        if (range[0] || range[1] || range[2] || range[3] != 0xfd) continue;
        int flags = edid[19] >= 4 ? range[4] : 0;
        int min_v = range[5] + ((flags & 3) == 3 ? 255 : 0);
        int max_v = range[6] + (flags & 2 ? 255 : 0);
        int min_h = range[7] + ((flags & 12) == 12 ? 255 : 0);
        int max_h = range[8] + (flags & 8 ? 255 : 0);
        if (hz < min_v || (max_v && hz > max_v) ||
            clock < min_h * HTOTAL || (max_h && clock >= (max_h + 1) * HTOTAL) ||
            (range[9] && clock > range[9] * 10000)) return 0;
    }
    return clock;
}

static void set_timing(uint8_t *edid, int clock, int vtotal) {
    uint8_t *dtd = edid + 54;
    for (int offset = 72; offset <= 108; offset += 18) {
        uint8_t *slot = edid + offset;
        if (!slot[0] && !slot[1] && !slot[2] && slot[3] == 0x10) {
            memcpy(slot, dtd, 18);
            break;
        }
    }
    int vblank = vtotal - HEIGHT;
    dtd[0] = clock / 10;
    dtd[1] = clock / 10 >> 8;
    dtd[2] = WIDTH & 255;
    dtd[3] = HTOTAL - WIDTH;
    dtd[4] = (WIDTH >> 8) << 4;
    dtd[5] = HEIGHT & 255;
    dtd[6] = vblank & 255;
    dtd[7] = ((HEIGHT >> 8) << 4) | (vblank >> 8);
    dtd[8] = 48;
    dtd[9] = 32;
    dtd[10] = 0x35;
    dtd[11] = 0;
    dtd[15] = 0;
    dtd[16] = 0;
    dtd[17] = 0x1a;
    uint8_t sum = 0;
    for (int i = 0; i < 127; ++i) sum += edid[i];
    edid[127] = -sum;
}

int main(int argc, char **argv) {
    int prepare = argc == 6 && !strcmp(argv[1], "prepare");
    if (!prepare && (argc != 4 || strcmp(argv[1], "budget"))) return 2;
    int budget = number(argv[3], 2000000);
    uint8_t edid[128 * 256];
    size_t size;
    if (!budget || !read_edid(argv[2], edid, sizeof(edid), &size)) return 1;
    int min = 0, max = 0, vtotal;
    for (int hz = MIN_HZ; hz <= MAX_HZ; ++hz) {
        if (!clock_for(hz, budget, edid, &vtotal)) continue;
        if (!min) min = hz;
        max = hz;
    }
    if (!prepare) {
        printf("BUDGET %d %d %d\n", budget, min, max);
        return 0;
    }
    int hz = number(argv[4], MAX_HZ);
    int clock = hz >= MIN_HZ ? clock_for(hz, budget, edid, &vtotal) : 0;
    if (!clock) return 1;
    set_timing(edid, clock, vtotal);
    FILE *out = fopen(argv[5], "wb");
    if (!out) return 1;
    size_t written = fwrite(edid, 1, size, out);
    if (fclose(out) || written != size) return 1;
    printf("%d\n", clock);
    return 0;
}

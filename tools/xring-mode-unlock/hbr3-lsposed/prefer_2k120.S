// Replaces the stock DP mode-pruning block at 0x7de7c in the pinned libxdmcore.so.
// On entry x19 is the display and w24 is the actual key chosen by the original selector.
.text
.Lstart:
    ldr x8, [x19, #672]
    ldr w11, .Lmin
    ldr w12, .Lmax
.Lloop:
    cbz x8, .Lactivate
    ldrh w9, [x8, #100]
    cmp w9, #2560
    b.ne .Lnext
    ldrh w9, [x8, #110]
    cmp w9, #1440
    b.ne .Lnext
    // All accepted rates are positive, so unsigned IEEE-754 bit ordering suffices.
    ldr w9, [x8, #160]
    cmp w9, w11
    b.lo .Lnext
    cmp w9, w12
    b.hi .Lnext
    ldr w24, [x8, #16]
    b .Lactivate
.Lnext:
    ldr x8, [x8]
    b .Lloop
.Lactivate:
    mov x0, x19
    mov w1, w24
    bl .Lstart + (0xd0a50 - 0x7de7c)
    b .Lstart + (0x7e1c0 - 0x7de7c)
.Lmin: .float 119.98
.Lmax: .float 120.02

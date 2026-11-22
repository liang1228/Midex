// Inserted at libxdmcore.so 0x7db04, after the original guarded prologue.
// Keep the original mode map and config keys; select highest real 2K <=165.
.text
ldr x8, [x19, #672]
mov w21, #0
mov w24, #0
1:
cbz x8, 3f
ldrh w9, [x8, #100]
cmp w9, #2560
b.ne 2f
ldrh w9, [x8, #110]
cmp w9, #1440
b.ne 2f
ldr s0, [x8, #160]
fcvtau w9, s0
cmp w9, #165
b.hi 2f
cmp w9, w21
b.ls 2f
ldr w24, [x8, #16]
mov w21, w9
2:
ldr x8, [x8]
b 1b
3:
// The patch generator appends cbz w21, original_epilogue; b original_call.

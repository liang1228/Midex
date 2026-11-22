.text
.balign 16
.global external_mode_guard
.type external_mode_guard, %function
external_mode_guard:
    // x21 is the original request, x20 the display ID, and w0 the connection type.
    // The call immediately before this branch may clobber x8-x12 and s0-s1.
    cmp     w0, #1
    b.ne    .Lstock
    ldr     x8, [x21, #0x10]
    cbz     x8, .Lstock
    ldr     x9, [x8, #8]
    ldr     x10, =0x4061a9977976f415
    cmp     x9, x20
    b.ne    .Lstock
    cmp     x9, x10
    b.ne    .Lstock

    ldr     w9, [x8, #0x10]
    cmp     w9, #2560
    b.ne    .Lstock
    ldr     w9, [x8, #0x14]
    cmp     w9, #1440
    b.ne    .Lstock

    ldp     w9, w10, [x8]
    cmp     w9, w10
    b.ne    .Lstock
    cmp     w9, #5
    b.hi    .Lstock
    adr     x10, .Ldirect_rates
    ldr     s1, [x10, w9, uxtw #2]
    ldr     s0, [x8, #0x18]
    fsub    s0, s0, s1
    fabs    s0, s0
    ldr     s1, .Lrate_tolerance
    fcmp    s0, s1
    b.mi    .Laccept

    // With the dock, mode 1 is 100 Hz instead of 180 Hz.
    cmp     w9, #1
    b.ne    .Lstock
    ldr     s0, [x8, #0x18]
    ldr     s1, .Lrate_100
    fsub    s0, s0, s1
    fabs    s0, s0
    ldr     s1, .Lrate_tolerance
    fcmp    s0, s1
    b.pl    .Lstock
.Laccept:
    b       stock_accept

.Lstock:
    cmp     w0, #1
    b       stock_branch
.Ldirect_rates:
    .float 60.0, 180.0, 165.0, 144.0, 120.0
.Lrate_100:
    .float 100.0
.Lrate_tolerance:
    .float 0.02
.ltorg
.size external_mode_guard, .-external_mode_guard

.balign 16
.global external_policy_guard
.type external_policy_guard, %function
external_policy_guard:
    // x0 is the physical display record and x25 points into the requested policy.
    cbz     w8, .Lpolicy_accept
    sub     w9, w8, #1
    cbnz    w9, .Lpolicy_refuse
    ldr     x9, [x0]
    ldr     x10, =0x4061a9977976f415
    eor     x9, x9, x10
    cbnz    x9, .Lpolicy_refuse
    ldur    w9, [x25, #-0x90]  // Requested default SurfaceFlinger mode ID.
    // Accept IDs 0 through 5 without changing the caller's flags.
    lsr     w9, w9, #1
    sub     w9, w9, #3
    tbz     w9, #31, .Lpolicy_refuse
.Lpolicy_accept:
    b       policy_accept
.Lpolicy_refuse:
    b       policy_refuse
.ltorg
.size external_policy_guard, .-external_policy_guard

.balign 16
.global scheduled_external_policy_guard
.type scheduled_external_policy_guard, %function
scheduled_external_policy_guard:
    // The scheduled copy repeats the type check with its own DisplayModeSpecs in x28.
    cbz     w8, .Lscheduled_accept
    sub     w9, w8, #1
    cbnz    w9, .Lscheduled_refuse
    ldr     x9, [x0]
    ldr     x10, =0x4061a9977976f415
    eor     x9, x9, x10
    cbnz    x9, .Lscheduled_refuse
    ldur    w9, [x28, #-0x98]  // Requested default SurfaceFlinger mode ID.
    lsr     w9, w9, #1
    sub     w9, w9, #3
    tbz     w9, #31, .Lscheduled_refuse
.Lscheduled_accept:
    b       scheduled_policy_accept
.Lscheduled_refuse:
    b       scheduled_policy_refuse
.ltorg
.size scheduled_external_policy_guard, .-scheduled_external_policy_guard

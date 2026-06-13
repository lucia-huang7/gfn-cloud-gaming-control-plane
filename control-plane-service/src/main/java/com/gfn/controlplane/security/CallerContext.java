package com.gfn.controlplane.security;

public record CallerContext(
        CallerRole role,
        String tenantId,
        String nodeId
) {
    private static final ThreadLocal<CallerContext> CURRENT = new ThreadLocal<>();

    public CallerContext(CallerRole role, String tenantId) {
        this(role, tenantId, null);
    }

    public static void set(CallerContext context) {
        CURRENT.set(context);
    }

    public static CallerContext get() {
        CallerContext context = CURRENT.get();
        if (context == null) {
            throw new IllegalStateException("Caller context is not set");
        }
        return context;
    }

    public static void clear() {
        CURRENT.remove();
    }

    public boolean isAdmin() {
        return role == CallerRole.ADMIN;
    }
}

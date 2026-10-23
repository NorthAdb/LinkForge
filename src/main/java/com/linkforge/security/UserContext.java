package com.linkforge.security;

public final class UserContext {

    private static final ThreadLocal<AuthUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(AuthUser user) {
        HOLDER.set(user);
    }

    public static AuthUser get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}

package me.cortex.nvidium.util;

import me.cortex.nvidium.Nvidium;

/**
 * Entry points into the development-only source set ({@code src/dev/java}: benchmark harness, Vulkan
 * self-tests). Those classes are on the classpath of the dev client but not in the released jar, so main
 * code reaches them by name and treats their absence as "not a dev build".
 */
public final class DevHooks {
    private DevHooks() {}

    /** Calls the public static {@code method(parameterTypes...)} of {@code className} if the class exists. */
    public static void invoke(String className, String method, Class<?>[] parameterTypes, Object... args) {
        try {
            Class.forName(className).getMethod(method, parameterTypes).invoke(null, args);
        } catch (ClassNotFoundException e) {
            Nvidium.LOGGER.warn("{} requested but not present (release build); ignoring", className);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("dev hook " + className + "." + method + " failed", e);
        }
    }
}

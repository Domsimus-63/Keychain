package com.example.accountswitcher.auth;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import net.minecraft.util.Util;

/** Opens links with the browser integration supplied by the running Minecraft version. */
public final class BrowserLauncher {
    private BrowserLauncher() {}

    public static void open(URI uri) throws ReflectiveOperationException {
        try {
            // Minecraft 26.3 moved URL opening from Util.OS to Blaze3D (SDL).
            Class<?> blaze3d = Class.forName("com.mojang.blaze3d.Blaze3D");
            Method openUri = blaze3d.getMethod("openUri", URI.class);
            invoke(openUri, null, uri);
        } catch (ClassNotFoundException | NoSuchMethodException oldVersion) {
            Object platform = Util.getPlatform();
            Method openUri = platform.getClass().getMethod("openUri", URI.class);
            invoke(openUri, platform, uri);
        }
    }

    private static void invoke(Method method, Object target, URI uri)
            throws ReflectiveOperationException {
        try {
            method.invoke(target, uri);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw error;
        }
    }
}

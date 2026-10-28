package io.github.domsimus.keychain.compat.mixin;

import com.mojang.authlib.services.MinecraftServicesDiscoveryService;
import io.github.domsimus.keychain.compat.Compat;
import java.io.File;
import net.minecraft.server.Services;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the services client Minecraft creates at startup. A new client looks up the service
 * addresses in the background and reports "Services are unavailable" until that finishes, so
 * switching accounts must reuse this one, which has long finished by then.
 */
@Mixin(Services.class)
public abstract class ServicesMixin {
    @Inject(method = "create", at = @At("HEAD"))
    private static void keychain$rememberDiscovery(MinecraftServicesDiscoveryService discovery, File gameDirectory, CallbackInfoReturnable<Services> callback) {
        Compat.rememberDiscovery(discovery);
    }
}

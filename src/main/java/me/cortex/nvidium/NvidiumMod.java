package me.cortex.nvidium;

import me.cortex.nvidium.util.DevHooks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

/**
 * NeoForge mod entrypoint. Nvidium hooks into Sodium entirely via mixins,
 * so nothing needs to happen here beyond letting FML know the mod exists.
 */
@Mod(value = "nvidium", dist = Dist.CLIENT)
public class NvidiumMod {
    public NvidiumMod() {
        Nvidium.LOGGER.info("Nvidium {} loaded on NeoForge", Nvidium.MOD_VERSION);
        if (Boolean.getBoolean("nvidium.bench")) {
            DevHooks.invoke("me.cortex.nvidium.bench.BenchHarness", "install", new Class<?>[0]);
        }
    }
}

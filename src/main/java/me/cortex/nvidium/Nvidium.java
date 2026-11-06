package me.cortex.nvidium;

import me.cortex.nvidium.backend.IRenderBackend;
import me.cortex.nvidium.config.NvidiumConfig;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Nvidium {
    public static final String MOD_VERSION;
    public static final Logger LOGGER = LoggerFactory.getLogger("Nvidium");
    public static boolean IS_COMPATIBLE = false;
    public static boolean IS_ENABLED = false;
    public static boolean IS_DEBUG = System.getProperty("nvidium.isDebug", "false").equals("TRUE");
    public static boolean SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER = true;
    public static boolean FORCE_DISABLE = Boolean.getBoolean("nvidium.forceDisable");

    /** The active GPU backend, or {@code null} when the system is not capable. Set once from the device-creation mixins. */
    public static IRenderBackend backend;

    public static NvidiumConfig config = NvidiumConfig.loadOrCreate();

    static {
        ModContainer mod = ModList.get().getModContainerById("nvidium").orElseThrow(NullPointerException::new);
        var version = mod.getModInfo().getVersion().toString();
        var commit = String.valueOf(mod.getModInfo().getModProperties().getOrDefault("commit", "<UnknownCommit>"));
        MOD_VERSION = version+"-"+commit;
    }

    /**
     * Called from the backend-specific device-creation mixin with the backend that matched the
     * created {@code GpuDevice}, or {@code null} when its requirements are not met.
     */
    public static void initBackend(IRenderBackend candidate) {
        backend = candidate;
        IS_COMPATIBLE = candidate != null;
        if (IS_COMPATIBLE) {
            LOGGER.info("All capabilities met ({})", candidate.name());
            SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER = candidate.supportsSparseBuffers();
        } else {
            LOGGER.warn("Not all requirements met, disabling nvidium");
        }

        if (IS_COMPATIBLE) {
            LOGGER.info("Enabling Nvidium");
        }
        IS_ENABLED = IS_COMPATIBLE;
    }
}

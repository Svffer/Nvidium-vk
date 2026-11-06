package me.cortex.nvidium.backend.vk;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.mixin.minecraft.GpuDeviceAccessor;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTConservativeRasterization;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.KHRFragmentShaderBarycentric;
import org.lwjgl.vulkan.NVRepresentativeFragmentTest;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceFragmentShaderBarycentricFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceMeshShaderFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceMeshShaderPropertiesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRepresentativeFragmentTestFeaturesNV;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;

/**
 * M2 of docs/VULKAN_PLAN.md: device bootstrap. Mojang creates the {@code VkDevice} with a fixed set of
 * extensions and features; device features can only be enabled at creation, so
 * {@link me.cortex.nvidium.mixin.minecraft.vk.MixinVulkanBackend} calls {@link #extendDeviceCreation}
 * right before {@code vkCreateDevice} to add what nvidium's mesh-shader pipeline needs, each one only
 * if the physical device supports it. {@link #onDeviceCreated} then records the outcome.
 *
 * <p>Everything is probed the way Mojang does it ({@link VulkanFeature} over a
 * {@code VkPhysicalDeviceFeatures2} pNext chain), see docs/VULKAN_FRAME_PROTOCOL.md §1.
 */
public final class VkDeviceBootstrap {
    private VkDeviceBootstrap() {}

    // --- feature structs ------------------------------------------------------------------------
    private static final VulkanPNextStruct MESH_SHADER_FEATURES = new VulkanPNextStruct(
            EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT,
            VkPhysicalDeviceMeshShaderFeaturesEXT.SIZEOF);
    private static final VulkanPNextStruct BARYCENTRIC_FEATURES = new VulkanPNextStruct(
            KHRFragmentShaderBarycentric.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FRAGMENT_SHADER_BARYCENTRIC_FEATURES_KHR,
            VkPhysicalDeviceFragmentShaderBarycentricFeaturesKHR.SIZEOF);
    private static final VulkanPNextStruct REPRESENTATIVE_FRAGMENT_TEST_FEATURES = new VulkanPNextStruct(
            NVRepresentativeFragmentTest.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_REPRESENTATIVE_FRAGMENT_TEST_FEATURES_NV,
            VkPhysicalDeviceRepresentativeFragmentTestFeaturesNV.SIZEOF);

    // --- required: without these the mesh-shader pipeline cannot be built --------------------------
    private static final VulkanFeature TASK_SHADER =
            new VulkanFeature(MESH_SHADER_FEATURES, "taskShader", VkPhysicalDeviceMeshShaderFeaturesEXT.TASKSHADER);
    private static final VulkanFeature MESH_SHADER =
            new VulkanFeature(MESH_SHADER_FEATURES, "meshShader", VkPhysicalDeviceMeshShaderFeaturesEXT.MESHSHADER);
    private static final VulkanFeature BUFFER_DEVICE_ADDRESS = new VulkanFeature(
            VulkanBackend.VK12_FEATURES_STRUCT,
            "bufferDeviceAddress",
            VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS);
    private static final VulkanFeature STORAGE_BUFFER_8BIT = new VulkanFeature(
            VulkanBackend.VK12_FEATURES_STRUCT,
            "storageBuffer8BitAccess",
            VkPhysicalDeviceVulkan12Features.STORAGEBUFFER8BITACCESS);
    private static final VulkanFeature SHADER_INT8 = new VulkanFeature(
            VulkanBackend.VK12_FEATURES_STRUCT, "shaderInt8", VkPhysicalDeviceVulkan12Features.SHADERINT8);
    private static final VulkanFeature SCALAR_BLOCK_LAYOUT = new VulkanFeature(
            VulkanBackend.VK12_FEATURES_STRUCT,
            "scalarBlockLayout",
            VkPhysicalDeviceVulkan12Features.SCALARBLOCKLAYOUT);
    private static final VulkanFeature STORAGE_BUFFER_16BIT = new VulkanFeature(
            VulkanBackend.VK11_FEATURES_STRUCT,
            "storageBuffer16BitAccess",
            VkPhysicalDeviceVulkan11Features.STORAGEBUFFER16BITACCESS);
    // The scene uniform block ends in uint16_t regionCount / uint8_t frameId (same packing as GL).
    private static final VulkanFeature UNIFORM_8BIT = new VulkanFeature(
            VulkanBackend.VK12_FEATURES_STRUCT,
            "uniformAndStorageBuffer8BitAccess",
            VkPhysicalDeviceVulkan12Features.UNIFORMANDSTORAGEBUFFER8BITACCESS);
    private static final VulkanFeature UNIFORM_16BIT = new VulkanFeature(
            VulkanBackend.VK11_FEATURES_STRUCT,
            "uniformAndStorageBuffer16BitAccess",
            VkPhysicalDeviceVulkan11Features.UNIFORMANDSTORAGEBUFFER16BITACCESS);
    private static final VulkanFeature SHADER_INT64 =
            new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT, "shaderInt64", VkPhysicalDeviceFeatures.SHADERINT64);
    private static final VulkanFeature SHADER_INT16 =
            new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT, "shaderInt16", VkPhysicalDeviceFeatures.SHADERINT16);
    // No geometry shaders are used, but the glslang bundled with LWJGL's shaderc declares the SPIR-V
    // `Geometry` capability for gl_PrimitiveID in mesh and fragment shaders (newer glslang uses
    // MeshShadingEXT); the device must then have geometryShader enabled or vkCreateShaderModule fails.
    private static final VulkanFeature GEOMETRY_SHADER = new VulkanFeature(
            VulkanBackend.VK10_FEATURES_STRUCT, "geometryShader", VkPhysicalDeviceFeatures.GEOMETRYSHADER);

    // --- optional: optimisations of the occlusion raster, one shader each -----------------------------
    private static final VulkanFeature FRAGMENT_SHADER_BARYCENTRIC = new VulkanFeature(
            BARYCENTRIC_FEATURES,
            "fragmentShaderBarycentric",
            VkPhysicalDeviceFragmentShaderBarycentricFeaturesKHR.FRAGMENTSHADERBARYCENTRIC);
    private static final VulkanFeature REPRESENTATIVE_FRAGMENT_TEST = new VulkanFeature(
            REPRESENTATIVE_FRAGMENT_TEST_FEATURES,
            "representativeFragmentTest",
            VkPhysicalDeviceRepresentativeFragmentTestFeaturesNV.REPRESENTATIVEFRAGMENTTEST);

    private static @Nullable VkDeviceCapabilities capabilities;

    /** The capabilities recorded for the live Vulkan device, or {@code null} on other backends. */
    public static @Nullable VkDeviceCapabilities capabilities() {
        return capabilities;
    }

    /**
     * Called by the mixin at the head of Mojang's private {@code createDevice(Collection, VulkanPhysicalDevice,
     * Set)}; both collections are the mutable locals that go straight into {@code VkDeviceCreateInfo}.
     */
    public static void extendDeviceCreation(
            VulkanPhysicalDevice physicalDevice, Collection<String> deviceExtensions, Set<VulkanFeature> features) {
        List<String> missing = new ArrayList<>();
        boolean hasMeshExt = physicalDevice.hasDeviceExtension(EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME);
        boolean hasBarycentricExt = physicalDevice.hasDeviceExtension(
                KHRFragmentShaderBarycentric.VK_KHR_FRAGMENT_SHADER_BARYCENTRIC_EXTENSION_NAME);
        boolean hasRepresentativeExt = physicalDevice.hasDeviceExtension(
                NVRepresentativeFragmentTest.VK_NV_REPRESENTATIVE_FRAGMENT_TEST_EXTENSION_NAME);
        boolean hasConservativeExt = physicalDevice.hasDeviceExtension(
                EXTConservativeRasterization.VK_EXT_CONSERVATIVE_RASTERIZATION_EXTENSION_NAME);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Query every feature of interest in one vkGetPhysicalDeviceFeatures2 call. Extension
            // structs are only chained when the extension exists (chaining an unknown struct is invalid).
            VkPhysicalDeviceFeatures2 supported =
                    VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            List<VulkanFeature> required = new ArrayList<>(List.of(
                    BUFFER_DEVICE_ADDRESS,
                    STORAGE_BUFFER_8BIT,
                    SHADER_INT8,
                    SCALAR_BLOCK_LAYOUT,
                    STORAGE_BUFFER_16BIT,
                    UNIFORM_8BIT,
                    UNIFORM_16BIT,
                    SHADER_INT64,
                    SHADER_INT16,
                    GEOMETRY_SHADER));
            if (hasMeshExt) {
                required.add(TASK_SHADER);
                required.add(MESH_SHADER);
            } else {
                missing.add(EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME);
            }
            List<VulkanFeature> optional = new ArrayList<>();
            if (hasBarycentricExt) optional.add(FRAGMENT_SHADER_BARYCENTRIC);
            if (hasRepresentativeExt) optional.add(REPRESENTATIVE_FRAGMENT_TEST);
            for (VulkanFeature f : required) f.struct().findOrCreateStructInPNextChain(supported, stack);
            for (VulkanFeature f : optional) f.struct().findOrCreateStructInPNextChain(supported, stack);
            VK12.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), supported);

            boolean allRequired = true;
            for (VulkanFeature f : required) {
                if (f.get(supported)) {
                    features.add(f);
                } else {
                    allRequired = false;
                    missing.add(f.name());
                }
            }
            if (hasMeshExt && allRequired) {
                deviceExtensions.add(EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME);
            }

            boolean barycentric = hasBarycentricExt && FRAGMENT_SHADER_BARYCENTRIC.get(supported);
            if (barycentric) {
                deviceExtensions.add(KHRFragmentShaderBarycentric.VK_KHR_FRAGMENT_SHADER_BARYCENTRIC_EXTENSION_NAME);
                features.add(FRAGMENT_SHADER_BARYCENTRIC);
            }
            boolean representative = hasRepresentativeExt && REPRESENTATIVE_FRAGMENT_TEST.get(supported);
            if (representative) {
                deviceExtensions.add(NVRepresentativeFragmentTest.VK_NV_REPRESENTATIVE_FRAGMENT_TEST_EXTENSION_NAME);
                features.add(REPRESENTATIVE_FRAGMENT_TEST);
            }
            if (hasConservativeExt) {
                deviceExtensions.add(EXTConservativeRasterization.VK_EXT_CONSERVATIVE_RASTERIZATION_EXTENSION_NAME);
            }

            VkDeviceCapabilities.MeshShaderLimits limits = hasMeshExt ? queryMeshLimits(physicalDevice, stack) : null;

            capabilities = new VkDeviceCapabilities(
                    hasMeshExt && TASK_SHADER.get(supported) && MESH_SHADER.get(supported),
                    BUFFER_DEVICE_ADDRESS.get(supported),
                    SHADER_INT64.get(supported),
                    SHADER_INT16.get(supported),
                    SHADER_INT8.get(supported),
                    STORAGE_BUFFER_8BIT.get(supported),
                    STORAGE_BUFFER_16BIT.get(supported),
                    SCALAR_BLOCK_LAYOUT.get(supported),
                    barycentric,
                    representative,
                    hasConservativeExt,
                    limits,
                    List.copyOf(missing));
        }
    }

    private static VkDeviceCapabilities.MeshShaderLimits queryMeshLimits(
            VulkanPhysicalDevice physicalDevice, MemoryStack stack) {
        VkPhysicalDeviceMeshShaderPropertiesEXT mesh =
                VkPhysicalDeviceMeshShaderPropertiesEXT.calloc(stack).sType$Default();
        VkPhysicalDeviceProperties2 props =
                VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(mesh);
        VK12.vkGetPhysicalDeviceProperties2(physicalDevice.vkPhysicalDevice(), props);
        return new VkDeviceCapabilities.MeshShaderLimits(
                mesh.maxTaskWorkGroupTotalCount(),
                mesh.maxTaskWorkGroupInvocations(),
                mesh.maxTaskPayloadSize(),
                mesh.maxMeshWorkGroupInvocations(),
                mesh.maxMeshOutputVertices(),
                mesh.maxMeshOutputPrimitives(),
                mesh.maxPreferredTaskWorkGroupInvocations(),
                mesh.maxPreferredMeshWorkGroupInvocations(),
                mesh.prefersLocalInvocationVertexOutput(),
                mesh.prefersLocalInvocationPrimitiveOutput());
    }

    /**
     * Called by the mixin when Mojang's {@code createDevice} returns. Creates the Vulkan render backend and
     * enables nvidium through {@link Nvidium#initBackend} when every requirement is met (plan M5), exactly as
     * the GL device mixin does for OpenGL.
     */
    public static void onDeviceCreated(GpuDevice device) {
        VkDeviceCapabilities caps = capabilities;
        if (caps == null) {
            Nvidium.LOGGER.warn("Vulkan device created without nvidium's bootstrap hook; disabling nvidium");
            Nvidium.initBackend(null);
            return;
        }
        Set<String> enabled = Set.of();
        if (((GpuDeviceAccessor) device).nvidium$getBackend() instanceof VulkanDevice vk) {
            enabled = vk.getDeviceInfo().underlyingExtensions();
        }
        boolean meshEnabled = enabled.contains(EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME + " (D)");
        VkContext ctx = VkContext.from(device);
        if (caps.meetsRequirements() && meshEnabled && ctx != null) {
            Nvidium.LOGGER.info(
                    "Vulkan device: VK_EXT_mesh_shader, bufferDeviceAddress, int64/int16/int8, 8/16-bit storage, "
                            + "scalarBlockLayout; optional: barycentric={}, representativeFragmentTest={}, "
                            + "conservativeRasterization={}",
                    caps.fragmentShaderBarycentric(),
                    caps.representativeFragmentTest(),
                    caps.conservativeRasterization());
            Nvidium.LOGGER.info("Mesh shader limits: {}", caps.meshLimits());
            if (Boolean.getBoolean("nvidium.vkSelfTest")) {
                VkSelfTest.run(device, caps);
            }
            Nvidium.initBackend(new VkRenderBackend(ctx, caps));
        } else {
            Nvidium.LOGGER.warn(
                    "Not all requirements met for nvidium on Vulkan, missing: {}{}",
                    caps.missing(),
                    meshEnabled ? "" : " (VK_EXT_mesh_shader not enabled on the device)");
            Nvidium.initBackend(null);
        }
    }
}

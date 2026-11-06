package me.cortex.nvidium.backend.vk;

import java.util.List;

/**
 * What the Vulkan device Mojang created can do for nvidium. Filled in by {@link VkDeviceBootstrap}
 * while the device is being created (the physical device is closed right after), so everything the
 * Vulkan backend needs to know about limits is captured here.
 *
 * @param missing required capabilities the device lacks; empty means nvidium can run on it
 */
public record VkDeviceCapabilities(
        boolean meshShader,
        boolean bufferDeviceAddress,
        boolean shaderInt64,
        boolean shaderInt16,
        boolean shaderInt8,
        boolean storageBuffer8BitAccess,
        boolean storageBuffer16BitAccess,
        boolean scalarBlockLayout,
        boolean fragmentShaderBarycentric,
        boolean representativeFragmentTest,
        boolean conservativeRasterization,
        MeshShaderLimits meshLimits,
        List<String> missing) {

    public boolean meetsRequirements() {
        return missing.isEmpty();
    }

    /** {@code VkPhysicalDeviceMeshShaderPropertiesEXT}, the subset that constrains nvidium's shaders. */
    public record MeshShaderLimits(
            int maxTaskWorkGroupTotalCount,
            int maxTaskWorkGroupInvocations,
            int maxTaskPayloadSize,
            int maxMeshWorkGroupInvocations,
            int maxMeshOutputVertices,
            int maxMeshOutputPrimitives,
            int maxPreferredTaskWorkGroupInvocations,
            int maxPreferredMeshWorkGroupInvocations,
            boolean prefersLocalInvocationVertexOutput,
            boolean prefersLocalInvocationPrimitiveOutput) {}
}

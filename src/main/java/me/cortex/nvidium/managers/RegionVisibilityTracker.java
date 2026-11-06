package me.cortex.nvidium.managers;

import static me.cortex.nvidium.backend.ShaderType.FRAGMENT;
import static me.cortex.nvidium.backend.ShaderType.MESH;

import me.cortex.nvidium.backend.Barrier;
import me.cortex.nvidium.backend.IBuffer;
import me.cortex.nvidium.backend.IRenderBackend;
import me.cortex.nvidium.backend.IShader;
import me.cortex.nvidium.backend.NvidiumShader;
import me.cortex.nvidium.sodiumCompat.ShaderLoader;
import me.cortex.nvidium.util.DownloadTaskStream;
import org.lwjgl.system.MemoryUtil;

public class RegionVisibilityTracker {
    private final IRenderBackend backend;
    private final IShader shader;

    private final DownloadTaskStream downStream;
    private final int[] frustum;
    private final int[] visible;

    public RegionVisibilityTracker(IRenderBackend backend, DownloadTaskStream downStream, int maxRegions) {
        this.backend = backend;
        this.shader = backend.createShader()
                .addSource(MESH, ShaderLoader.parse(backend.shaderSource(NvidiumShader.REGION_QUERY_MESH)))
                .addSource(FRAGMENT, ShaderLoader.parse(backend.shaderSource(NvidiumShader.REGION_QUERY_FRAG)))
                .compile();
        this.downStream = downStream;
        visible = new int[maxRegions];
        frustum = new int[maxRegions];
        for (int i = 0; i < maxRegions; i++) {
            frustum[i] = 0;
            visible[i] = 0;
        }
    }

    private int frame = 0;
    // This is kind of evil in the fact that it just reuses the visibility buffer
    public void computeVisibility(int regionCount, IBuffer regionVisibilityBuffer, short[] regionMapping) {
        frame++;
        backend.drawMeshTasks(shader, 0, (regionCount + 3) / 4);
        backend.barrier(Barrier.SHADER_STORAGE);
        downStream.download(regionVisibilityBuffer, 0, regionCount, ptr -> {
            for (int i = 0; i < regionMapping.length; i++) {
                if (MemoryUtil.memGetByte(ptr + i) == 1) {
                    // System.out.println(regionMapping[i] + " was visible");
                    frustum[regionMapping[i]]++;
                    visible[regionMapping[i]] = frame;
                } else {
                    // System.out.println(regionMapping[i] + " was not visible");
                    frustum[regionMapping[i]]++;
                }
            }
        });
    }

    public void delete() {
        shader.delete();
    }

    public void resetRegion(int id) {
        frustum[id] = 0;
        visible[id] = 0;
    }

    public int findMostLikelyLeastSeenRegion(int maxIndex) {
        int maxRank = Integer.MIN_VALUE;
        int id = -1;
        for (int i = 0; i < maxIndex; i++) {
            if (frustum[i] <= 200) continue;
            int rank = -visible[i];
            // int rank = -visible[i];
            if (maxRank < rank) {
                maxRank = rank;
                id = i;
            }
        }
        return id;
    }
}

// The 12 triangles of the occlusion box, in the primitive order of the GL shaders: the NV versions
// write a flat 36-entry gl_PrimitiveIndicesNV from the PILUTA..PILUTE tables (8 threads x 4 indices,
// then 4 threads x 1); GL_EXT_mesh_shader takes one uvec3 per primitive instead. Generated from
// those tables, do not edit by hand (bench/compile-vk-shaders.py keeps nothing here; see git history).
const uvec3 BOX_TRIANGLES[12] = uvec3[12](
    uvec3(0, 1, 2),
    uvec3(1, 3, 2),
    uvec3(0, 2, 6),
    uvec3(6, 4, 0),
    uvec3(0, 4, 5),
    uvec3(5, 1, 0),
    uvec3(1, 5, 7),
    uvec3(7, 3, 1),
    uvec3(4, 6, 7),
    uvec3(7, 5, 4),
    uvec3(2, 7, 6),
    uvec3(2, 3, 7)
);

// Thread `tid` (0..7) of a box batch emits triangle tid and, for tid < 4, triangle 8 + tid.
void emitBox(uint batchIdx, uint tid, int visIndex) {
    uint baseVtx = batchIdx * 8;
    uint basePrim = batchIdx * 12;
    gl_PrimitiveTriangleIndicesEXT[basePrim + tid] = BOX_TRIANGLES[tid] + baseVtx;
    gl_MeshPrimitivesEXT[basePrim + tid].gl_PrimitiveID = visIndex;
    if (tid < 4) {
        gl_PrimitiveTriangleIndicesEXT[basePrim + 8 + tid] = BOX_TRIANGLES[8 + tid] + baseVtx;
        gl_MeshPrimitivesEXT[basePrim + 8 + tid].gl_PrimitiveID = visIndex;
    }
}

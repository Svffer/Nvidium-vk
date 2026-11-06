#moj_import <nvidium:occlusion/scene_types.glsl>

layout(std140, binding=0) uniform SceneData {
    //Need to basically go in order of alignment
    //align(16)
    mat4 MVP;
    #ifdef RENDER_FOG
    mat4 MVPInv;
    #endif
    ivec4 chunkPosition;
    vec4 subchunkOffset;

    //vec4  subChunkPosition;//The subChunkTranslation is already done inside the MVP
    //align(8)
    readonly restrict uint16_t *regionIndices;//Pointer to block of memory at the end of the SceneData struct, also mapped to be a uniform
    readonly restrict Region *regionData;
    restrict Section *sectionData;
    //NOTE: for the following, can make it so that region visibility actually uses section visibility array
    restrict uint8_t *regionVisibility;
    restrict uint8_t *sectionVisibility;
    restrict u8vec3  *sectionIndices;
    //Terrain command buffer, the first 4 bytes are actually the count
    writeonly restrict uvec2 *terrainCommandBuffer;
    writeonly restrict uvec2 *translucencyCommandBuffer;
    writeonly restrict uvec2 *temporalCommandBuffer;

    readonly restrict uint16_t *sortingRegionList;

    //TODO:FIXME: only apply non readonly to translucency mesh
    restrict Vertex *terrainData;//readonly
    restrict uint   *translucencyIndexData;

    //TODO: possibly make this a uniform instead of a buffer, but it might get quite large is the issue
    readonly restrict mat4 *transformationArray;
    readonly restrict uint64_t *originArray;

    //readonly restrict u64vec4 *terrainData;
    //uvec4 *terrainData;

    uint32_t *statistics_buffer;

    vec2 screenSize;

    vec4 fogColour;
    vec2 environmentFog;
    vec2 renderFog;

    vec2 texCoordShrink;
    vec2 texelSize;

    uint flags;

    //align(2)
    uint16_t regionCount;//Number of regions in regionIndices
    //align(1)
    uint8_t frameId;
};

mat4 getRegionTransformation(Region region) {
    return transformationArray[unpackRegionTransformId(region)];
}

ivec3 unpackOriginOffsetId(uint id) {
    uint64_t val = originArray[id];
    int x = (int(uint(val&0x1ffffff))<<7)>>7;
    int y = (int(uint((val>>50)&0x3fff))<<18)>>18;
    int z = (int(uint((val>>25)&0x1ffffff))<<7)>>7;
    return ivec3(x,y,z);
}

bool useBlockFaceCulling() {
    return (flags&1)!=0;
}

bool useRGSS() {
    return (flags&2)!=0;
}

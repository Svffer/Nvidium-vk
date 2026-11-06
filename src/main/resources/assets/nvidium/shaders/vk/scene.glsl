// Vulkan scene data. Same byte layout as the OpenGL SceneData block (RenderPipeline.SCENE_SIZE): the
// scalar block layout reproduces the sequential packing the Java side writes. Every GL
// NV_shader_buffer_load pointer is a GL_EXT_buffer_reference2 reference here; the `name_` members are
// exposed as `name` so that `name[i]` in the shader bodies expands to `name_.v[i]` and the code
// reads like the GL version.
//
// Entry shaders must declare before importing this file:
//   #extension GL_EXT_buffer_reference2 : require
//   #extension GL_EXT_scalar_block_layout : require
//   #extension GL_EXT_shader_explicit_arithmetic_types_int8 : require
//   #extension GL_EXT_shader_explicit_arithmetic_types_int16 : require
//   #extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
#moj_import <nvidium:occlusion/scene_types.glsl>

layout(buffer_reference, scalar, buffer_reference_align = 2) readonly buffer U16Ref { uint16_t v[]; };
layout(buffer_reference, scalar, buffer_reference_align = 8) readonly buffer RegionRef { Region v[]; };
layout(buffer_reference, scalar, buffer_reference_align = 16) buffer SectionRef { Section v[]; };
layout(buffer_reference, scalar, buffer_reference_align = 1) buffer U8Ref { uint8_t v[]; };
layout(buffer_reference, scalar, buffer_reference_align = 1) buffer U8Vec3Ref { u8vec3 v[]; };
layout(buffer_reference, scalar, buffer_reference_align = 8) writeonly buffer UVec2Ref { uvec2 v[]; };
#ifdef USE_SODIUM_VERTEX_FORMAT
layout(buffer_reference, scalar, buffer_reference_align = 4) buffer VertexRef { Vertex v[]; };   // 20-byte stride
#else
layout(buffer_reference, scalar, buffer_reference_align = 16) buffer VertexRef { Vertex v[]; };  // uvec4
#endif
layout(buffer_reference, scalar, buffer_reference_align = 4) buffer U32Ref { uint v[]; };
layout(buffer_reference, scalar, buffer_reference_align = 16) readonly buffer Mat4Ref { mat4 v[]; };
layout(buffer_reference, scalar, buffer_reference_align = 8) readonly buffer U64Ref { uint64_t v[]; };

layout(set = 0, binding = 2, scalar) uniform SceneData {
    mat4 MVP;
    #ifdef RENDER_FOG
    mat4 MVPInv;
    #endif
    ivec4 chunkPosition;
    vec4 subchunkOffset;

    U16Ref regionIndices_;   // block at the end of the scene buffer (see RenderPipeline)
    RegionRef regionData_;
    SectionRef sectionData_;
    U8Ref regionVisibility_;
    U8Ref sectionVisibility_;
    U8Vec3Ref sectionIndices_;
    UVec2Ref terrainCommandBuffer_;
    UVec2Ref translucencyCommandBuffer_;
    UVec2Ref temporalCommandBuffer_;
    U16Ref sortingRegionList_;
    VertexRef terrainData_;
    U32Ref translucencyIndexData_;
    Mat4Ref transformationArray_;
    U64Ref originArray_;
    U32Ref statistics_buffer_;

    vec2 screenSize;

    vec4 fogColour;
    vec2 environmentFog;
    vec2 renderFog;

    vec2 texCoordShrink;
    vec2 texelSize;

    uint flags;

    uint16_t regionCount;
    uint8_t frameId;
};

#define regionIndices regionIndices_.v
#define regionData regionData_.v
#define sectionData sectionData_.v
#define regionVisibility regionVisibility_.v
#define sectionVisibility sectionVisibility_.v
#define sectionIndices sectionIndices_.v
#define terrainCommandBuffer terrainCommandBuffer_.v
#define translucencyCommandBuffer translucencyCommandBuffer_.v
#define temporalCommandBuffer temporalCommandBuffer_.v
#define sortingRegionList sortingRegionList_.v
#define terrainData terrainData_.v
#define translucencyIndexData translucencyIndexData_.v
#define transformationArray transformationArray_.v
#define originArray originArray_.v
#define statistics_buffer statistics_buffer_.v

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

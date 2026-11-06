// Shared by the OpenGL (occlusion/scene.glsl) and Vulkan (vk/scene.glsl) scene definitions.
#ifdef USE_SODIUM_VERTEX_FORMAT
struct Vertex {
    uint hi;
    uint lo;
    uint color;

    uint16_t u;
    uint16_t v;

    uint8_t blockLight;
    uint8_t skyLight;
    uint8_t material;
    uint8_t section;
};
#else
#define Vertex uvec4
#endif

// this is cause in the section rasterizer you get less cache misses thus higher throughput
struct Section {
    ivec4 header;
    //Header.x -> 0-3=offsetx 4-7=sizex 8-31=chunk x
    //Header.y -> 0-3=offsetz 4-7=sizez 8-31=chunk z
    //Header.z -> 0-3=offsety 4-7=sizey 8-15=chunk y
    //Header.w -> quad offset
    ivec4 renderRanges;
    int   translucentQuadCount;
    int   translucencyDataIdx;
    // Java writes 48-byte records (SectionManager.SECTION_SIZE). The GL pointer layout pads the struct to
    // its 16-byte alignment on its own; the Vulkan scalar layout does not, so the padding is explicit.
    ivec2 _padding;
};

struct Region {
    uint64_t a;
    uint64_t b;
};

ivec3 unpackRegionSize(Region region) {
    return ivec3((region.a>>59)&7, region.a>>62, (region.a>>56)&7);
}

uint unpackRegionTransformId(Region region) {
    return uint((region.b>>(64-24-10))&((1<<10)-1));
}

ivec3 unpackRegionPosition(Region region) {
    //TODO: optimize
    int x = int(int64_t(region.a<<(64-24-24))>>(64-24));
    int y = (int(region.a)<<8)>>8;
    int z = int(int64_t(region.b)>>(64-24));
    return ivec3(x,y,z);
}

int unpackRegionCount(Region region) {
    return int((region.a>>48)&255);
}

bool sectionEmpty(ivec4 header) {
    header.y &= ~0x1FF<<17;
    return header == ivec4(0);
}

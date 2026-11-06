#define MESH_WORKLOAD_PER_INVOCATION 32

//originAndBaseData.w is in quad count space
struct TranslucentTask {
    vec4 originAndBaseData;
    uint quadCount;
    #ifdef TRANSLUCENCY_SORTING_QUADS
    uint jiggle;
    #endif
    int translucencyIndex;
};

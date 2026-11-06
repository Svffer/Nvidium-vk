struct SectionTask {
    uint _visOutBase;// The base offset for the visibility output of the shader
    uint _offset;//start offset for regions (region id << 8)
    mat4 regionTransform;
    ivec3 chunkShift;
    uint sectionCount;
};

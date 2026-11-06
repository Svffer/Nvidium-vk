#version 460
// Fragment stage of section_raster: the primitive id carries (visibility index << 8) | history bits.
#moj_import <nvidium:vk/common.glsl>

#moj_import <nvidium:vk/scene.glsl>
layout(early_fragment_tests) in;

#ifdef DEBUG
layout(location = 0) out vec4 colour;
void main() {
    uint uid = bitfieldReverse(gl_PrimitiveID*132471+123571);
    colour = vec4(float((uid>>0)&7)/7, float((uid>>3)&7)/7, float((uid>>6)&7)/7, 1.0);
    sectionVisibility[gl_PrimitiveID>>8] = uint8_t(gl_PrimitiveID);
}
#else
void main() {
    sectionVisibility[gl_PrimitiveID>>8] = uint8_t(gl_PrimitiveID);
}
#endif

#version 460
#extension GL_ARB_shading_language_include : enable
#pragma optionNV(unroll all)
#define UNROLL_LOOP
#extension GL_NV_gpu_shader5 : require
#extension GL_NV_bindless_texture : require
#extension GL_NV_shader_buffer_load : require

//#extension GL_NV_conservative_raster_underestimation : enable

#extension GL_NV_fragment_shader_barycentric : require


#import <nvidium:occlusion/scene.glsl>
#import <nvidium:terrain/fog.glsl>
#import <nvidium:terrain/vertex_format.glsl>




layout(location = 0) out vec4 colour;
layout(location = 1) in Interpolants {
    f16vec2 uv;
};


layout(binding = 1) uniform sampler2D tex_light;

vec4 sampleLight(vec2 uv) {
    //Its divided by 16 to match sodium/vanilla (it can never be 1 which is funny)
    return vec4(texture(tex_light, uv).rgb, 1);
}

vec3 computeMultiplier(Vertex V) {
    vec4 tint = decodeVertexColour(V);
    tint *= sampleLight(decodeLightUV(V));
    tint *= tint.w;
    return tint.xyz;
}

vec4 getOutputColour(vec4 colour, uint quadId, bool triangle0) {

    /*
    //TODO: keep pos around instead of retransfroming it here and in transformVertex
    vec3 pos = decodeVertexPosition(V)+origin;


    OUT[id].tint = f16vec3(tint.xyz);

    vec3 tintO;
    vec3 addiO;
    computeFog(isCylindricalFog, pos+subchunkOffset.xyz, tint, fogColour, fogStart, fogEnd, tintO, addiO);
    OUT[id].tint = f16vec3(tintO);
    OUT[id].addin = f16vec3(addiO);
    */

    uvec3 TRI_INDICIES = triangle0?uvec3(0,1,2):uvec3(2,3,0);
    Vertex V0 = terrainData[(quadId<<2)+TRI_INDICIES.x];
    Vertex Vp = terrainData[(quadId<<2)+TRI_INDICIES.y];
    Vertex V2 = terrainData[(quadId<<2)+TRI_INDICIES.z];

    vec3 multiplier = gl_BaryCoordNV.x*computeMultiplier(V0) + gl_BaryCoordNV.y*computeMultiplier(Vp) + gl_BaryCoordNV.z*computeMultiplier(V2);
    colour.rgb *= multiplier;

    return colour;
}


layout(binding = 0) uniform sampler2D tex_diffuse;
void main() {
    #ifdef TRANSLUCENT_PASS
    colour = texture(tex_diffuse, uv, 0);
    #else
    colour = texture(tex_diffuse, uv, ((gl_PrimitiveID>>2)&1)*-8.0f);
    if (colour.a < getVertexAlphaCutoff(uint(gl_PrimitiveID&3))) discard;
    #endif
    colour = getOutputColour(colour, uint(gl_PrimitiveID)>>4, uint((gl_PrimitiveID>>3)&1)==0);
    #ifndef TRANSLUCENT_PASS
    colour.a = 1;
    #endif
}
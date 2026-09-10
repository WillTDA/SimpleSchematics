#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float WorldPass;
uniform float NearFade;
uniform float FadeStart;
uniform float FadeEnd;
uniform float FogStart;
uniform float FogEnd;

in vec4 vertexColour;
in vec2 texCoord;
in vec3 viewPosition;

out vec4 fragColor;

void main() {
    vec4 texel = texture(Sampler0, texCoord);
    // Clip transparent texels before applying ghost opacity. Otherwise lowering
    // opacity would make leaves, panes and fences suddenly disappear.
    if (texel.a < 0.1) discard;

    vec4 colour = texel * vertexColour * ColorModulator;
    if (WorldPass > 0.5) {
        float distanceToEye = length(viewPosition);
        if (NearFade > 0.5) {
            colour.a *= smoothstep(FadeStart, max(FadeEnd, FadeStart + 0.001), distanceToEye);
        }
        if (FogEnd > FogStart) {
            // Fade away in distant fog instead of laying an opaque fog colour
            // over the real scene. Library previews do not use world fog.
            colour.a *= 1.0 - smoothstep(FogStart, FogEnd, distanceToEye);
        }
    }
    if (colour.a <= 0.001) discard;
    fragColor = colour;
}

#ifndef SCPACCESS_TEXT_FRAGMENT_GLSL
#define SCPACCESS_TEXT_FRAGMENT_GLSL

// ScpAccess: fragment part of the core/text override - CRT scan lines over the reader screen text (see the vertex part).

layout(location = 9) in float scpaccessFx;

// Call right after main() computed `color`; returns the colour to continue with.
vec4 scpaccess_text_fragment(vec4 color) {
    if (scpaccessFx < 0.5) return color;
    float line = 0.72 + 0.28 * step(0.5, fract(gl_FragCoord.y * 0.5));
    return vec4(color.rgb * line * 1.15, color.a);
}

#endif

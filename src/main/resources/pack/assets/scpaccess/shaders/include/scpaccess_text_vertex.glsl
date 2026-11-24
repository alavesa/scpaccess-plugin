#ifndef SCPACCESS_TEXT_VERTEX_GLSL
#define SCPACCESS_TEXT_VERTEX_GLSL

// =====================================================================================================================
// ScpAccess: vertex part of the core/text override - the screen of a keycard reader.
//
// The reader's screen is a text display in WORLD space. Its text colour is the marker #F3ACxx (red 243, green 172):
//   blue bits 0-1  mode   0 = idle (cyan), 1 = granted (green), 2 = denied (red), 3 = warning (amber)
//   blue bits 2-7  phase  0..63, advanced by the server a few times a second: flicker without any clock uniform
// Marked text is drawn full-bright in the mode's colour with a little flicker, and the fragment part adds CRT scan
// lines. Everything else - signs, name tags, other plugins' text, the GUI - is untouched.
//
// No Globals/GameTime here on purpose (world text pipelines are not guaranteed to bind them); the one varying sits at
// location 9, clear of vanilla (0-3) and of the other plugins' hooks (4-8).
// =====================================================================================================================

layout(location = 9) out float scpaccessFx;

// Call at the END of main(), with the colour attribute and the vertex colour main() computed; returns the vertex colour.
vec4 scpaccess_text_vertex(vec4 color, vec4 lit) {
    scpaccessFx = 0.0;
#ifndef IS_GUI
    ivec3 c = ivec3(round(color.rgb * 255.0));
    if (c.r != 243 || c.g != 172) return lit;
    int mode = c.b & 3;
    float phase = float(c.b >> 2);
    vec3 tint = mode == 1 ? vec3(0.25, 1.0, 0.40) : mode == 2 ? vec3(1.0, 0.22, 0.18) : mode == 3 ? vec3(1.0, 0.72, 0.15) : vec3(0.30, 0.85, 1.0);
    float flicker = 0.88 + 0.12 * sin(phase * 2.399963) + (mode == 2 ? 0.10 * sin(phase * 7.1) : 0.0);
    scpaccessFx = 1.0 + float(mode);
    return vec4(tint * clamp(flicker, 0.0, 1.2), color.a);
#else
    return lit;
#endif
}

#endif

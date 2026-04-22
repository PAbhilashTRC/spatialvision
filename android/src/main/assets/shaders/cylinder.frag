#version 300 es
precision mediump float;

uniform vec4 u_Color;

// Lighting uniforms
uniform vec3 uPointLightingLocation;
uniform vec3 uAmbientColor;
uniform vec3 uAttenuation;

in vec3 v_Normal;
in vec3 v_WorldPos;

out vec4 o_FragColor;

// ---------------- Noise ----------------
float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);

    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));

    vec2 u = f * f * (3.0 - 2.0 * f);

    return mix(a, b, u.x) +
           (c - a) * u.y * (1.0 - u.x) +
           (d - b) * u.x * u.y;
}

// ---------------- Main ----------------
void main() {

    vec3 normal = normalize(v_Normal);

    // ======================================================
    // 🔥 OBJECT-SPACE HEIGHT (FIXED REALISM)
    // ======================================================
    float height = v_WorldPos.y;

    // Better gradient instead of raw world Y
    float heightFactor = smoothstep(0.0, 8.0, height);

    // ======================================================
    // 🔥 WOOD AGE NOISE (crucial for SPIDA look)
    // ======================================================
    float n = noise(v_WorldPos.xz * 2.5);

    // ======================================================
    // 🔥 BASE COLOR (with aging variation)
    // ======================================================
    vec3 baseColor = u_Color.rgb;

    // wood grain variation
    baseColor *= (0.82 + 0.18 * n);

    // vertical aging (bottom darker)
    baseColor *= mix(0.65, 1.05, heightFactor);

    // slight desaturation (real wood is not vivid)
    baseColor *= 0.92;

    // ======================================================
    // 🔥 LIGHTING
    // ======================================================
    vec3 lightDir = normalize(uPointLightingLocation - v_WorldPos);
    vec3 viewDir = normalize(-v_WorldPos);
    vec3 reflectDir = reflect(-lightDir, normal);

    float dist = length(uPointLightingLocation - v_WorldPos);

    float attenuation = 1.0 / (
        uAttenuation.x +
        uAttenuation.y * dist +
        uAttenuation.z * dist * dist
    );

    // ======================================================
    // 🔥 AMBIENT (soft environmental fill)
    // ======================================================
    vec3 ambient = 0.55 * uAmbientColor * baseColor;

    // ======================================================
    // 🔥 DIFFUSE (wrap lighting = more realistic wood)
    // ======================================================
    float wrap = 0.25;
    float diff = max(0.0, (dot(normal, lightDir) + wrap) / (1.0 + wrap));
    vec3 diffuse = diff * baseColor;

    // ======================================================
    // 🔥 SPECULAR (VERY LOW - wood is rough)
    // ======================================================
    float specPower = 10.0;
    float spec = pow(max(dot(reflectDir, viewDir), 0.0), specPower);

    vec3 specular = spec * vec3(0.08); // subtle only

    // ======================================================
    // 🔥 FINAL COMPOSITION
    // ======================================================
    vec3 finalColor = ambient + attenuation * (diffuse + specular);

    o_FragColor = vec4(finalColor, u_Color.a);
}
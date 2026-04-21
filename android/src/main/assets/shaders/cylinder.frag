#version 300 es
precision mediump float;

uniform vec4 u_Color;

// Lighting uniforms
uniform vec3 uPointLightingLocation;

uniform vec3 uAmbientColor;
// uniform vec4 uDiffuseColor;
// uniform vec4 uSpecularColor;

uniform vec3 uAttenuation;
// uniform float uMaterialShininess;

in vec3 v_Normal;
in vec3 v_WorldPos;

out vec4 o_FragColor;

// Add this at top
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
           (c - a)* u.y * (1.0 - u.x) +
           (d - b)* u.x * u.y;
}

void main() {

    vec3 normal = normalize(v_Normal);

    // 🔥 Add vertical gradient (pole aging look)
    float heightFactor = v_WorldPos.y * 0.2;

    // 🔥 Add subtle noise
    float n = noise(v_WorldPos.xz * 3.0);

    // Base color variation
    vec3 baseColor = u_Color.rgb;
    baseColor *= 0.85 + 0.15 * n;
    baseColor *= 0.9 + 0.1 * heightFactor;

    // Light directions
    vec3 lightDir = normalize(uPointLightingLocation - v_WorldPos);
    vec3 viewDir = normalize(-v_WorldPos); // camera at origin assumption
    vec3 reflectDir = reflect(-lightDir, normal);

    // Distance for attenuation
    float dist = length(uPointLightingLocation - v_WorldPos);
    float attenuation = 1.0 / (
        uAttenuation.x +
        uAttenuation.y * dist +
        uAttenuation.z * dist * dist
    );

    // Softer ambient
    vec3 ambient = 0.5 * uAmbientColor * baseColor;

    // 🔹 Diffuse
    float wrap = 0.3;
    float softDiff = max(0.0, (dot(normal, lightDir) + wrap) / (1.0 + wrap));
    vec3 diffuse = softDiff * baseColor;

    // 🔥 Reduce plastic shine
    float spec = pow(max(dot(reflectDir, viewDir), 0.0), 8.0); // lower shininess
    vec3 specular = spec * vec3(0.2); // reduce intensity

    // Final color
    vec3 finalColor = ambient + attenuation * (diffuse + specular);

    o_FragColor = vec4(finalColor, u_Color.a);
}

#version 300 es
precision mediump float;

uniform vec4 u_Color;

// Lighting uniforms
uniform vec3 uPointLightingLocation;

uniform vec3 uAmbientColor;
uniform vec4 uDiffuseColor;
uniform vec4 uSpecularColor;

uniform vec3 uAttenuation;
uniform float uMaterialShininess;

in vec3 v_Normal;
in vec3 v_WorldPos;

out vec4 o_FragColor;

void main() {

    vec3 normal = normalize(v_Normal);

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

    // 🔹 Ambient
    vec3 ambient = uAmbientColor * u_Color.rgb;

    // 🔹 Diffuse
    float diff = max(dot(normal, lightDir), 0.0);
    vec3 diffuse = diff * uDiffuseColor.rgb * u_Color.rgb;

    // 🔹 Specular
    float spec = pow(max(dot(reflectDir, viewDir), 0.0), uMaterialShininess);
    vec3 specular = spec * uSpecularColor.rgb;

    // Final color
    vec3 finalColor = ambient + attenuation * (diffuse + specular);

    o_FragColor = vec4(finalColor, u_Color.a);
}
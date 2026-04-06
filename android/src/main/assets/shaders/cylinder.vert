#version 300 es
precision mediump float;

layout(location = 0) in vec2 a_Params; // x=angle, y=height

uniform vec3 u_Start;
uniform vec3 u_End;
uniform float u_Radius;

uniform mat4 u_View;
uniform mat4 u_Proj;

out vec3 v_WorldPos;

void main() {
    float angle = a_Params.x;
    float height = a_Params.y;

    vec3 dir = normalize(u_End - u_Start);

    vec3 up = vec3(0.0, 1.0, 0.0);
    if (abs(dot(up, dir)) > 0.99) {
        up = vec3(1.0, 0.0, 0.0);
    }

    vec3 right = normalize(cross(dir, up));
    vec3 forward = normalize(cross(right, dir));

    vec3 circle = cos(angle) * right + sin(angle) * forward;

    vec3 center = mix(u_Start, u_End, height);
    vec3 worldPos = center + circle * u_Radius;

    v_WorldPos = worldPos;

    gl_Position = u_Proj * u_View * vec4(worldPos, 1.0);
}

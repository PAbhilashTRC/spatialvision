#version 300 es
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexPos;

out vec2 vTexPos;

uniform mat4 u_ViewProjection;
uniform vec3 u_CameraPos;
uniform vec3 u_LabelOrigin;
uniform float u_Scale;

void main() {
  vTexPos = aTexPos;
  vec3 forward = -normalize(u_CameraPos - u_LabelOrigin);
  vec3 up = vec3(0.0, 1.0, 0.0);

  if (abs(dot(forward, up)) > 0.95) {
      up = vec3(1.0, 0.0, 0.0);
  }

  vec3 right = normalize(cross(forward, up));
  vec3 correctedUp = cross(right, forward);

  vec3 worldPos =
      u_LabelOrigin +
      right * aPosition.x * u_Scale +
      correctedUp * aPosition.y * u_Scale;

  gl_Position = u_ViewProjection * vec4(worldPos, 1.0);

}

export interface SpatialVisionPlugin {
  echo(options: { value: string }): Promise<{ value: string }>;
  startCamera(options: MeasuringInputs): Promise<{status: string, message: string}>
}

export interface MeasuringInputs {
  title: string
}
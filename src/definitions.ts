export interface SpatialVisionPlugin {
  echo(options: { value: string }): Promise<{ value: string }>;
  startCamera(options: MeasuringInputs): Promise<MeasuringToolResponse>
  poleDigitalTwin(): Promise<{status: string}>
}

export interface MeasuringInputs {
  title: string
}

export interface MeasuringResult {
  label: string;
  distance: number;
  unit: string;
  startPoint: string;
  endPoint: string;
}

export interface MeasuringToolResponse {
  status: number;
  imagePath: string;
  measurements: Array<MeasuringResult>;
}
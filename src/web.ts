import { WebPlugin } from '@capacitor/core';

import type { MeasuringInputs, MeasuringToolResponse, SpatialVisionPlugin } from './definitions';

export class SpatialVisionWeb extends WebPlugin implements SpatialVisionPlugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }

  async startCamera(options: MeasuringInputs): Promise<MeasuringToolResponse> {
    console.log('Starting camera with options', options);
    return { 
      status: 200,
      imagePath: "",
      measurements: []
    };
  }

  async poleDigitalTwin(): Promise<{status: string}> {
    console.log('Generating pole digital twin');
    return { status: "success" };
  }
}

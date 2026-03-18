import { WebPlugin } from '@capacitor/core';

import type { MeasuringInputs, SpatialVisionPlugin } from './definitions';

export class SpatialVisionWeb extends WebPlugin implements SpatialVisionPlugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }

  async startCamera(options: MeasuringInputs): Promise<{ status: string; message: string; }> {
    return { status: "Ok", message: options.title};
  }
}

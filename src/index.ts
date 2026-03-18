import { registerPlugin } from '@capacitor/core';

import type { SpatialVisionPlugin } from './definitions';

const SpatialVision = registerPlugin<SpatialVisionPlugin>('SpatialVision', {
  web: () => import('./web').then((m) => new m.SpatialVisionWeb()),
});

export * from './definitions';
export { SpatialVision };

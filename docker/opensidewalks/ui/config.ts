import { FeatureCollection, MultiPolygon } from "geojson";

export interface RegionProperties {
  id: string;
  key: string;
  name: string;
  bounds: [number, number, number, number];
  lon: number;
  lat: number;
  zoom: number;
}

export type Regions = FeatureCollection<MultiPolygon, RegionProperties>;

/** What the container was started with: config.json, which the page loads before the app. */
export interface AppConfig {
  maptilerKey: string;
  regions: Regions;
}

const config: AppConfig = (window as unknown as { ACCESSMAP_CONFIG: AppConfig })
  .ACCESSMAP_CONFIG;

export default config;

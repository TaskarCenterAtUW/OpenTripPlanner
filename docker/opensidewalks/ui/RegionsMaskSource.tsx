import React from "react";

import { Feature, Position } from "geojson";
import { Source } from "react-map-gl";

import regions from "constants/regions";

// The boundary of a dataset can have several parts and holes: the world is masked except for each
// part, and each hole is masked again.
const RegionsMaskSource = () => {
  const outer_ring = [
    [-180, -90],
    [-180, 90],
    [180, 90],
    [180, -90],
    [-180, -90],
  ];
  const polygons: Position[][][] = regions.features.flatMap(
    (feature) => feature.geometry.coordinates
  );
  const part_rings = polygons.map((polygon) => polygon[0]);
  const holes = polygons.flatMap((polygon) =>
    polygon.slice(1).map((hole) => [hole])
  );

  const invertedPolygon: Feature = {
    type: "Feature",
    geometry: {
      type: "MultiPolygon",
      coordinates: [[outer_ring, ...part_rings], ...holes],
    },
    properties: {},
  };

  return <Source id="regions-mask" type="geojson" data={invertedPolygon} />;
};

export default RegionsMaskSource;

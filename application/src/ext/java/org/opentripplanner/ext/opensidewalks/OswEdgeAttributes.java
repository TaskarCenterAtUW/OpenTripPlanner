package org.opentripplanner.ext.opensidewalks;

import java.io.Serializable;

/**
 * What a pedestrian cost profile needs to know about an OpenSidewalks edge to cost it for a
 * traveller. An edge and the parts it is split into share these.
 *
 * @param layer        the index of the layer of the profile the edge is in, or -1 if it is in none
 * @param incline      the grade in the direction of the street edge, NaN if unknown
 * @param lengthMeters the length of the whole OpenSidewalks edge
 * @param usableByAll  whether every traveller the parameters of the profile allow can use the edge
 */
record OswEdgeAttributes(
  int layer,
  double incline,
  double lengthMeters,
  CurbRamps curbRamps,
  boolean usableByAll
) implements Serializable {
  double speedFactor(PedestrianCostSpec spec) {
    return spec.speedFactor(layer, incline, lengthMeters, curbRamps);
  }
}

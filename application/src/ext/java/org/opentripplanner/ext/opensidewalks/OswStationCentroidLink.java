package org.opentripplanner.ext.opensidewalks;

import org.opentripplanner.street.model.edge.StreetStationCentroidLink;
import org.opentripplanner.street.model.vertex.StationCentroidVertex;
import org.opentripplanner.street.model.vertex.StreetVertex;
import org.opentripplanner.street.search.state.State;

/**
 * The link between the centroid of a station and a place on an OpenSidewalks edge. Like
 * {@link OswStopLink}, it takes the time of walking the distance between the two, since a
 * centroid can be linked to more than one edge here.
 */
public class OswStationCentroidLink extends StreetStationCentroidLink {

  private final int walkSeconds;

  private OswStationCentroidLink(StreetVertex from, StationCentroidVertex to) {
    super(from, to);
    this.walkSeconds = OswLinkModule.walkSeconds(from, to);
  }

  private OswStationCentroidLink(StationCentroidVertex from, StreetVertex to) {
    super(from, to);
    this.walkSeconds = OswLinkModule.walkSeconds(to, from);
  }

  /** Link the centroid and the place on the street to each other, in both directions. */
  static void createAndConnect(StationCentroidVertex centroid, StreetVertex streetVertex) {
    connectToGraph(new OswStationCentroidLink(centroid, streetVertex));
    connectToGraph(new OswStationCentroidLink(streetVertex, centroid));
  }

  /** The time it takes to walk between the centroid and the place on the street. */
  public int walkSeconds() {
    return walkSeconds;
  }

  @Override
  public State[] traverse(State s0) {
    var s1 = s0.edit(this);
    s1.incrementTimeInSeconds(walkSeconds);
    // Like any link to a centroid, a little more, so that a street search does not go through it
    s1.incrementWeight(1 + walkSeconds);
    s1.setBackMode(null);
    return s1.makeStateArray();
  }
}

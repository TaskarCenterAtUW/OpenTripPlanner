package org.opentripplanner.ext.opensidewalks;

import org.opentripplanner.street.geometry.SphericalDistanceLibrary;
import org.opentripplanner.street.model.edge.StreetTransitEntityLink;
import org.opentripplanner.street.model.vertex.StreetVertex;
import org.opentripplanner.street.model.vertex.TransitStopVertex;

/**
 * The link between a transit stop and a place on an OpenSidewalks edge. Unlike the link OTP makes
 * between a stop and a street, it takes the time of walking the distance between the two. A stop
 * can be linked to more than one edge here, for travellers who can not use all of them, and a
 * link that cost nothing would let a traveller start from an edge farther from the stop than
 * they need to, without walking there.
 */
public class OswStopLink extends StreetTransitEntityLink<TransitStopVertex> {

  /** The speed the distance is walked at. A link does not know the speed of the request. */
  private static final double WALK_SPEED = 1.3;

  private final int walkSeconds;

  private OswStopLink(StreetVertex from, TransitStopVertex to) {
    super(from, to, to.getWheelchairAccessibility());
    this.walkSeconds = walkSeconds(from, to);
  }

  private OswStopLink(TransitStopVertex from, StreetVertex to) {
    super(from, to, from.getWheelchairAccessibility());
    this.walkSeconds = walkSeconds(to, from);
  }

  /** Link the stop and the place on the street to each other, in both directions. */
  static void createAndConnect(TransitStopVertex stop, StreetVertex streetVertex) {
    connectToGraph(new OswStopLink(stop, streetVertex));
    connectToGraph(new OswStopLink(streetVertex, stop));
  }

  /** The time it takes to walk between the stop and the place on the street. */
  public int walkSeconds() {
    return walkSeconds;
  }

  @Override
  protected int getStreetToStopTime() {
    var stop = getTransitEntityVertex();
    return (stop.hasPathways() ? 0 : stop.getStreetToStopTime()) + walkSeconds;
  }

  private static int walkSeconds(StreetVertex streetVertex, TransitStopVertex stop) {
    double meters = SphericalDistanceLibrary.distance(
      streetVertex.getCoordinate(),
      stop.getCoordinate()
    );
    return (int) Math.round(meters / WALK_SPEED);
  }
}

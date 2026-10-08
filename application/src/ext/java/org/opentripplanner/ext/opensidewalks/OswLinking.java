package org.opentripplanner.ext.opensidewalks;

import javax.annotation.Nullable;
import org.locationtech.jts.geom.Coordinate;
import org.opentripplanner.routing.linking.LinkingContextRequest;
import org.opentripplanner.street.geometry.SphericalDistanceLibrary;
import org.opentripplanner.street.model.edge.StreetEdge;

/**
 * Which OpenSidewalks edges a place can be linked to.
 * <p>
 * The places of a request, where the trip starts and ends, are linked to the nearest edge the
 * traveller of the request can use, if that is within {@link #MAX_METERS_FROM_PLACE}. OTP links
 * them in {@code LinkingContextFactory}, on the thread of the request, and says here who the
 * traveller is and where the place is while it does.
 * <p>
 * Anything else, such as the stops linked when the graph is built, is there for all travellers.
 * OTP links those to the nearest edge all of them can use, or to the other streets if the graph
 * has any, and {@link OswStopLinkModule} adds the links that the travellers on OpenSidewalks
 * edges need.
 */
public final class OswLinking {

  /**
   * A place farther from the OpenSidewalks edges than this is not linked to them. OTP would link
   * it to an edge up to a kilometer away, and not count the way there as part of the trip.
   */
  public static final double MAX_METERS_FROM_PLACE = 50;

  private static final double MAX_DEGREES_FROM_PLACE = SphericalDistanceLibrary.metersToDegrees(
    MAX_METERS_FROM_PLACE
  );

  private static final ThreadLocal<Linking> CURRENT = new ThreadLocal<>();

  private OswLinking() {}

  /** Link the places of the given request, until the returned scope is closed. */
  public static Scope linkFor(LinkingContextRequest request) {
    PedestrianCostParameters traveller = null;
    for (var extension : request.extensions()) {
      if (extension instanceof PedestrianCostParameters parameters) {
        traveller = parameters;
      }
    }
    return open(new Linking(traveller, null));
  }

  /**
   * Link the place at the given coordinate, for the request that is being linked, until the
   * returned scope is closed.
   */
  public static Scope linkAt(Coordinate place) {
    var request = CURRENT.get();
    return open(new Linking(request == null ? null : request.traveller, place));
  }

  /**
   * Link as the traveller with the given parameter values, wherever the edges are, until the
   * returned scope is closed.
   */
  public static Scope linkAs(@Nullable PedestrianCostParameters traveller) {
    return open(new Linking(traveller, null));
  }

  /** The linking going on in this thread for a request or a traveller, or null if there is none. */
  @Nullable
  static Linking current() {
    return CURRENT.get();
  }

  static boolean isLinkable(OswCosting costing, OswEdgeAttributes attributes, StreetEdge edge) {
    var linking = CURRENT.get();
    if (linking == null) {
      return !costing.besideOtherStreets() && attributes.usableByAll();
    }
    return (
      costing.walksOpenSidewalks(linking.traveller) &&
      attributes.speedFactor(costing.spec(linking.traveller)) > 0 &&
      linking.isNear(edge)
    );
  }

  private static Scope open(Linking linking) {
    var previous = CURRENT.get();
    CURRENT.set(linking);
    return () -> {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    };
  }

  /**
   * @param traveller the parameter values of the request, or {@code null} if it has none
   * @param place     where the place being linked is, or {@code null} if edges anywhere will do
   */
  record Linking(@Nullable PedestrianCostParameters traveller, @Nullable Coordinate place) {
    boolean isNear(StreetEdge edge) {
      if (place == null) {
        return true;
      }
      double xscale = Math.cos(Math.toRadians(place.y));
      double squared = edge.squaredEquirectangularDistanceToPoint(place.x, place.y, xscale);
      return squared <= MAX_DEGREES_FROM_PLACE * MAX_DEGREES_FROM_PLACE;
    }
  }

  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}

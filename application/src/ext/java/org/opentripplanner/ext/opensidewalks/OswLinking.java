package org.opentripplanner.ext.opensidewalks;

import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.locationtech.jts.geom.Coordinate;
import org.opentripplanner.apis.support.InvalidInputException;
import org.opentripplanner.routing.linking.LinkingContextRequest;
import org.opentripplanner.street.geometry.SphericalDistanceLibrary;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.search.TraverseMode;
import org.opentripplanner.street.search.TraverseModeSet;

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
 * has any, and {@link OswLinkModule} adds the links that the travellers on OpenSidewalks
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
    var modes = List.of(
      request.accessMode(),
      request.egressMode(),
      request.directMode(),
      request.transferMode()
    );
    boolean withVehicle = modes
      .stream()
      .anyMatch(m -> m.includesBiking() || m.includesDriving() || m.includesScooter());
    return open(new Linking(traveller, null, withVehicle, false));
  }

  /**
   * Link the place at the given coordinate, for the request that is being linked, until the
   * returned scope is closed.
   */
  public static Scope linkAt(Coordinate place) {
    var request = CURRENT.get();
    return open(
      request == null
        ? new Linking(null, place, false, false)
        : new Linking(request.traveller, place, request.withVehicle, request.forEveryone)
    );
  }

  /**
   * Link as the traveller with the given parameter values, wherever the edges are, until the
   * returned scope is closed.
   */
  public static Scope linkAs(@Nullable PedestrianCostParameters traveller) {
    return open(new Linking(traveller, null, false, false));
  }

  /**
   * Link something that is there for every trip on the OpenSidewalks edges, such as a vehicle for
   * rent or a car park, to the nearest OpenSidewalks edge, until the returned scope is closed. It
   * is linked for walking to an edge someone can walk, and for cycling and driving to a road.
   * OTP links it to the other streets as always, outside this scope. In a graph without other
   * streets OTP links it to the OpenSidewalks edges already, and nothing is linked here.
   */
  public static Scope linkForEveryone() {
    return open(new Linking(PedestrianCostParameters.onOpenSidewalks(Map.of()), null, false, true));
  }

  /** The linking going on in this thread for a request or a traveller, or null if there is none. */
  @Nullable
  static Linking current() {
    return CURRENT.get();
  }

  /**
   * Whether a place can be linked to the edge for the given modes.
   *
   * @throws InvalidInputException if a request on the OpenSidewalks edges cycles or drives, and
   *                               the OpenSidewalks data has no roads
   */
  static boolean isLinkable(
    OswCosting costing,
    OswEdgeAttributes attributes,
    StreetEdge edge,
    TraverseModeSet modes
  ) {
    var permission = edge.getPermission();
    boolean byVehicle =
      (modes.getBicycle() && permission.allows(TraverseMode.BICYCLE)) ||
      (modes.getCar() && permission.allows(TraverseMode.CAR));
    var linking = CURRENT.get();
    if (linking == null) {
      return !costing.besideOtherStreets() && (byVehicle || attributes.usableByAll());
    }
    if (!costing.usesOpenSidewalks(linking.traveller)) {
      return false;
    }
    if (linking.forEveryone) {
      return costing.besideOtherStreets() && (byVehicle || modes.getWalk());
    }
    // OTP links a request that cycles for walking, since a bicycle can be walked
    if ((linking.withVehicle || modes.getBicycle() || modes.getCar()) && !costing.hasRoads()) {
      throw new InvalidInputException(
        "The OpenSidewalks data of this server has no roads, so a trip on it can not include cycling or driving."
      );
    }
    return (
      linking.isNear(edge) &&
      (byVehicle ||
        (modes.getWalk() && attributes.speedFactor(costing.spec(linking.traveller)) > 0))
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
   * @param traveller   the parameter values of the request, or {@code null} if it has none
   * @param place       where the place being linked is, or {@code null} if edges anywhere will do
   * @param withVehicle whether the request cycles or drives in any part of the trip
   * @param forEveryone whether this is for every trip on the OpenSidewalks edges, see
   *                    {@link #linkForEveryone()}
   */
  record Linking(
    @Nullable PedestrianCostParameters traveller,
    @Nullable Coordinate place,
    boolean withVehicle,
    boolean forEveryone
  ) {
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

package org.opentripplanner.ext.opensidewalks;

import java.util.Arrays;
import javax.annotation.Nullable;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.search.TraverseMode;
import org.opentripplanner.street.search.state.State;
import org.opentripplanner.street.search.state.StateEditor;

/**
 * Walks a street edge at the cost a {@link PedestrianCostProfile} gives it for the traveller of
 * the request: the walking speed of the request multiplied with the speed factor of the edge,
 * plus a fixed delay.
 * <p>
 * The cost replaces the walking cost OTP calculates for other street edges. Slopes, stairs, the
 * walk safety and the wheelchair accessibility preferences of the request are not applied on top,
 * since the profile already says what the edge costs for the traveller it describes. Turns
 * between these edges cost nothing.
 */
final class OswWalkCost {

  private OswWalkCost() {}

  /**
   * The time it takes to walk the edge at the given speed in meters per second, or infinity if
   * the traveller can not use the edge.
   *
   * @param delayShare the share of the delay of the whole OpenSidewalks edge that this edge has
   */
  static double durationSeconds(
    StreetEdge edge,
    OswEdgeAttributes attributes,
    float delayShare,
    PedestrianCostSpec spec,
    double walkSpeed
  ) {
    double speedFactor = attributes.speedFactor(spec);
    if (!(speedFactor > 0)) {
      return Double.POSITIVE_INFINITY;
    }
    return (
      edge.getDistanceMeters() / (walkSpeed * speedFactor) +
      spec.delaySeconds(attributes.layer()) * delayShare
    );
  }

  /**
   * The states OTP made traversing the edge, without those that walk it if the traveller of the
   * request can not walk it. That is for where OTP does the walking itself: where a car drops the
   * traveller off or may pick them up, and in geofencing zones. Walking a bicycle is left as it is.
   */
  static State[] withoutWalksTheTravellerCanNotMake(
    State s0,
    State[] states,
    StreetEdge edge,
    OswCosting costing,
    OswEdgeAttributes attributes,
    float delayShare
  ) {
    if (Arrays.stream(states).noneMatch(OswWalkCost::walks)) {
      return states;
    }
    var traveller = s0.getExtensionRequestContext(PedestrianCostParameters.class).orElse(null);
    var spec = costing.spec(traveller);
    double seconds = durationSeconds(
      edge,
      attributes,
      delayShare,
      spec,
      s0.getRequest().walk().speed()
    );
    if (seconds != Double.POSITIVE_INFINITY) {
      return states;
    }
    return Arrays.stream(states)
      .filter(s -> !walks(s))
      .toArray(State[]::new);
  }

  /** Whether the state got where it is on foot, without a vehicle. */
  private static boolean walks(State state) {
    return state.getBackMode() == TraverseMode.WALK && !state.isBackWalkingBike();
  }

  /**
   * @return the states after walking the edge, or {@code null} if the traversal is not a plain
   * walk and should be left to OTP. Walking is the only thing the profile has a cost for, so
   * that includes walking with a vehicle, and walking where a car may pick the walker up or drop
   * them off.
   */
  @Nullable
  static State[] traverse(
    State s0,
    StreetEdge edge,
    OswCosting costing,
    OswEdgeAttributes attributes,
    float delayShare
  ) {
    var traveller = s0.getExtensionRequestContext(PedestrianCostParameters.class).orElse(null);
    if (!costing.usesOpenSidewalks(traveller)) {
      return State.empty();
    }
    var request = s0.getRequest();
    if (s0.currentMode() != TraverseMode.WALK) {
      return null;
    }
    // Where a car can go, OTP decides whether a walker who is to be picked up gets into the car
    if (request.mode().includesPickup() && edge.getPermission().allows(TraverseMode.CAR)) {
      return null;
    }
    // The geofencing zones of vehicles for rent can act on a walker at their boundaries, and in
    // an arrive-by search on a walker who has just left a vehicle
    if (
      request.mode().includesRenting() &&
      (request.arriveBy() ||
        !edge.getFromVertex().listGeofencingBoundaries().isEmpty() ||
        !edge.getToVertex().listGeofencingBoundaries().isEmpty())
    ) {
      return null;
    }
    var backEdge = s0.getBackEdge();
    if (backEdge != null && edge.isReverseOf(backEdge)) {
      return State.empty();
    }
    var spec = costing.spec(traveller);
    double seconds = durationSeconds(edge, attributes, delayShare, spec, request.walk().speed());
    if (seconds == Double.POSITIVE_INFINITY) {
      return State.empty();
    }
    StateEditor editor = edge.createEditor(s0, edge, TraverseMode.WALK, false);
    editor.incrementTimeInMilliseconds((long) Math.ceil(1000.0 * seconds));
    editor.incrementWeight(seconds * request.walk().reluctance());
    editor.incrementTraversalDistanceMeters(edge.getDistanceMeters());
    return editor.makeStateArray();
  }
}

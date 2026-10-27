package org.opentripplanner.ext.opensidewalks;

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
   * @return the states after walking the edge, or {@code null} if the traversal is not a plain
   * walk and should be left to OTP. Walking is the only thing the profile has a cost for, so
   * that includes walking with a vehicle, and the searches that may get into or out of a car
   * along the way.
   */
  @Nullable
  static State[] traverse(
    State s0,
    StreetEdge edge,
    OswCosting costing,
    OswEdgeAttributes attributes,
    float delayShare
  ) {
    var request = s0.getRequest();
    if (
      s0.currentMode() != TraverseMode.WALK ||
      request.mode().includesRenting() ||
      request.mode().includesPickup()
    ) {
      return null;
    }
    var backEdge = s0.getBackEdge();
    if (backEdge != null && edge.isReverseOf(backEdge)) {
      return State.empty();
    }
    var spec = costing.spec(
      s0.getExtensionRequestContext(PedestrianCostParameters.class).orElse(null)
    );
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

package org.opentripplanner.ext.opensidewalks;

import javax.annotation.Nullable;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.search.TraverseMode;
import org.opentripplanner.street.search.state.State;
import org.opentripplanner.street.search.state.StateEditor;

/**
 * Walks a street edge at the cost a {@link PedestrianCostProfile} gave it: the walking speed of
 * the request multiplied with the speed factor of the edge, plus a fixed delay.
 * <p>
 * The cost replaces the walking cost OTP calculates for other street edges. Slopes, stairs, the
 * walk safety and the wheelchair accessibility preferences of the request are not applied on top,
 * since the profile already says what the edge costs for the traveller it describes. Turns
 * between these edges cost nothing.
 */
final class OswWalkCost {

  private OswWalkCost() {}

  /** The time it takes to walk the edge at the given speed in meters per second. */
  static double durationSeconds(
    StreetEdge edge,
    float speedFactor,
    float delaySeconds,
    double walkSpeed
  ) {
    return edge.getDistanceMeters() / (walkSpeed * speedFactor) + delaySeconds;
  }

  /**
   * @return the states after walking the edge, or {@code null} if the traversal is not a plain
   * walk and should be left to OTP. Walking is the only thing the profile has a cost for, so
   * that includes walking with a vehicle, and the searches that may get into or out of a car
   * along the way.
   */
  @Nullable
  static State[] traverse(State s0, StreetEdge edge, float speedFactor, float delaySeconds) {
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
    double seconds = durationSeconds(edge, speedFactor, delaySeconds, request.walk().speed());
    StateEditor editor = edge.createEditor(s0, edge, TraverseMode.WALK, false);
    editor.incrementTimeInMilliseconds((long) Math.ceil(1000.0 * seconds));
    editor.incrementWeight(seconds * request.walk().reluctance());
    editor.incrementTraversalDistanceMeters(edge.getDistanceMeters());
    return editor.makeStateArray();
  }
}

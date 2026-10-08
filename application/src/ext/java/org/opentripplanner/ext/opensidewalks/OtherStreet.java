package org.opentripplanner.ext.opensidewalks;

import java.io.Serializable;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.edge.StreetEdgeCostExtension;
import org.opentripplanner.street.search.TraverseMode;
import org.opentripplanner.street.search.TraverseModeSet;
import org.opentripplanner.street.search.state.State;

/**
 * Set on the streets of a graph that do not come from OpenSidewalks data, when the graph has
 * OpenSidewalks edges too. The two are networks that are not joined to each other, so each is
 * for its own travellers:
 * <ul>
 *   <li>People on foot whom the pedestrian cost profile makes a difference for walk the
 *   OpenSidewalks edges, and are kept off these streets.</li>
 *   <li>Everyone else uses these streets as OTP has them: people on foot without such limits,
 *   cars, and bicycles, ridden or walked.</li>
 * </ul>
 */
final class OtherStreet implements StreetEdgeCostExtension, Serializable {

  private final OswCosting costing;

  OtherStreet(OswCosting costing) {
    this.costing = costing;
  }

  @Override
  public double calculateExtraCost(State state, int edgeLength, TraverseMode traverseMode) {
    return 0;
  }

  @Override
  public boolean canTraverse(State state, TraverseMode traverseMode, boolean walkingBike) {
    if (traverseMode != TraverseMode.WALK || walkingBike) {
      return true;
    }
    var traveller = state.getExtensionRequestContext(PedestrianCostParameters.class).orElse(null);
    return !costing.walksOpenSidewalks(traveller);
  }

  /**
   * What OTP links when the graph is built, such as stops, is linked to these streets as always.
   * The places of a request are, unless its traveller walks the OpenSidewalks edges.
   */
  @Override
  public boolean canLink(StreetEdge edge, TraverseModeSet modes) {
    var permission = edge.getPermission();
    if (
      (modes.getBicycle() && permission.allows(TraverseMode.BICYCLE)) ||
      (modes.getCar() && permission.allows(TraverseMode.CAR))
    ) {
      return true;
    }
    var linking = OswLinking.current();
    return linking == null || !costing.walksOpenSidewalks(linking.traveller());
  }

  /** The parts a street is split into are for the same travellers. */
  @Override
  public StreetEdgeCostExtension partial(double fromDistanceMeters, double toDistanceMeters) {
    return this;
  }
}

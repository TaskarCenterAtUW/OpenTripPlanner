package org.opentripplanner.ext.opensidewalks;

import java.io.Serializable;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.edge.StreetEdgeCostExtension;
import org.opentripplanner.street.search.TraverseMode;
import org.opentripplanner.street.search.TraverseModeSet;
import org.opentripplanner.street.search.state.State;

/**
 * Set on the streets of a graph that do not come from OpenSidewalks data, when the graph has
 * OpenSidewalks edges too. The two are networks that are not joined to each other, and a request
 * travels on one of them, see {@link StreetNetwork}. A request on the OpenSidewalks edges is kept
 * off these streets, whatever its mode. Everyone else uses them as OTP has them.
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
    var traveller = state.getExtensionRequestContext(PedestrianCostParameters.class).orElse(null);
    return !costing.usesOpenSidewalks(traveller);
  }

  /**
   * What OTP links when the graph is built, such as stops, is linked to these streets as always.
   * The places of a request are, unless it travels on the OpenSidewalks edges.
   */
  @Override
  public boolean canLink(StreetEdge edge, TraverseModeSet modes) {
    var linking = OswLinking.current();
    return linking == null || !costing.usesOpenSidewalks(linking.traveller());
  }

  /** The parts a street is split into are for the same travellers. */
  @Override
  public StreetEdgeCostExtension partial(double fromDistanceMeters, double toDistanceMeters) {
    return this;
  }
}

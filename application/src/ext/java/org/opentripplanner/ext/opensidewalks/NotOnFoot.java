package org.opentripplanner.ext.opensidewalks;

import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.edge.StreetEdgeCostExtension;
import org.opentripplanner.street.search.TraverseMode;
import org.opentripplanner.street.search.TraverseModeSet;
import org.opentripplanner.street.search.state.State;

/**
 * Keeps people on foot off a street that does not come from OpenSidewalks data, when the graph
 * has such streets too. People on foot then walk the OpenSidewalks edges only, where they are
 * costed by the pedestrian cost profile, and the other streets are left to those who drive, ride
 * a bicycle, or walk one: a bicycle can still be walked along a street it can not be ridden on.
 * <p>
 * Without this there are two networks to walk on that are not joined to each other, and which of
 * them a trip uses is up to which is nearer to where it starts.
 */
enum NotOnFoot implements StreetEdgeCostExtension {
  INSTANCE;

  @Override
  public double calculateExtraCost(State state, int edgeLength, TraverseMode traverseMode) {
    return 0;
  }

  @Override
  public boolean canTraverse(State state, TraverseMode traverseMode, boolean walkingBike) {
    return traverseMode != TraverseMode.WALK || walkingBike;
  }

  /** A location is linked to the street for driving and for riding, but not for walking. */
  @Override
  public boolean canLink(StreetEdge edge, TraverseModeSet modes) {
    var permission = edge.getPermission();
    return (
      (modes.getBicycle() && permission.allows(TraverseMode.BICYCLE)) ||
      (modes.getCar() && permission.allows(TraverseMode.CAR))
    );
  }

  /** The parts a street is split into are kept from people on foot as well. */
  @Override
  public StreetEdgeCostExtension partial(double fromDistanceMeters, double toDistanceMeters) {
    return this;
  }
}

package org.opentripplanner.street.model.edge;

import javax.annotation.Nullable;
import org.opentripplanner.street.search.TraverseMode;
import org.opentripplanner.street.search.TraverseModeSet;
import org.opentripplanner.street.search.state.State;

/**
 * This interface allows us to set ONE extension on a {@link StreetEdge}. The extension can add
 * extra cost to the edge, and can keep some of what the permissions of the edge allow from
 * using it.
 */
public interface StreetEdgeCostExtension {
  /**
   * This is method is called from the street edge and allows an extension to add extra cost.
   *
   * @return zero(0) - no extra cost is added, or a positive value.
   */
  double calculateExtraCost(State state, int edgeLength, TraverseMode traverseMode);

  /**
   * Whether the edge can be traversed in the given state, in addition to what the permissions of
   * the edge say. It is called from the street edge before it is traversed.
   *
   * @param traverseMode the mode the edge would be traversed in
   * @param walkingBike  whether a bicycle is being walked along the edge
   */
  default boolean canTraverse(State state, TraverseMode traverseMode, boolean walkingBike) {
    return true;
  }

  /**
   * Whether a location can be linked to the edge for the given modes, in addition to what the
   * permissions of the edge say. It is called when the edges a location may be linked to are
   * looked for.
   */
  default boolean canLink(StreetEdge edge, TraverseModeSet modes) {
    return true;
  }

  /**
   * This method is called when the street edge is split, and returns the extension to set on the
   * part of the edge between the two given distances from the start of the edge.
   *
   * @return the extension for the part, or {@code null} if the part should not have one.
   */
  @Nullable
  default StreetEdgeCostExtension partial(double fromDistanceMeters, double toDistanceMeters) {
    return null;
  }
}

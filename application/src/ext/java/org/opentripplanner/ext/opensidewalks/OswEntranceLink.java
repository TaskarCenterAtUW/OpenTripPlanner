package org.opentripplanner.ext.opensidewalks;

import org.opentripplanner.street.model.edge.StreetTransitEntranceLink;
import org.opentripplanner.street.model.vertex.StreetVertex;
import org.opentripplanner.street.model.vertex.TransitEntranceVertex;

/**
 * The link between the entrance of a station and a place on an OpenSidewalks edge. Like
 * {@link OswStopLink}, it takes the time of walking the distance between the two, since an
 * entrance can be linked to more than one edge here.
 */
public class OswEntranceLink extends StreetTransitEntranceLink {

  private final int walkSeconds;

  private OswEntranceLink(StreetVertex from, TransitEntranceVertex to) {
    super(from, to);
    this.walkSeconds = OswLinkModule.walkSeconds(from, to);
  }

  private OswEntranceLink(TransitEntranceVertex from, StreetVertex to) {
    super(from, to);
    this.walkSeconds = OswLinkModule.walkSeconds(to, from);
  }

  /** Link the entrance and the place on the street to each other, in both directions. */
  static void createAndConnect(TransitEntranceVertex entrance, StreetVertex streetVertex) {
    connectToGraph(new OswEntranceLink(entrance, streetVertex));
    connectToGraph(new OswEntranceLink(streetVertex, entrance));
  }

  /** The time it takes to walk between the entrance and the place on the street. */
  public int walkSeconds() {
    return walkSeconds;
  }

  @Override
  protected int getStreetToStopTime() {
    return walkSeconds;
  }
}

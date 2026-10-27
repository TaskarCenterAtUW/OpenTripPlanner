package org.opentripplanner.ext.opensidewalks;

import org.opentripplanner.street.model.edge.StreetVehicleParkingLink;
import org.opentripplanner.street.model.vertex.StreetVertex;
import org.opentripplanner.street.model.vertex.VehicleParkingEntranceVertex;

/**
 * The link between the entrance of a car park and a place on an OpenSidewalks edge. Like
 * {@link OswStopLink}, it takes the time of walking the distance between the two, since an
 * entrance can be linked to more than one edge here.
 */
public class OswParkingLink extends StreetVehicleParkingLink {

  private final int walkSeconds;

  private OswParkingLink(StreetVertex from, VehicleParkingEntranceVertex to) {
    super(from, to);
    this.walkSeconds = OswLinkModule.walkSeconds(from, to);
  }

  private OswParkingLink(VehicleParkingEntranceVertex from, StreetVertex to) {
    super(from, to);
    this.walkSeconds = OswLinkModule.walkSeconds(to, from);
  }

  /** Link the entrance and the place on the street to each other, in both directions. */
  static void createAndConnect(VehicleParkingEntranceVertex entrance, StreetVertex streetVertex) {
    connectToGraph(new OswParkingLink(entrance, streetVertex));
    connectToGraph(new OswParkingLink(streetVertex, entrance));
  }

  /** The time it takes to walk between the entrance and the place on the street. */
  public int walkSeconds() {
    return walkSeconds;
  }

  @Override
  protected int getStreetToEntranceTime() {
    return walkSeconds;
  }
}

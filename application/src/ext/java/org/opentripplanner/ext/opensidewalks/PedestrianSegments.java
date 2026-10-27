package org.opentripplanner.ext.opensidewalks;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import org.locationtech.jts.geom.LineString;
import org.opentripplanner.service.streetdetails.StreetDetailsService;
import org.opentripplanner.service.streetdetails.model.Level;
import org.opentripplanner.street.geometry.GeometryUtils;
import org.opentripplanner.street.model.edge.Edge;
import org.opentripplanner.street.model.edge.ElevatorAlightEdge;
import org.opentripplanner.street.model.edge.ElevatorBoardEdge;
import org.opentripplanner.street.model.edge.ElevatorEdge;
import org.opentripplanner.street.model.edge.PathwayEdge;
import org.opentripplanner.street.model.vertex.TransitEntranceVertex;
import org.opentripplanner.street.model.vertex.Vertex;
import org.opentripplanner.street.search.state.State;

/**
 * Finds the OpenSidewalks edges and the station pathways a street path goes along, for a walking
 * leg of an itinerary.
 */
public class PedestrianSegments {

  private static final String ELEVATOR = "ELEVATOR";

  private PedestrianSegments() {}

  /**
   * The OpenSidewalks edges and station pathways the path of the given states goes along, in
   * order, one segment per edge. The parts an OpenSidewalks edge is split into, where a stop is
   * linked to it or a trip starts or ends partway along it, are one segment, and so is a ride in an
   * elevator. Other edges of the path, such as those linking a stop to the street, are left out.
   *
   * @param streetDetails where the levels of elevators are, or {@code null} to leave them out
   */
  public static List<PedestrianSegment> of(
    List<State> states,
    @Nullable StreetDetailsService streetDetails
  ) {
    var segments = new ArrayList<PedestrianSegment>();
    OswEdgeAttributes current = null;
    var geometries = new ArrayList<LineString>();
    double distance = 0;
    var elevatorRide = new ArrayList<Edge>();

    // The first state is where the path starts, the edges are the back edges of the others
    for (int i = 1; i < states.size(); i++) {
      Edge edge = states.get(i).getBackEdge();
      OswEdgeAttributes attributes = attributes(edge);
      // The parts of one direction of an edge share the same attributes
      if (current != null && attributes != current) {
        segments.add(segment(current, geometries, distance));
        geometries.clear();
        distance = 0;
      }
      current = attributes;
      if (attributes != null) {
        geometries.add(edge.getGeometry());
        distance += edge.getDistanceMeters();
      }

      // Boarding, riding between floors and leaving an elevator is one segment
      if (edge instanceof ElevatorEdge) {
        elevatorRide.add(edge);
        continue;
      }
      if (!elevatorRide.isEmpty()) {
        segments.add(elevator(elevatorRide, streetDetails));
        elevatorRide.clear();
      }

      if (edge instanceof PathwayEdge pathway && isWalked(pathway)) {
        segments.add(pathway(pathway));
      }
    }
    if (current != null) {
      segments.add(segment(current, geometries, distance));
    }
    if (!elevatorRide.isEmpty()) {
      segments.add(elevator(elevatorRide, streetDetails));
    }
    return List.copyOf(segments);
  }

  @Nullable
  private static OswEdgeAttributes attributes(@Nullable Edge edge) {
    if (edge instanceof OswStreetEdge osw) {
      return osw.attributes();
    }
    if (edge instanceof TemporaryOswStreetEdge osw) {
      return osw.attributes();
    }
    return null;
  }

  private static PedestrianSegment segment(
    OswEdgeAttributes attributes,
    List<LineString> geometries,
    double distance
  ) {
    var tags = attributes.tags();
    return new PedestrianSegment(
      GeometryUtils.concatenateLineStrings(geometries),
      distance,
      tags.name(),
      tags.highway(),
      tags.footway(),
      tags.description(),
      tags.surface(),
      Double.isNaN(attributes.incline()) ? null : attributes.incline(),
      switch (attributes.curbRamps()) {
        case YES -> true;
        case NO -> false;
        case UNKNOWN -> null;
      },
      tags.elevator(),
      tags.indoor(),
      null,
      null,
      null,
      null
    );
  }

  /**
   * The pathways between a boarding area and its platform, which OTP adds, have neither a kind
   * nor a length, and are not something the traveller notices.
   */
  private static boolean isWalked(PathwayEdge pathway) {
    return pathway.mode() != PathwayEdge.Mode.UNKNOWN || pathway.getDistanceMeters() > 0;
  }

  private static PedestrianSegment pathway(PathwayEdge pathway) {
    var from = pathway.fromLevel();
    var to = pathway.toLevel();
    return new PedestrianSegment(
      pathway.getGeometry(),
      pathway.getDistanceMeters(),
      pathway.signpostedAs().map(Object::toString).orElse(null),
      null,
      null,
      entranceName(pathway.getFromVertex(), pathway.getToVertex()),
      null,
      null,
      null,
      false,
      true,
      pathway.mode().name(),
      from.map(PathwayEdge.Level::name).orElse(null),
      to.map(PathwayEdge.Level::name).orElse(null),
      from.isPresent() && to.isPresent() ? to.get().index() - from.get().index() : null
    );
  }

  private static PedestrianSegment elevator(
    List<Edge> ride,
    @Nullable StreetDetailsService streetDetails
  ) {
    Vertex from = ride.getFirst().getFromVertex();
    Vertex to = ride.getLast().getToVertex();
    Level fromLevel = null;
    Level toLevel = null;
    if (streetDetails != null) {
      for (Edge edge : ride) {
        if (edge instanceof ElevatorBoardEdge && fromLevel == null) {
          fromLevel = streetDetails.findHorizontalEdgeLevelInfo(edge).orElse(null);
        }
        if (edge instanceof ElevatorAlightEdge) {
          toLevel = streetDetails.findHorizontalEdgeLevelInfo(edge).orElse(null);
        }
      }
    }
    return new PedestrianSegment(
      GeometryUtils.makeLineString(from.getCoordinate(), to.getCoordinate()),
      0,
      null,
      null,
      null,
      entranceName(from, to),
      null,
      null,
      null,
      true,
      true,
      ELEVATOR,
      fromLevel == null ? null : fromLevel.name(),
      toLevel == null ? null : toLevel.name(),
      fromLevel != null && toLevel != null ? toLevel.level() - fromLevel.level() : null
    );
  }

  /** The name of the station entrance at either end, if there is one. */
  @Nullable
  private static String entranceName(Vertex from, Vertex to) {
    for (Vertex vertex : List.of(from, to)) {
      if (vertex instanceof TransitEntranceVertex entrance && entrance.getName() != null) {
        return entrance.getName().toString();
      }
    }
    return null;
  }
}

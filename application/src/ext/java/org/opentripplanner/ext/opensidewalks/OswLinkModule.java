package org.opentripplanner.ext.opensidewalks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import org.locationtech.jts.geom.Envelope;
import org.opentripplanner.graph_builder.model.GraphBuilderModule;
import org.opentripplanner.street.geometry.SphericalDistanceLibrary;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.linking.LinkingDirection;
import org.opentripplanner.street.linking.VertexLinker;
import org.opentripplanner.street.model.edge.Edge;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.edge.StreetStationCentroidLink;
import org.opentripplanner.street.model.edge.StreetTransitEntranceLink;
import org.opentripplanner.street.model.edge.StreetTransitStopLink;
import org.opentripplanner.street.model.edge.StreetVehicleParkingLink;
import org.opentripplanner.street.model.vertex.StationCentroidVertex;
import org.opentripplanner.street.model.vertex.StreetVertex;
import org.opentripplanner.street.model.vertex.TransitEntranceVertex;
import org.opentripplanner.street.model.vertex.TransitStopVertex;
import org.opentripplanner.street.model.vertex.VehicleParkingEntranceVertex;
import org.opentripplanner.street.model.vertex.Vertex;
import org.opentripplanner.street.search.TraverseMode;
import org.opentripplanner.street.search.TraverseModeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Links transit stops, the entrances and centroids of stations and the entrances of vehicle
 * parking to the OpenSidewalks edges that the travellers who can not use all edges need. It runs after OTP has linked them as it always does,
 * which for OpenSidewalks edges is to the nearest edge every traveller can use, and if there is
 * other street data, only to that.
 * <p>
 * A stop, a station or a vehicle parking is there for all travellers, and is linked before any of them is known.
 * Linking it only to the nearest edge would leave it out of reach of the travellers who can not
 * use that edge. Linking it only to the nearest edge all can use would send the others farther
 * than they have to go, and leave it unlinked if there is no such edge nearby.
 * <p>
 * So it is linked to every edge that is the nearest one some traveller can use. Going outwards,
 * an edge is one of those if one of the least able travellers who can use it can use none of the
 * edges before it. If they all can, so can everyone else who can use the edge, and nobody needs
 * it. A traveller then finds the nearest edge they can use among the links, since edges that can
 * not be used are not walked, and the links take the time of walking to them, see
 * {@link OswStopLink}, {@link OswEntranceLink}, {@link OswStationCentroidLink} and
 * {@link OswParkingLink}. Car parks are linked to the roads of the OpenSidewalks data for cars as
 * well.
 * <p>
 * Vehicles for rent, and vehicle parking that a real-time updater adds, are linked by the
 * updaters, see {@link OswLinking#linkForEveryone()}.
 */
public class OswLinkModule implements GraphBuilderModule {

  private static final Logger LOG = LoggerFactory.getLogger(OswLinkModule.class);
  private static final TraverseModeSet WALK = new TraverseModeSet(TraverseMode.WALK);
  private static final TraverseModeSet CAR = new TraverseModeSet(TraverseMode.CAR);

  /** As far as OTP looks for an edge to link a stop to. Entrances are linked within it too. */
  private static final double RADIUS_METERS = 100;

  /** Edges that are this near to being equally far away are the two directions of a street. */
  private static final double SAME_DISTANCE_DEGREES = SphericalDistanceLibrary.metersToDegrees(
    0.05
  );

  /** The speed the links are walked at, in meters per second. */
  private static final double WALK_SPEED = 1.3;

  private final Graph graph;
  private final VertexLinker vertexLinker;

  public OswLinkModule(Graph graph, VertexLinker vertexLinker) {
    this.graph = graph;
    this.vertexLinker = vertexLinker;
  }

  @Override
  public void buildGraph() {
    var costing = OswCosting.of(graph);
    if (costing.isEmpty()) {
      return;
    }
    var travellers = new OswTravellers(costing.get().profile());
    int stops = 0;
    int links = 0;
    for (TransitStopVertex stop : graph.getVerticesOfType(TransitStopVertex.class)) {
      // Stops with pathways have entrances, which are what is linked to the streets, below
      if (stop.hasPathways()) {
        continue;
      }
      int before = stop.getOutgoing().size();
      link(stop, travellers, OswStopLink::createAndConnect);
      chargeLinks(stop, StreetTransitStopLink.class, OswStopLink::createAndConnect);
      int added = stop.getOutgoing().size() - before;
      if (added > 0) {
        stops++;
        links += added;
      }
    }
    LOG.info(
      "Linked {} stops to {} more places on OpenSidewalks edges, for travellers who can not use all edges.",
      stops,
      links
    );

    int stationEntrances = 0;
    links = 0;
    for (var entrance : graph.getVerticesOfType(TransitEntranceVertex.class)) {
      int before = entrance.getOutgoing().size();
      link(entrance, travellers, OswEntranceLink::createAndConnect);
      chargeLinks(entrance, StreetTransitEntranceLink.class, OswEntranceLink::createAndConnect);
      int added = entrance.getOutgoing().size() - before;
      if (added > 0) {
        stationEntrances++;
        links += added;
      }
    }
    LOG.info(
      "Linked {} station entrances to {} more places on OpenSidewalks edges, for travellers who can not use all edges.",
      stationEntrances,
      links
    );

    int centroids = 0;
    links = 0;
    for (var centroid : graph.getVerticesOfType(StationCentroidVertex.class)) {
      int before = centroid.getOutgoing().size();
      link(centroid, travellers, OswStationCentroidLink::createAndConnect);
      chargeLinks(
        centroid,
        StreetStationCentroidLink.class,
        OswStationCentroidLink::createAndConnect
      );
      int added = centroid.getOutgoing().size() - before;
      if (added > 0) {
        centroids++;
        links += added;
      }
    }
    if (centroids > 0) {
      LOG.info(
        "Linked {} station centroids to {} more places on OpenSidewalks edges, for travellers who can not use all edges.",
        centroids,
        links
      );
    }

    int entrances = 0;
    links = 0;
    for (var entrance : graph.getVerticesOfType(VehicleParkingEntranceVertex.class)) {
      if (!entrance.isWalkAccessible()) {
        continue;
      }
      int before = entrance.getOutgoing().size();
      link(entrance, travellers, OswParkingLink::createAndConnect);
      chargeLinks(entrance, StreetVehicleParkingLink.class, OswParkingLink::createAndConnect);
      int added = entrance.getOutgoing().size() - before;
      if (added > 0) {
        entrances++;
        links += added;
      }
    }
    LOG.info(
      "Linked {} vehicle parking entrances to {} places on OpenSidewalks edges, for travellers who can not use all edges.",
      entrances,
      links
    );

    int forCars = 0;
    for (var entrance : graph.getVerticesOfType(VehicleParkingEntranceVertex.class)) {
      if (entrance.isCarAccessible() && linkForCars(entrance)) {
        forCars++;
      }
    }
    if (forCars > 0) {
      LOG.info("Linked {} car park entrances to the roads of the OpenSidewalks data.", forCars);
    }
  }

  /**
   * Link a car park to the nearest road of the OpenSidewalks data, so that a trip on the
   * OpenSidewalks edges can drive to it. Nothing is linked if the data has no roads.
   *
   * @return whether it was linked
   */
  private boolean linkForCars(VehicleParkingEntranceVertex entrance) {
    int before = entrance.getOutgoing().size();
    try (var ignore = OswLinking.linkForEveryone()) {
      vertexLinker.linkVertexPermanently(
        entrance,
        CAR,
        LinkingDirection.BIDIRECTIONAL,
        (vertex, streetVertex) ->
          List.of(
            StreetVehicleParkingLink.createStreetVehicleParkingLink(entrance, streetVertex),
            StreetVehicleParkingLink.createStreetVehicleParkingLink(streetVertex, entrance)
          )
      );
    }
    return entrance.getOutgoing().size() > before;
  }

  /**
   * @param connect makes the links between the vertex and a place on an edge, in both directions
   */
  private <T extends Vertex> void link(
    T vertex,
    OswTravellers travellers,
    BiConsumer<T, StreetVertex> connect
  ) {
    List<OswEdgeAttributes> nearer = new ArrayList<>();
    for (List<StreetEdge> street : nearbyStreets(vertex)) {
      boolean forAll = false;
      List<OswEdgeAttributes> here = new ArrayList<>();
      for (StreetEdge edge : street) {
        if (!(edge instanceof OswStreetEdge osw)) {
          // A street from other data. Travellers on OpenSidewalks edges do not walk it, and OTP
          // has linked the vertex to it for the others.
          continue;
        }
        forAll |= osw.isUsableByAll();
        here.add(osw.attributes());
        if (osw.isUsableByAll()) {
          link(vertex, osw, travellers.leastAbleUsers(osw.attributes()).getFirst(), connect);
        } else {
          neededBy(osw.attributes(), nearer, travellers).ifPresent(it ->
            link(vertex, osw, it, connect)
          );
        }
      }
      if (forAll) {
        // Nobody needs an edge that is farther away than this
        return;
      }
      nearer.addAll(here);
    }
  }

  /**
   * One of the least able travellers who can use the edge and can use none of the nearer ones,
   * if there is one.
   */
  private static Optional<PedestrianCostParameters> neededBy(
    OswEdgeAttributes edge,
    List<OswEdgeAttributes> nearer,
    OswTravellers travellers
  ) {
    return travellers
      .leastAbleUsers(edge)
      .stream()
      .filter(traveller -> nearer.stream().noneMatch(it -> travellers.canUse(traveller, it)))
      .findFirst();
  }

  /**
   * @param traveller a traveller who can use the edge. OTP only links to edges that the
   *                  traveller it links for can use.
   */
  private <T extends Vertex> void link(
    T vertex,
    StreetEdge edge,
    PedestrianCostParameters traveller,
    BiConsumer<T, StreetVertex> connect
  ) {
    Set<StreetVertex> streetVertices;
    var onOpenSidewalks = PedestrianCostParameters.onOpenSidewalks(traveller.values());
    try (var ignore = OswLinking.linkAs(onOpenSidewalks)) {
      streetVertices = split(vertex, edge);
    }
    for (StreetVertex streetVertex : streetVertices) {
      boolean linked = vertex
        .getOutgoing()
        .stream()
        .anyMatch(e -> e.getToVertex() == streetVertex);
      if (!linked) {
        connect.accept(vertex, streetVertex);
      }
    }
  }

  private Set<StreetVertex> split(Vertex vertex, StreetEdge edge) {
    return vertexLinker.linkToSpecificStreetEdgesPermanently(
      vertex,
      WALK,
      LinkingDirection.BIDIRECTIONAL,
      Set.of(edge)
    );
  }

  /**
   * The streets that can be walked within reach of the vertex, nearest first. A street is the
   * edges that are equally far away: its two directions.
   */
  private List<List<StreetEdge>> nearbyStreets(Vertex vertex) {
    double radius = SphericalDistanceLibrary.metersToDegrees(RADIUS_METERS);
    double xscale = Math.cos(Math.toRadians(vertex.getLat()));
    var envelope = new Envelope(vertex.getCoordinate());
    envelope.expandBy(radius / xscale, radius);

    record Candidate(StreetEdge edge, double distance) {}
    List<Candidate> candidates = new ArrayList<>();
    for (Edge e : graph.findEdges(envelope)) {
      if (e instanceof StreetEdge edge && canBeWalked(edge)) {
        double distance = Math.sqrt(
          edge.squaredEquirectangularDistanceToPoint(vertex.getLon(), vertex.getLat(), xscale)
        );
        if (distance < radius) {
          candidates.add(new Candidate(edge, distance));
        }
      }
    }
    candidates.sort(Comparator.comparingDouble(Candidate::distance));

    List<List<StreetEdge>> streets = new ArrayList<>();
    double last = Double.NEGATIVE_INFINITY;
    for (Candidate c : candidates) {
      if (c.distance() - last > SAME_DISTANCE_DEGREES) {
        streets.add(new ArrayList<>());
      }
      streets.getLast().add(c.edge());
      last = c.distance();
    }
    return streets;
  }

  /**
   * The time it takes to walk between the two vertices in a straight line, at a fixed speed. A
   * link does not know the speed of the request.
   */
  static int walkSeconds(Vertex a, Vertex b) {
    double meters = SphericalDistanceLibrary.distance(a.getCoordinate(), b.getCoordinate());
    return (int) Math.round(meters / WALK_SPEED);
  }

  private static boolean canBeWalked(StreetEdge edge) {
    return edge.getPermission().allows(TraverseMode.WALK);
  }

  /**
   * Replace the links OTP made between the vertex and OpenSidewalks edges, which cost nothing,
   * with ones that take the time of walking them, like the links made here.
   *
   * @param otpLink the class of the links OTP made
   */
  private static <T extends Vertex> void chargeLinks(
    T vertex,
    Class<? extends Edge> otpLink,
    BiConsumer<T, StreetVertex> connect
  ) {
    for (Edge link : List.copyOf(vertex.getOutgoing())) {
      if (
        link.getClass() == otpLink &&
        link.getToVertex() instanceof StreetVertex streetVertex &&
        OswStreetEdge.isOnOpenSidewalksEdge(streetVertex)
      ) {
        for (Edge back : List.copyOf(vertex.getIncoming())) {
          if (back.getClass() == otpLink && back.getFromVertex() == streetVertex) {
            back.remove();
          }
        }
        link.remove();
        connect.accept(vertex, streetVertex);
      }
    }
  }
}

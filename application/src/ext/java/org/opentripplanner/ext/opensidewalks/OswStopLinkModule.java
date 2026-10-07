package org.opentripplanner.ext.opensidewalks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.locationtech.jts.geom.Envelope;
import org.opentripplanner.graph_builder.model.GraphBuilderModule;
import org.opentripplanner.street.geometry.SphericalDistanceLibrary;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.linking.LinkingDirection;
import org.opentripplanner.street.linking.VertexLinker;
import org.opentripplanner.street.model.edge.Edge;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.edge.StreetTransitStopLink;
import org.opentripplanner.street.model.vertex.StreetVertex;
import org.opentripplanner.street.model.vertex.TransitStopVertex;
import org.opentripplanner.street.search.TraverseMode;
import org.opentripplanner.street.search.TraverseModeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Links transit stops to the OpenSidewalks edges that the travellers who can not use all edges
 * need. It runs after OTP has linked the stops as it always does, which for OpenSidewalks edges
 * is to the nearest edge every traveller can use.
 * <p>
 * A stop is there for all travellers, and is linked before any of them is known. Linking it only
 * to the nearest edge would leave it out of reach of the travellers who can not use that edge.
 * Linking it only to the nearest edge all can use would send the others farther than they have
 * to go, and leave the stop unlinked if there is no such edge nearby.
 * <p>
 * So a stop is linked to every edge that is the nearest one some traveller can use. Going from
 * the stop outwards, an edge is one of those if one of the least able travellers who can use it
 * can use none of the edges before it. If they all can, so can everyone else who can use the
 * edge, and nobody needs it. A traveller then finds the nearest edge they can use among the links
 * of the stop, since edges that can not be used are not walked, and the links take the time of
 * walking to them, see {@link OswStopLink}.
 */
public class OswStopLinkModule implements GraphBuilderModule {

  private static final Logger LOG = LoggerFactory.getLogger(OswStopLinkModule.class);
  private static final TraverseModeSet WALK = new TraverseModeSet(TraverseMode.WALK);

  /** As far as OTP looks for an edge to link a stop to. */
  private static final double RADIUS_METERS = 100;

  /** Edges that are this near to being equally far away are the two directions of a street. */
  private static final double SAME_DISTANCE_DEGREES = SphericalDistanceLibrary.metersToDegrees(
    0.05
  );

  private final Graph graph;
  private final VertexLinker vertexLinker;

  public OswStopLinkModule(Graph graph, VertexLinker vertexLinker) {
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
      // Stops with pathways have entrances, which are what is linked to the streets
      if (stop.hasPathways()) {
        continue;
      }
      int before = stop.getOutgoing().size();
      link(stop, travellers);
      chargeLinks(stop);
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
  }

  private void link(TransitStopVertex stop, OswTravellers travellers) {
    List<OswEdgeAttributes> nearer = new ArrayList<>();
    for (List<StreetEdge> street : nearbyStreets(stop)) {
      boolean forAll = false;
      List<OswEdgeAttributes> here = new ArrayList<>();
      for (StreetEdge edge : street) {
        if (!(edge instanceof OswStreetEdge osw)) {
          // A street from another source, which OTP lets everyone walk
          forAll = true;
          continue;
        }
        forAll |= osw.isUsableByAll();
        here.add(osw.attributes());
        if (osw.isUsableByAll()) {
          link(stop, osw, null);
        } else {
          neededBy(osw.attributes(), nearer, travellers).ifPresent(it -> link(stop, osw, it));
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
   * @param traveller a traveller who can use the edge, or {@code null} if all can. OTP only links
   *                  to edges that the traveller it links for can use.
   */
  private void link(TransitStopVertex stop, StreetEdge edge, PedestrianCostParameters traveller) {
    Set<StreetVertex> streetVertices;
    if (traveller == null) {
      streetVertices = split(stop, edge);
    } else {
      try (var ignore = OswLinking.linkAs(traveller)) {
        streetVertices = split(stop, edge);
      }
    }
    for (StreetVertex streetVertex : streetVertices) {
      boolean linked = stop
        .getOutgoing()
        .stream()
        .anyMatch(e -> e.getToVertex() == streetVertex);
      if (!linked) {
        OswStopLink.createAndConnect(stop, streetVertex);
      }
    }
  }

  private Set<StreetVertex> split(TransitStopVertex stop, StreetEdge edge) {
    return vertexLinker.linkToSpecificStreetEdgesPermanently(
      stop,
      WALK,
      LinkingDirection.BIDIRECTIONAL,
      Set.of(edge)
    );
  }

  /**
   * The streets that can be walked within reach of the stop, nearest first. A street is the
   * edges that are equally far away: its two directions.
   */
  private List<List<StreetEdge>> nearbyStreets(TransitStopVertex stop) {
    double radius = SphericalDistanceLibrary.metersToDegrees(RADIUS_METERS);
    double xscale = Math.cos(Math.toRadians(stop.getLat()));
    var envelope = new Envelope(stop.getCoordinate());
    envelope.expandBy(radius / xscale, radius);

    record Candidate(StreetEdge edge, double distance) {}
    List<Candidate> candidates = new ArrayList<>();
    for (Edge e : graph.findEdges(envelope)) {
      if (e instanceof StreetEdge edge && canBeWalked(edge)) {
        double distance = Math.sqrt(
          edge.squaredEquirectangularDistanceToPoint(stop.getLon(), stop.getLat(), xscale)
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

  /** Whether anyone can walk the edge. For an OpenSidewalks edge, some traveller can. */
  private static boolean canBeWalked(StreetEdge edge) {
    return edge instanceof OswStreetEdge
      ? edge.getPermission().allows(TraverseMode.WALK)
      : edge.canTraverse(WALK);
  }

  /**
   * Replace the links OTP made between the stop and OpenSidewalks edges, which cost nothing, with
   * ones that take the time of walking them, like the links made here.
   */
  private static void chargeLinks(TransitStopVertex stop) {
    for (Edge link : List.copyOf(stop.getOutgoing())) {
      if (
        link instanceof StreetTransitStopLink &&
        link.getToVertex() instanceof StreetVertex streetVertex &&
        isOnOpenSidewalksEdge(streetVertex)
      ) {
        for (Edge back : List.copyOf(stop.getIncoming())) {
          if (back instanceof StreetTransitStopLink && back.getFromVertex() == streetVertex) {
            back.remove();
          }
        }
        link.remove();
        OswStopLink.createAndConnect(stop, streetVertex);
      }
    }
  }

  private static boolean isOnOpenSidewalksEdge(StreetVertex vertex) {
    return (
      vertex.getOutgoing().stream().anyMatch(OswStreetEdge.class::isInstance) ||
      vertex.getIncoming().stream().anyMatch(OswStreetEdge.class::isInstance)
    );
  }
}

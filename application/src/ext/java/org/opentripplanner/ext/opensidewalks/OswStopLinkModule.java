package org.opentripplanner.ext.opensidewalks;

import java.util.ArrayList;
import java.util.List;
import org.opentripplanner.graph_builder.model.GraphBuilderModule;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.linking.LinkingDirection;
import org.opentripplanner.street.linking.VertexLinker;
import org.opentripplanner.street.model.edge.Edge;
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
 * to go, and leave the stop unlinked if there is no such edge nearby. So the stop is also linked
 * to the nearest edge that each of the travellers at the ends of the parameter ranges can use,
 * and the traveller of the build configuration. A traveller then finds a link that can be used
 * among those of the stop, since edges that can not be used are not walked.
 */
public class OswStopLinkModule implements GraphBuilderModule {

  private static final Logger LOG = LoggerFactory.getLogger(OswStopLinkModule.class);
  private static final TraverseModeSet WALK = new TraverseModeSet(TraverseMode.WALK);

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
    List<PedestrianCostParameters> travellers = new ArrayList<>();
    travellers.add(PedestrianCostParameters.DEFAULT);
    for (var values : costing.get().profile().extremeValues()) {
      travellers.add(PedestrianCostParameters.of(values));
    }

    int links = 0;
    int stops = 0;
    for (TransitStopVertex stop : graph.getVerticesOfType(TransitStopVertex.class)) {
      // Stops with pathways have entrances, which are what is linked to the streets
      if (stop.hasPathways()) {
        continue;
      }
      int before = stop.getOutgoing().size();
      for (var traveller : travellers) {
        try (var ignore = OswLinking.linkAs(traveller)) {
          vertexLinker.linkVertexPermanently(
            stop,
            WALK,
            LinkingDirection.BIDIRECTIONAL,
            (vertex, streetVertex) -> link((TransitStopVertex) vertex, streetVertex)
          );
        }
      }
      int added = stop.getOutgoing().size() - before;
      if (added > 0) {
        links += added;
        stops++;
      }
    }
    LOG.info(
      "Linked {} stops to {} more places on OpenSidewalks edges, for travellers who can not use all edges.",
      stops,
      links
    );
  }

  private static List<Edge> link(TransitStopVertex stop, StreetVertex streetVertex) {
    boolean linked = stop
      .getOutgoing()
      .stream()
      .anyMatch(e -> e.getToVertex() == streetVertex);
    if (linked) {
      return List.of();
    }
    return List.of(
      StreetTransitStopLink.createStreetTransitStopLink(stop, streetVertex),
      StreetTransitStopLink.createStreetTransitStopLink(streetVertex, stop)
    );
  }
}

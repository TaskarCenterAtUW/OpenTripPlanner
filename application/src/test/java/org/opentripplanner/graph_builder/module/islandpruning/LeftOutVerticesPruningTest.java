package org.opentripplanner.graph_builder.module.islandpruning;

import static com.google.common.truth.Truth.assertThat;
import static org.opentripplanner.street.model.StreetModelForTest.intersectionVertex;
import static org.opentripplanner.street.model.StreetModelForTest.streetEdge;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.opentripplanner.graph_builder.issue.api.DataImportIssueStore;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.vertex.IntersectionVertex;
import org.opentripplanner.street.model.vertex.Vertex;
import org.opentripplanner.transit.service.SiteRepository;
import org.opentripplanner.transit.service.TransitRepository;

/**
 * A street network of ten vertices, and two islands of two vertices each, far from it. One of
 * the islands is left out of the pruning.
 */
class LeftOutVerticesPruningTest {

  private static final IslandPruningParameters PARAMETERS = IslandPruningParameters.of()
    .withPruningThresholdIslandWithoutStops(5)
    .withAdaptivePruningFactor(1)
    .build();

  private final Graph graph = new Graph();
  private final List<StreetEdge> leftOutEdges = new ArrayList<>();
  private final List<StreetEdge> otherIslandEdges = new ArrayList<>();
  private Set<Vertex> leftOut;

  @Test
  void anIslandThatIsLeftOutIsNotPruned() {
    build();

    prune(leftOut::contains);

    assertThat(graph.listEdges()).containsAtLeastElementsIn(leftOutEdges);
    assertThat(graph.listEdges()).containsNoneIn(otherIslandEdges);
  }

  @Test
  void withNothingLeftOutBothIslandsArePruned() {
    build();

    prune(v -> false);

    assertThat(graph.listEdges()).containsNoneIn(leftOutEdges);
    assertThat(graph.listEdges()).containsNoneIn(otherIslandEdges);
  }

  private void build() {
    IntersectionVertex previous = vertex(60, 25);
    for (int i = 1; i < 10; i++) {
      var next = vertex(60, 25 + i * 0.001);
      connect(previous, next, new ArrayList<>());
      previous = next;
    }
    var a = vertex(60.1, 25);
    var b = vertex(60.1, 25.001);
    connect(a, b, leftOutEdges);
    connect(vertex(60.2, 25), vertex(60.2, 25.001), otherIslandEdges);
    leftOut = Set.of(a, b);
    graph.index();
  }

  private IntersectionVertex vertex(double lat, double lon) {
    var vertex = intersectionVertex(lat, lon);
    graph.addVertex(vertex);
    return vertex;
  }

  private static void connect(IntersectionVertex a, IntersectionVertex b, List<StreetEdge> edges) {
    edges.add(streetEdge(a, b));
    edges.add(streetEdge(b, a));
  }

  private void prune(Predicate<Vertex> isLeftOut) {
    var transitRepository = new TransitRepository(new SiteRepository());
    transitRepository.index();
    new IslandPruningModule(
      graph,
      transitRepository,
      DataImportIssueStore.NOOP,
      null,
      PARAMETERS,
      isLeftOut
    ).buildGraph();
  }
}

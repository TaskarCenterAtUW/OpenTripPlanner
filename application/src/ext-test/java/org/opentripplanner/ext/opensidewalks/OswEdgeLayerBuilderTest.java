package org.opentripplanner.ext.opensidewalks;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.vertex.Vertex;
import org.opentripplanner.street.model.vertex.VertexLabel;
import org.opentripplanner.test.support.ResourceLoader;

/** Builds the map layer of the small synthetic dataset of {@link OswModuleTest}. */
class OswEdgeLayerBuilderTest {

  private static final ResourceLoader RESOURCES = ResourceLoader.of(OswEdgeLayerBuilderTest.class);
  private static final OswCosting PROFILE = OswCosting.fromFile(
    RESOURCES.file("unweaver-dynamic.json"),
    Map.of()
  );
  private static final Envelope EVERYWHERE = new Envelope(-180, 180, -90, 90);

  @Test
  void oneFeaturePerEdgeInTheDirectionItIsMapped() {
    var graph = build();
    graph.index();

    var features = new OswEdgeLayerBuilder(graph, "transportation", 0).findGeometries(EVERYWHERE);

    // Each of the 16 edges that has a direction in the graph, once. Steps, the plain footway and
    // the road are in no layer of the profile, so in the graph neither way.
    assertThat(features).hasSize(13);
    var sidewalk = feature(features, graph, "2", "3");
    assertEquals(0.1, sidewalk.incline(), 1e-9);
    assertEquals("sidewalk", sidewalk.attributes().tags().footway());
    var crossing = feature(features, graph, "21", "22");
    assertEquals("marked", crossing.attributes().tags().crossing());
    assertEquals("unmarked", feature(features, graph, "1", "11").attributes().tags().crossing());
  }

  @Test
  void anEdgeNobodyCanUseTheWayItIsMappedIsShownTheOtherWayRound() {
    var graph = build();
    graph.removeEdge(edge(graph, "2", "3"));
    graph.index();

    var features = new OswEdgeLayerBuilder(graph, "transportation", 0).findGeometries(EVERYWHERE);

    assertThat(features).hasSize(13);
    // The incline and geometry are as the edge is mapped, from 2 to 3
    var sidewalk = feature(features, graph, "2", "3");
    assertEquals(0.1, sidewalk.incline(), 1e-9);
  }

  @Test
  void anEdgeWithoutAnInclineHasNone() {
    var graph = build();
    graph.index();

    var features = new OswEdgeLayerBuilder(graph, "transportation", 0).findGeometries(EVERYWHERE);

    assertTrue(Double.isNaN(feature(features, graph, "21", "22").incline()));
  }

  private static Graph build() {
    var graph = new Graph();
    new OswModule(graph, List.of(RESOURCES.file("tiny")), PROFILE).buildGraph();
    return graph;
  }

  /** The feature that runs from the first node to the second. */
  private static OswEdgeLayerBuilder.MapEdge feature(
    List<Geometry> features,
    Graph graph,
    String fromNodeId,
    String toNodeId
  ) {
    var from = vertex(graph, fromNodeId).getCoordinate();
    var to = vertex(graph, toNodeId).getCoordinate();
    return features
      .stream()
      .filter(
        g ->
          g.getCoordinates()[0].equals2D(from) &&
          g.getCoordinates()[g.getNumPoints() - 1].equals2D(to)
      )
      .map(g -> (OswEdgeLayerBuilder.MapEdge) g.getUserData())
      .findFirst()
      .orElseThrow(() -> new AssertionError("No feature from " + fromNodeId + " to " + toNodeId));
  }

  private static Vertex vertex(Graph graph, String nodeId) {
    return graph.getVertex(VertexLabel.string("osw:" + nodeId));
  }

  private static StreetEdge edge(Graph graph, String fromNodeId, String toNodeId) {
    var to = vertex(graph, toNodeId);
    return vertex(graph, fromNodeId)
      .getOutgoing()
      .stream()
      .filter(e -> e.getToVertex() == to)
      .map(StreetEdge.class::cast)
      .findFirst()
      .orElseThrow();
  }
}

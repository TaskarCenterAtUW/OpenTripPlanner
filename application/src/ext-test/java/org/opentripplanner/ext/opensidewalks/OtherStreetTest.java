package org.opentripplanner.ext.opensidewalks;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentripplanner.street.model.StreetModelForTest.intersectionVertex;

import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentripplanner.core.model.i18n.I18NString;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.model.StreetMode;
import org.opentripplanner.street.model.StreetModelForTest;
import org.opentripplanner.street.model.StreetTraversalPermission;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.vertex.SplitterVertex;
import org.opentripplanner.street.model.vertex.StreetVertex;
import org.opentripplanner.street.model.vertex.Vertex;
import org.opentripplanner.street.model.vertex.VertexLabel;
import org.opentripplanner.street.search.StreetSearchBuilder;
import org.opentripplanner.street.search.TraverseMode;
import org.opentripplanner.street.search.TraverseModeSet;
import org.opentripplanner.street.search.request.StreetSearchRequest;
import org.opentripplanner.test.support.ResourceLoader;

/**
 * A graph with streets from other data, a road from A to B that all may use and a footpath from
 * B to C, and the OpenSidewalks edges of the small synthetic dataset. The two are not joined.
 */
class OtherStreetTest {

  private static final ResourceLoader RESOURCES = ResourceLoader.of(OtherStreetTest.class);
  private static final TraverseModeSet WALK = new TraverseModeSet(TraverseMode.WALK);
  private static final TraverseModeSet CAR = new TraverseModeSet(TraverseMode.CAR);

  /** Someone the profile makes a difference for: they avoid curbs and have limits on grades. */
  private static final PedestrianCostParameters WITH_LIMITS = PedestrianCostParameters.of(
    Map.of("uphill", "0.085", "avoid_curbs", "true")
  );

  /** Values that are there, but ask for nothing: every limit is at its loosest. */
  private static final PedestrianCostParameters MOST_ABLE = PedestrianCostParameters.of(
    Map.of("uphill", "1.0", "downhill", "1.0", "avoid_curbs", "false")
  );

  private final Graph graph = new Graph();
  private final StreetVertex a = intersectionVertex("A", 47.66, -122.3);
  private final StreetVertex b = intersectionVertex("B", 47.661, -122.3);
  private final StreetVertex c = intersectionVertex("C", 47.662, -122.3);
  private final StreetEdge road = StreetModelForTest.streetEdge(
    a,
    b,
    StreetTraversalPermission.ALL
  );
  private final StreetEdge footpath = StreetModelForTest.streetEdge(
    b,
    c,
    StreetTraversalPermission.PEDESTRIAN
  );

  @BeforeEach
  void loadOpenSidewalks() {
    graph.addVertex(a);
    graph.addVertex(b);
    graph.addVertex(c);
    new OswModule(
      graph,
      List.of(RESOURCES.file("tiny")),
      OswCosting.fromFile(RESOURCES.file("unweaver-dynamic.json"), Map.of())
    ).buildGraph();
  }

  @Test
  void aTravellerWithoutLimitsWalksTheOtherStreets() {
    assertTrue(reaches(a, StreetMode.WALK, null, c));
    // And not the OpenSidewalks edges
    assertFalse(reaches(node("2"), StreetMode.WALK, null, node("1")));
  }

  @Test
  void valuesThatAskForNothingAreNoLimits() {
    assertTrue(reaches(a, StreetMode.WALK, MOST_ABLE, c));
    assertFalse(reaches(node("2"), StreetMode.WALK, MOST_ABLE, node("1")));
  }

  @Test
  void aTravellerWithLimitsWalksTheOpenSidewalksEdges() {
    assertTrue(reaches(node("2"), StreetMode.WALK, WITH_LIMITS, node("1")));
    // And not the other streets
    assertFalse(reaches(a, StreetMode.WALK, WITH_LIMITS, b));
  }

  @Test
  void bicyclesAndCarsUseTheOtherStreetsWhoeverTheTravellerIs() {
    // Along the road, and then walking the bicycle along the footpath
    assertTrue(reaches(a, StreetMode.BIKE, null, c));
    assertTrue(reaches(a, StreetMode.BIKE, WITH_LIMITS, c));
    assertTrue(reaches(a, StreetMode.CAR, WITH_LIMITS, b));
    // A bicycle is not walked on the OpenSidewalks edges
    assertFalse(reaches(node("2"), StreetMode.BIKE, null, node("1")));
  }

  @Test
  void thePlacesOfARequestAreLinkedToTheStreetsOfItsTraveller() {
    var sidewalk = edge("2", "1");

    try (var ignore = OswLinking.linkAs(null)) {
      assertTrue(road.canTraverse(WALK));
      assertFalse(sidewalk.canTraverse(WALK));
    }
    try (var ignore = OswLinking.linkAs(MOST_ABLE)) {
      assertTrue(road.canTraverse(WALK));
      assertFalse(sidewalk.canTraverse(WALK));
    }
    try (var ignore = OswLinking.linkAs(WITH_LIMITS)) {
      assertFalse(road.canTraverse(WALK));
      assertFalse(footpath.canTraverse(WALK));
      assertTrue(sidewalk.canTraverse(WALK));
      // Driving there is another matter
      assertTrue(road.canTraverse(CAR));
    }
  }

  @Test
  void whatOtpLinksWhenTheGraphIsBuiltIsLinkedToTheOtherStreets() {
    assertTrue(road.canTraverse(WALK));
    assertFalse(edge("12", "13").canTraverse(WALK));
  }

  @Test
  void thePartsOfASplitStreetAreForTheSameTravellers() {
    var middle = new SplitterVertex("middle", -122.3, 47.6605, I18NString.of("middle"));

    var parts = road.splitDestructively(middle);

    try (var ignore = OswLinking.linkAs(WITH_LIMITS)) {
      assertFalse(parts.head().canTraverse(WALK));
      assertFalse(parts.tail().canTraverse(WALK));
      assertTrue(parts.head().canTraverse(CAR));
    }
  }

  private StreetVertex node(String id) {
    return (StreetVertex) graph.getVertex(VertexLabel.string("osw:" + id));
  }

  private StreetEdge edge(String fromNodeId, String toNodeId) {
    var to = node(toNodeId);
    return node(fromNodeId)
      .getOutgoing()
      .stream()
      .filter(e -> e.getToVertex() == to)
      .map(StreetEdge.class::cast)
      .findFirst()
      .orElseThrow();
  }

  private boolean reaches(
    Vertex from,
    StreetMode mode,
    @Nullable PedestrianCostParameters traveller,
    Vertex vertex
  ) {
    var request = StreetSearchRequest.of().withMode(mode);
    if (traveller != null) {
      request.withExtensionRequestContexts(List.of(traveller));
    }
    return StreetSearchBuilder.of()
      .withRequest(request.build())
      .withFrom(from)
      .getShortestPathTree()
      .getAllStates()
      .stream()
      .anyMatch(s -> s.getVertex() == vertex);
  }
}

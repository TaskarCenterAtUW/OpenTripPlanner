package org.opentripplanner.ext.opensidewalks;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.opentripplanner.apis.support.InvalidInputException;
import org.opentripplanner.astar.model.ShortestPathTree;
import org.opentripplanner.core.model.i18n.I18NString;
import org.opentripplanner.ext.opensidewalks.config.OpenSidewalksConfig;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.linking.LinkingDirection;
import org.opentripplanner.street.model.StreetTraversalPermission;
import org.opentripplanner.street.model.edge.Edge;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.vertex.SplitterVertex;
import org.opentripplanner.street.model.vertex.Vertex;
import org.opentripplanner.street.model.vertex.VertexLabel;
import org.opentripplanner.street.search.StreetSearchBuilder;
import org.opentripplanner.street.search.TraverseMode;
import org.opentripplanner.street.search.TraverseModeSet;
import org.opentripplanner.street.search.request.StreetSearchRequest;
import org.opentripplanner.street.search.state.State;
import org.opentripplanner.test.support.ResourceLoader;
import org.opentripplanner.utils.collection.ListUtils;

/**
 * Builds a graph from a small synthetic OpenSidewalks dataset: two sidewalks, 1-2-3 in the south
 * and 11-12-13-14 in the north, joined by three crossings, with an elevator, steps, a plain
 * footway and a road leading off them.
 */
class OswModuleTest {

  private static final ResourceLoader RESOURCES = ResourceLoader.of(OswModuleTest.class);
  private static final File DATASET = RESOURCES.file("tiny");
  private static final double SPEED = 1.3;
  private static final double RELUCTANCE = 3;
  private static final File PROFILE_FILE = RESOURCES.file("unweaver-dynamic.json");
  private static final OswCosting PROFILE = OswCosting.fromFile(PROFILE_FILE, Map.of());

  @Test
  void withoutAProfileEveryEdgeIsAnOrdinaryStreet() {
    var graph = build(null);

    var edges = ListUtils.ofIterable(graph.findEdges(StreetEdge.class));
    // 16 edges in the dataset, one street edge in each direction
    assertThat(edges).hasSize(32);
    for (StreetEdge edge : edges) {
      assertFalse(edge instanceof OswStreetEdge);
      // Only walked, except the road, which bicycles and cars can use too
      boolean road = "Example St".equals(edge.getName().toString());
      assertEquals(
        road ? StreetTraversalPermission.ALL : StreetTraversalPermission.PEDESTRIAN,
        edge.getPermission()
      );
    }
    assertTrue(graph.hasStreets);

    var e104 = edge(graph, "12", "13");
    // The length is the one the dataset gives
    assertEquals(100.02, e104.getDistanceMeters(), 0.001);
    assertEquals(4, e104.getGeometry().getNumPoints());
    assertEquals("sidewalk", e104.getName().toString());
    assertTrue(e104.nameIsDerived());
    assertEquals("Example St", edge(graph, "3", "61").getName().toString());

    assertThat(reachableFrom(graph, "2", null)).containsAtLeast("3", "31", "51", "n-west", "61");
  }

  @Test
  void edgesNoTravellerCanUseAreLeftOut() {
    var graph = build(PROFILE);

    // Steps and plain footways are in no layer of this profile
    assertNull(edge(graph, "12", "51"));
    assertNull(edge(graph, "n-west", "11"));
    // Nor are roads, but they are kept for bicycles and cars
    var road = osw(graph, "3", "61");
    assertEquals(StreetTraversalPermission.ALL, road.getPermission());
    assertFalse(road.isUsableByAll());
    assertTrue(PROFILE.hasRoads());
    // Any other edge is there, also the ones only some travellers can use: a sidewalk that is
    // 10% uphill, and a crossing with a raised kerb
    assertFalse(osw(graph, "2", "3").isUsableByAll());
    assertFalse(osw(graph, "31", "32").isUsableByAll());
    // The elevator has no incline and needs no curb ramps
    assertTrue(osw(graph, "13", "41").isUsableByAll());
  }

  @Test
  void theTravellerOfTheRequestDecidesWhichEdgesCanBeWalked() {
    var graph = build(PROFILE);

    // Uphill is limited to 8.5% by default: 2 -> 3 is 10% up, but 3 -> 2 is fine
    var defaults = reachableFrom(graph, "2", null);
    assertThat(defaults).containsAtLeast("1", "12", "32", "41", "14");
    // The crossing 31 - 32 has a raised kerb, and avoiding curbs is the default
    assertThat(defaults).containsNoneOf("3", "31", "51", "n-west", "61");
    assertThat(reachableFrom(graph, "3", null)).contains("2");

    assertThat(reachableFrom(graph, "2", traveller("uphill", "0.12"))).contains("3");
    // Without avoiding curbs the crossing can be used, which is also another way to node 3
    assertThat(reachableFrom(graph, "2", traveller("avoid_curbs", "false"))).containsAtLeast(
      "31",
      "3"
    );
  }

  @Test
  void theBuildConfigurationGivesTheDefaultTraveller() {
    var graph = build(OswCosting.fromFile(PROFILE_FILE, Map.of("avoid_curbs", "false")));

    assertThat(reachableFrom(graph, "2", null)).contains("31");
    assertThat(reachableFrom(graph, "2", traveller("avoid_curbs", "true"))).doesNotContain("31");
  }

  @Test
  void edgesAreWalkedAtTheCostOfTheProfile() {
    var graph = build(PROFILE);

    // 2 -> 1 is the sidewalk 1 -> 2, which is 3% uphill, walked downhill. The expected time is
    // the one R5 and Unweaver have for it.
    var sidewalk = osw(graph, "2", "1");
    assertEquals(111.85261519391284, sidewalk.walkDurationSeconds(null, SPEED), 1e-9);
    assertThat(osw(graph, "1", "2").walkDurationSeconds(null, SPEED)).isGreaterThan(111.9);
    // A traveller who can manage steeper grades is slowed less by this one
    assertThat(
      osw(graph, "1", "2").walkDurationSeconds(traveller("uphill", "0.5"), SPEED)
    ).isLessThan(osw(graph, "1", "2").walkDurationSeconds(null, SPEED));
    assertEquals(Double.POSITIVE_INFINITY, osw(graph, "2", "3").walkDurationSeconds(null, SPEED));

    // The crossing has no incline, so it takes its length at plain speed and the 30 s delay
    assertEquals(15.98 / SPEED + 30, osw(graph, "21", "22").walkDurationSeconds(null, SPEED), 1e-9);

    State atS1 = search(graph, "2", null).getState(vertex(graph, "1"));
    assertEquals(111_853, atS1.getElapsedTimeMilliseconds());
    assertEquals(111.8526 * RELUCTANCE, atS1.getWeight(), 0.01);
    assertEquals(99.89, atS1.getTraversalDistanceMeters(), 0.001);
  }

  @Test
  void invalidParameterValuesOfARequestAreRejected() {
    var graph = build(PROFILE);
    assertThrows(InvalidInputException.class, () ->
      reachableFrom(graph, "2", traveller("uphill", "7"))
    );
    assertThrows(InvalidInputException.class, () ->
      reachableFrom(graph, "2", traveller("uphil", "0.1"))
    );
  }

  @Test
  void splittingAnEdgeSharesItsDelayBetweenTheParts() {
    var graph = build(PROFILE);
    var crossing = osw(graph, "21", "22");
    double whole = crossing.walkDurationSeconds(null, SPEED);
    var middle = along(crossing, "split", 0.25);

    var split = crossing.splitDestructively(middle);

    var head = (OswStreetEdge) split.head();
    var tail = (OswStreetEdge) split.tail();
    assertEquals(0.25, head.delayShare(), 0.001);
    assertEquals(0.75, tail.delayShare(), 0.001);
    assertEquals(
      whole,
      head.walkDurationSeconds(null, SPEED) + tail.walkDurationSeconds(null, SPEED),
      0.01
    );
    assertThat(middle.getIncoming()).containsExactly(head);
    assertThat(middle.getOutgoing()).containsExactly(tail);
  }

  @Test
  void theEndsOfATripAreCostedLikeTheEdgeTheyAreOn() {
    var graph = build(PROFILE);
    var crossing = osw(graph, "21", "22");
    var quarter = along(crossing, "quarter", 0.25);
    var half = along(crossing, "half", 0.5);

    var split = crossing.splitNonDestructively(quarter, LinkingDirection.BIDIRECTIONAL);

    var head = (TemporaryOswStreetEdge) split.head();
    var tail = (TemporaryOswStreetEdge) split.tail();
    assertEquals(crossing, head.getParentEdge());
    assertEquals(0.25, head.delayShare(), 0.001);
    assertEquals(0.75, tail.delayShare(), 0.001);
    assertThat(quarter.getIncoming()).containsExactly(head);
    assertThat(quarter.getOutgoing()).containsExactly(tail);
    // The crossing itself is still there
    assertEquals(crossing, edge(graph, "21", "22"));

    State atQuarter = StreetSearchBuilder.of()
      .withRequest(request(null))
      .withFrom(crossing.getFromVertex())
      .getShortestPathTree()
      .getState(quarter);
    assertEquals(15.98 / 4 / SPEED + 7.5, atQuarter.getElapsedTimeMilliseconds() / 1000.0, 0.01);

    // From one place on the crossing to another, a quarter of it apart
    var between = (TemporaryOswStreetEdge) crossing.createPartialEdge(quarter, half).orElseThrow();
    assertEquals(15.98 / 4, between.getDistanceMeters(), 0.01);
    assertEquals(0.25, between.delayShare(), 0.001);
  }

  @Test
  void placesAreLinkedToEdgesTheirTravellerCanUse() {
    var graph = build(PROFILE);
    var steepSidewalk = osw(graph, "2", "3");
    var modes = new TraverseModeSet(TraverseMode.WALK);

    // What is linked when the graph is built is there for all travellers
    assertFalse(steepSidewalk.canTraverse(modes));
    assertTrue(osw(graph, "12", "13").canTraverse(modes));

    try (var ignore = OswLinking.linkAs(null)) {
      assertFalse(steepSidewalk.canTraverse(modes));
    }
    try (var ignore = OswLinking.linkAs(traveller("uphill", "0.12"))) {
      assertTrue(steepSidewalk.canTraverse(modes));
    }
    assertFalse(steepSidewalk.canTraverse(modes));
  }

  @Test
  void datasetsAreLoadedSideBySideWithoutJoining() {
    var graph = new Graph();

    // The same dataset twice: the same node ids and the same positions
    new OswModule(graph, List.of(DATASET, DATASET), null).buildGraph();

    assertThat(ListUtils.ofIterable(graph.findEdges(StreetEdge.class))).hasSize(64);
    var first = graph.getVertex(VertexLabel.string("osw:0:2"));
    var second = graph.getVertex(VertexLabel.string("osw:1:2"));
    assertThat(first).isNotNull();
    assertThat(second).isNotNull();
    var request = StreetSearchRequest.of().build();
    var reached = StreetSearchBuilder.of()
      .withRequest(request)
      .withFrom(first)
      .getShortestPathTree()
      .getAllStates()
      .stream()
      .map(State::getVertex)
      .toList();
    assertThat(reached).contains(graph.getVertex(VertexLabel.string("osw:0:3")));
    assertThat(reached).doesNotContain(second);
  }

  @Test
  void aConfigurationWithoutSourcesBuildsNothing() {
    var config = new OpenSidewalksConfig(List.of(), null, Map.of());
    assertNull(OswModule.create(config, new Graph(), DATASET));
  }

  @Test
  void pathsAreResolvedAgainstTheBaseDirectory() {
    var config = new OpenSidewalksConfig(
      List.of("tiny"),
      "unweaver-dynamic.json",
      Map.of("avoid_curbs", "false")
    );
    var graph = new Graph();
    var module = OswModule.create(config, graph, DATASET.getParentFile());
    module.checkInputs();
    module.buildGraph();

    assertThat(reachableFrom(graph, "2", null)).contains("31");
  }

  private static Graph build(@Nullable OswCosting costing) {
    var graph = new Graph();
    new OswModule(graph, List.of(DATASET), costing).buildGraph();
    return graph;
  }

  private static PedestrianCostParameters traveller(String parameter, String value) {
    return PedestrianCostParameters.of(Map.of(parameter, value));
  }

  /** A walk as the traveller with the given parameter values, or the default one if null. */
  private static StreetSearchRequest request(@Nullable PedestrianCostParameters traveller) {
    var builder = StreetSearchRequest.of().withWalk(w ->
      w.withSpeed(SPEED).withReluctance(RELUCTANCE)
    );
    if (traveller != null) {
      builder.withExtensionRequestContexts(List.of(traveller));
    }
    return builder.build();
  }

  private static OswStreetEdge osw(Graph graph, String fromNodeId, String toNodeId) {
    return (OswStreetEdge) edge(graph, fromNodeId, toNodeId);
  }

  /** A vertex the given share of the way along the edge, which is a straight line. */
  private static SplitterVertex along(StreetEdge edge, String label, double share) {
    var from = edge.getFromVertex();
    var to = edge.getToVertex();
    return new SplitterVertex(
      label,
      from.getLon() + (to.getLon() - from.getLon()) * share,
      from.getLat() + (to.getLat() - from.getLat()) * share,
      I18NString.of(label)
    );
  }

  private static Vertex vertex(Graph graph, String nodeId) {
    return graph.getVertex(VertexLabel.string("osw:" + nodeId));
  }

  @Nullable
  private static StreetEdge edge(Graph graph, String fromNodeId, String toNodeId) {
    var to = vertex(graph, toNodeId);
    return vertex(graph, fromNodeId)
      .getOutgoing()
      .stream()
      .filter(e -> e.getToVertex() == to)
      .map(StreetEdge.class::cast)
      .findFirst()
      .orElse(null);
  }

  private static ShortestPathTree<State, Edge, Vertex> search(
    Graph graph,
    String fromNodeId,
    @Nullable PedestrianCostParameters traveller
  ) {
    return StreetSearchBuilder.of()
      .withRequest(request(traveller))
      .withFrom(vertex(graph, fromNodeId))
      .getShortestPathTree();
  }

  /** The ids of the nodes that can be walked to from the given node. */
  private static List<String> reachableFrom(
    Graph graph,
    String fromNodeId,
    @Nullable PedestrianCostParameters traveller
  ) {
    return search(graph, fromNodeId, traveller)
      .getAllStates()
      .stream()
      .map(s -> s.getVertex().getLabel().toString().substring("osw:".length()))
      .distinct()
      .toList();
  }
}

package org.opentripplanner.ext.opensidewalks;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;
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
  private static final StreetSearchRequest WALK = StreetSearchRequest.of()
    .withWalk(w -> w.withSpeed(SPEED).withReluctance(RELUCTANCE))
    .build();

  @Test
  void withoutAProfileEveryEdgeIsAnOrdinaryWalkableStreet() {
    var graph = build(null);

    var edges = ListUtils.ofIterable(graph.findEdges(StreetEdge.class));
    // 16 edges in the dataset, one street edge in each direction
    assertThat(edges).hasSize(32);
    for (StreetEdge edge : edges) {
      assertFalse(edge instanceof OswStreetEdge);
      assertEquals(StreetTraversalPermission.PEDESTRIAN, edge.getPermission());
    }
    assertTrue(graph.hasStreets);

    var e104 = edge(graph, "12", "13");
    // The length is the one the dataset gives
    assertEquals(100.02, e104.getDistanceMeters(), 0.001);
    assertEquals(4, e104.getGeometry().getNumPoints());
    assertEquals("sidewalk", e104.getName().toString());
    assertTrue(e104.nameIsDerived());
    assertEquals("Example St", edge(graph, "3", "61").getName().toString());

    assertThat(reachableFrom(graph, "2")).containsAtLeast("3", "31", "51", "n-west", "61");
  }

  @Test
  void theProfileDecidesWhichEdgesCanBeWalked() throws IOException {
    var graph = build(PedestrianCostProfileTest.loadProfile().resolve(null));

    // Uphill is limited to 8.5%: 2 -> 3 is 10% up and impassable, but 3 -> 2 is fine
    assertNull(edge(graph, "2", "3"));
    assertNotNull(edge(graph, "3", "2"));
    // Crossing 21 - 22 has lowered kerbs at both ends, 31 - 32 a raised one, 1 - 11 no kerbs
    assertNotNull(edge(graph, "21", "22"));
    assertNull(edge(graph, "31", "32"));
    assertNotNull(edge(graph, "1", "11"));
    // The short steep segment is usable, as is the elevator
    assertNotNull(edge(graph, "13", "14"));
    assertNotNull(edge(graph, "13", "41"));
    // Steps, plain footways and roads are in no layer of this profile
    assertNull(edge(graph, "12", "51"));
    assertNull(edge(graph, "n-west", "11"));
    assertNull(edge(graph, "3", "61"));

    var fromS2 = reachableFrom(graph, "2");
    assertThat(fromS2).containsAtLeast("1", "12", "32", "41", "14");
    assertThat(fromS2).containsNoneOf("3", "31", "51", "n-west", "61");
    assertThat(reachableFrom(graph, "3")).contains("2");
  }

  @Test
  void parametersChangeWhichEdgesCanBeWalked() throws IOException {
    var spec = PedestrianCostProfileTest.loadProfile().resolve(Map.of("avoid_curbs", false));
    var graph = build(spec);

    assertNotNull(edge(graph, "31", "32"));
    assertThat(reachableFrom(graph, "2")).contains("31");
  }

  @Test
  void edgesAreWalkedAtTheCostOfTheProfile() throws IOException {
    var graph = build(PedestrianCostProfileTest.loadProfile().resolve(null));

    // 2 -> 1 is the sidewalk 1 -> 2, which is 3% uphill, walked downhill. The expected time is
    // the one R5 and Unweaver have for it.
    var sidewalk = (OswStreetEdge) edge(graph, "2", "1");
    assertEquals(111.85261519391284, sidewalk.walkDurationSeconds(SPEED), 0.001);
    assertThat(edge(graph, "1", "2")).isInstanceOf(OswStreetEdge.class);
    assertThat(((OswStreetEdge) edge(graph, "1", "2")).walkDurationSeconds(SPEED)).isGreaterThan(
      sidewalk.walkDurationSeconds(SPEED)
    );

    // The crossing has no incline, so it takes its length at plain speed and the 30 s delay
    var crossing = (OswStreetEdge) edge(graph, "21", "22");
    assertEquals(15.98 / SPEED + 30, crossing.walkDurationSeconds(SPEED), 0.001);

    State atS1 = search(graph, "2").getState(vertex(graph, "1"));
    assertEquals(111_853, atS1.getElapsedTimeMilliseconds(), 1);
    assertEquals(111.8526 * RELUCTANCE, atS1.getWeight(), 0.01);
    assertEquals(99.89, atS1.getTraversalDistanceMeters(), 0.001);
  }

  @Test
  void splittingAnEdgeSharesItsDelayBetweenTheParts() throws IOException {
    var graph = build(PedestrianCostProfileTest.loadProfile().resolve(null));
    var crossing = (OswStreetEdge) edge(graph, "21", "22");
    double whole = crossing.walkDurationSeconds(SPEED);
    var from = crossing.getFromVertex();
    var to = crossing.getToVertex();
    var middle = new SplitterVertex(
      "split",
      from.getLon(),
      from.getLat() + (to.getLat() - from.getLat()) / 4,
      I18NString.of("split")
    );

    var split = crossing.splitDestructively(middle);

    var head = (OswStreetEdge) split.head();
    var tail = (OswStreetEdge) split.tail();
    assertEquals(7.5, head.delaySeconds(), 0.01);
    assertEquals(22.5, tail.delaySeconds(), 0.01);
    assertEquals(whole, head.walkDurationSeconds(SPEED) + tail.walkDurationSeconds(SPEED), 0.01);
    assertThat(middle.getIncoming()).containsExactly(head);
    assertThat(middle.getOutgoing()).containsExactly(tail);
  }

  @Test
  void theEndsOfATripAreCostedLikeTheEdgeTheyAreOn() throws IOException {
    var graph = build(PedestrianCostProfileTest.loadProfile().resolve(null));
    var crossing = (OswStreetEdge) edge(graph, "21", "22");
    var from = crossing.getFromVertex();
    var to = crossing.getToVertex();
    var quarter = new SplitterVertex(
      "quarter",
      from.getLon(),
      from.getLat() + (to.getLat() - from.getLat()) / 4,
      I18NString.of("quarter")
    );
    var half = new SplitterVertex(
      "half",
      from.getLon(),
      from.getLat() + (to.getLat() - from.getLat()) / 2,
      I18NString.of("half")
    );

    var split = crossing.splitNonDestructively(quarter, LinkingDirection.BIDIRECTIONAL);

    var head = (TemporaryOswStreetEdge) split.head();
    var tail = (TemporaryOswStreetEdge) split.tail();
    assertEquals(crossing, head.getParentEdge());
    assertEquals(7.5, head.delaySeconds(), 0.01);
    assertEquals(22.5, tail.delaySeconds(), 0.01);
    assertThat(quarter.getIncoming()).containsExactly(head);
    assertThat(quarter.getOutgoing()).containsExactly(tail);
    // The crossing itself is still there
    assertEquals(crossing, edge(graph, "21", "22"));

    State atQuarter = StreetSearchBuilder.of()
      .withRequest(WALK)
      .withFrom(from)
      .getShortestPathTree()
      .getState(quarter);
    assertEquals(15.98 / 4 / SPEED + 7.5, atQuarter.getElapsedTimeMilliseconds() / 1000.0, 0.01);

    // From one place on the crossing to another, a quarter of it apart
    var between = (TemporaryOswStreetEdge) crossing.createPartialEdge(quarter, half).orElseThrow();
    assertEquals(15.98 / 4, between.getDistanceMeters(), 0.01);
    assertEquals(7.5, between.delaySeconds(), 0.01);
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

    assertNotNull(edge(graph, "31", "32"));
  }

  private static Graph build(@Nullable PedestrianCostSpec spec) {
    var graph = new Graph();
    new OswModule(graph, List.of(DATASET), spec).buildGraph();
    return graph;
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

  private static ShortestPathTree<State, Edge, Vertex> search(Graph graph, String fromNodeId) {
    return StreetSearchBuilder.of()
      .withRequest(WALK)
      .withFrom(vertex(graph, fromNodeId))
      .getShortestPathTree();
  }

  /** The ids of the nodes that can be walked to from the given node. */
  private static List<String> reachableFrom(Graph graph, String fromNodeId) {
    return search(graph, fromNodeId)
      .getAllStates()
      .stream()
      .map(s -> s.getVertex().getLabel().toString().substring("osw:".length()))
      .distinct()
      .toList();
  }
}

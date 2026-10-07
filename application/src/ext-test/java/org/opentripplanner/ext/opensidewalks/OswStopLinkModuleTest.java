package org.opentripplanner.ext.opensidewalks;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.routing.linking.VertexLinkerTestFactory;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.linking.LinkingDirection;
import org.opentripplanner.street.linking.VertexLinker;
import org.opentripplanner.street.model.edge.StreetTransitStopLink;
import org.opentripplanner.street.model.vertex.TransitStopVertex;
import org.opentripplanner.street.model.vertex.Vertex;
import org.opentripplanner.street.model.vertex.VertexLabel;
import org.opentripplanner.street.search.StreetSearchBuilder;
import org.opentripplanner.street.search.TraverseMode;
import org.opentripplanner.street.search.TraverseModeSet;
import org.opentripplanner.street.search.request.StreetSearchRequest;
import org.opentripplanner.street.search.state.State;
import org.opentripplanner.test.support.ResourceLoader;

/**
 * A stop stands halfway along the south sidewalk of the small synthetic dataset, between the
 * nodes 2 and 3. That part of the sidewalk is 10% steep, which not every traveller can manage.
 * The sidewalk across the street, 20 m to the north, is level.
 */
class OswStopLinkModuleTest {

  private static final ResourceLoader RESOURCES = ResourceLoader.of(OswStopLinkModuleTest.class);
  private static final File DATASET = RESOURCES.file("tiny");
  private static final File PROFILE = RESOURCES.file("unweaver-dynamic.json");
  private static final double SPEED = 1.3;

  private final Graph graph = new Graph();
  private final TransitStopVertex stop = TransitStopVertex.of()
    .withId(new FeedScopedId("F", "stop"))
    .withCoordinate(47.65, -122.298)
    .build();
  private VertexLinker linker;

  private void build() {
    new OswModule(graph, List.of(DATASET), OswCosting.fromFile(PROFILE, Map.of())).buildGraph();
    graph.addVertex(stop);
    graph.index();
    linker = VertexLinkerTestFactory.of(graph);
    // What OTP does with every stop
    linker.linkVertexPermanently(
      stop,
      new TraverseModeSet(TraverseMode.WALK),
      LinkingDirection.BIDIRECTIONAL,
      (vertex, streetVertex) ->
        List.of(
          StreetTransitStopLink.createStreetTransitStopLink(stop, streetVertex),
          StreetTransitStopLink.createStreetTransitStopLink(streetVertex, stop)
        )
    );
  }

  @Test
  void otpLinksAStopToTheNearestEdgeEveryTravellerCanUse() {
    build();

    assertThat(stop.getOutgoing()).isNotEmpty();
    for (var link : stop.getOutgoing()) {
      // The level sidewalk in the north, not the steep one the stop stands on
      assertEquals(47.6501797, link.getToVertex().getLat(), 0.00002);
    }
    // From there it is a long way round to the lower end of the sidewalk the stop stands on
    assertThat(secondsFromStopTo("2", null)).isGreaterThan(60.0);
  }

  @Test
  void theModuleLinksItToTheEdgesOnlySomeTravellersCanUseToo() {
    build();
    int before = stop.getOutgoing().size();
    var strong = traveller("uphill", "0.12");
    double wayRound = secondsFromStopTo("3", strong);
    double wayRoundToTheLowerEnd = secondsFromStopTo("2", null);

    new OswStopLinkModule(graph, linker).buildGraph();

    assertThat(stop.getOutgoing().size()).isGreaterThan(before);
    assertThat(
      stop
        .getOutgoing()
        .stream()
        .map(e -> e.getToVertex().getLat())
        .toList()
    ).contains(47.65);
    assertEquals(stop.getOutgoing().size(), stop.getIncoming().size());

    // A traveller who can manage the grade walks half the steep sidewalk up to node 3
    assertThat(secondsFromStopTo("3", strong)).isLessThan(wayRound);
    // The default traveller can not walk it up, and is not sent a longer way than before
    assertNull(secondsFromStopTo("3", null));
    assertThat(secondsFromStopTo("2", null)).isAtMost(wayRoundToTheLowerEnd);
    // A traveller who can manage no grade at all still has the link to the level sidewalk
    var noGrades = PedestrianCostParameters.of(Map.of("uphill", "0", "downhill", "0"));
    assertNotNull(secondsFromStopTo("12", noGrades));
    assertNull(secondsFromStopTo("3", noGrades));
  }

  @Test
  void linkingAgainAddsNothing() {
    build();
    new OswStopLinkModule(graph, linker).buildGraph();
    int links = stop.getOutgoing().size();
    int vertices = graph.getVertices().size();

    new OswStopLinkModule(graph, linker).buildGraph();

    assertEquals(links, stop.getOutgoing().size());
    assertEquals(vertices, graph.getVertices().size());
  }

  private static PedestrianCostParameters traveller(String parameter, String value) {
    return PedestrianCostParameters.of(Map.of(parameter, value));
  }

  /** The time it takes to walk from the stop to a node, or null if there is no way. */
  @Nullable
  private Double secondsFromStopTo(String nodeId, @Nullable PedestrianCostParameters traveller) {
    var request = StreetSearchRequest.of().withWalk(w -> w.withSpeed(SPEED));
    if (traveller != null) {
      request.withExtensionRequestContexts(List.of(traveller));
    }
    Vertex node = graph.getVertex(VertexLabel.string("osw:" + nodeId));
    State state = StreetSearchBuilder.of()
      .withRequest(request.build())
      .withFrom(stop)
      .getShortestPathTree()
      .getState(node);
    return state == null ? null : state.getElapsedTimeMilliseconds() / 1000.0;
  }
}

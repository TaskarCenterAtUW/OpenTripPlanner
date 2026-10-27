package org.opentripplanner.ext.opensidewalks;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opentripplanner.street.geometry.SphericalDistanceLibrary;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.model.StreetMode;
import org.opentripplanner.street.model.StreetTraversalPermission;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.vertex.Vertex;
import org.opentripplanner.street.search.StreetSearchBuilder;
import org.opentripplanner.street.search.request.StreetSearchRequest;
import org.opentripplanner.street.search.state.State;
import org.opentripplanner.test.support.ResourceLoader;

/**
 * The OpenSidewalks dataset of the Microsoft campus in Redmond, which has roads as well as
 * sidewalks and crossings.
 */
class OswRoadNetworkTest {

  private static final ResourceLoader RESOURCES = ResourceLoader.of(OswRoadNetworkTest.class);
  private static final Graph GRAPH = new Graph();
  private static OswCosting costing;

  @BeforeAll
  static void build() {
    costing = OswCosting.fromFile(RESOURCES.file("ws-prod.json"), Map.of());
    new OswModule(GRAPH, List.of(RESOURCES.file("microsoft-campus")), costing).buildGraph();
  }

  @Test
  void roadsCanBeUsedByBicyclesAndCarsAndTheRestOnlyWalked() {
    assertTrue(costing.hasRoads());
    var edges = streetEdges();
    long roads = edges
      .stream()
      .filter(e -> e.getPermission() == StreetTraversalPermission.ALL)
      .count();
    long walkways = edges
      .stream()
      .filter(e -> e.getPermission() == StreetTraversalPermission.PEDESTRIAN)
      .count();
    // Both directions of each road and walkway of the dataset
    assertEquals(2 * 876, roads);
    assertEquals(edges.size(), roads + walkways);
  }

  @Test
  void aCarDrivesAlongTheRoads() {
    var start = roadVertex();

    var reached = search(start, StreetMode.CAR);

    double farthest = reached
      .stream()
      .mapToDouble(s ->
        SphericalDistanceLibrary.distance(start.getCoordinate(), s.getVertex().getCoordinate())
      )
      .max()
      .orElse(0);
    assertThat(farthest).isGreaterThan(500.0);
    // and only along the roads
    for (var state : reached) {
      if (state.getBackEdge() instanceof StreetEdge edge) {
        assertEquals(StreetTraversalPermission.ALL, edge.getPermission());
      }
    }
  }

  @Test
  void aCarDoesNotGoAlongASidewalk() {
    var sidewalkOnly = streetEdges()
      .stream()
      .filter(e -> "sidewalk".equals(e.getName().toString()))
      .map(StreetEdge::getFromVertex)
      .filter(v ->
        v
          .getOutgoing()
          .stream()
          .allMatch(e -> ((StreetEdge) e).getPermission() == StreetTraversalPermission.PEDESTRIAN)
      )
      .findFirst()
      .orElseThrow();

    assertThat(search(sidewalkOnly, StreetMode.CAR)).hasSize(1);
  }

  private static List<StreetEdge> streetEdges() {
    List<StreetEdge> edges = new ArrayList<>();
    GRAPH.findEdges(StreetEdge.class).forEach(edges::add);
    return edges;
  }

  private static Vertex roadVertex() {
    return streetEdges()
      .stream()
      .filter(e -> e.getPermission() == StreetTraversalPermission.ALL)
      .filter(
        e -> "primary".equals(e.getName().toString()) || e.getName().toString().contains("Way")
      )
      .findFirst()
      .orElseThrow()
      .getFromVertex();
  }

  private static List<State> search(Vertex from, StreetMode mode) {
    var request = StreetSearchRequest.of()
      .withMode(mode)
      .withExtensionRequestContexts(
        List.of(PedestrianCostParameters.of(Map.of(), StreetNetwork.OPEN_SIDEWALKS))
      );
    return List.copyOf(
      StreetSearchBuilder.of()
        .withRequest(request.build())
        .withFrom(from)
        .getShortestPathTree()
        .getAllStates()
    );
  }
}

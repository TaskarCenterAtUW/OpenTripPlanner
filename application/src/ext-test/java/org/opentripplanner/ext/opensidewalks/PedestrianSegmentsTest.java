package org.opentripplanner.ext.opensidewalks;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opentripplanner.core.model.accessibility.Accessibility;
import org.opentripplanner.core.model.i18n.I18NString;
import org.opentripplanner.core.model.i18n.NonLocalizedString;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.model.StreetModelForTest;
import org.opentripplanner.street.model.StreetTraversalPermission;
import org.opentripplanner.street.model.edge.ElevatorHopEdge;
import org.opentripplanner.street.model.edge.PathwayEdge;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.path.StreetPath;
import org.opentripplanner.street.model.vertex.SplitterVertex;
import org.opentripplanner.street.model.vertex.Vertex;
import org.opentripplanner.street.model.vertex.VertexLabel;
import org.opentripplanner.street.search.StreetSearchBuilder;
import org.opentripplanner.street.search.request.StreetSearchRequest;
import org.opentripplanner.test.support.ResourceLoader;

/**
 * Walks the small synthetic dataset of {@link OswModuleTest} from the southern sidewalk to the
 * northern one, over the crossing 21-22.
 */
class PedestrianSegmentsTest {

  private static final ResourceLoader RESOURCES = ResourceLoader.of(PedestrianSegmentsTest.class);
  private static final File DATASET = RESOURCES.file("tiny");
  private static final OswCosting PROFILE = OswCosting.fromFile(
    RESOURCES.file("unweaver-dynamic.json"),
    Map.of()
  );

  @Test
  void aSegmentForEachEdgeWalked() {
    var graph = build();

    var segments = walk(graph, "2", "12");

    assertThat(segments.stream().map(PedestrianSegment::footway).toList())
      .containsExactly("sidewalk", "crossing", "sidewalk")
      .inOrder();
    var crossing = segments.get(1);
    assertEquals("footway", crossing.highway());
    assertEquals(15.98, crossing.distanceMeters(), 0.01);
    assertNull(crossing.incline());
    assertTrue(crossing.curbRamps());
    assertFalse(crossing.elevator());
    // The geometry is in the direction it is walked
    assertEquals(vertex(graph, "21").getCoordinate(), crossing.geometry().getCoordinateN(0));
  }

  @Test
  void theInclineIsInTheDirectionWalked() {
    var graph = build();

    assertEquals(0.01, walk(graph, "2", "12").getFirst().incline(), 1e-9);
    assertEquals(-0.01, walk(graph, "12", "2").getLast().incline(), 1e-9);
  }

  @Test
  void thePartsOfAnEdgeAreOneSegment() {
    var graph = build();
    var crossing = edge(graph, "21", "22");
    var from = crossing.getFromVertex();
    var to = crossing.getToVertex();
    // Splitting it for good, like linking a stop to it does
    crossing.splitDestructively(
      new SplitterVertex(
        "quarter",
        from.getLon() + (to.getLon() - from.getLon()) * 0.25,
        from.getLat() + (to.getLat() - from.getLat()) * 0.25,
        I18NString.of("quarter")
      )
    );
    graph.removeEdge(crossing);

    var segments = walk(graph, "2", "12");

    assertThat(segments).hasSize(3);
    assertEquals(15.98, segments.get(1).distanceMeters(), 0.01);
    assertEquals(3, segments.get(1).geometry().getNumPoints());
  }

  @Test
  void stationPathwaysAreSegmentsToo() {
    var graph = build();
    var sidewalkEnd = vertex(graph, "12");
    var mezzanine = StreetModelForTest.intersectionVertex(
      "mezzanine",
      sidewalkEnd.getLat() + 0.0001,
      sidewalkEnd.getLon()
    );
    var platform = StreetModelForTest.intersectionVertex(
      "platform",
      sidewalkEnd.getLat() + 0.0001,
      sidewalkEnd.getLon()
    );
    graph.addVertex(mezzanine);
    graph.addVertex(platform);
    PathwayEdge.createPathwayEdge(
      sidewalkEnd,
      mezzanine,
      NonLocalizedString.ofNullable("To trains"),
      0,
      20,
      0,
      0,
      true,
      PathwayEdge.Mode.ESCALATOR,
      new PathwayEdge.Level(0, "Street Level"),
      new PathwayEdge.Level(-1, "Mezzanine")
    );
    ElevatorHopEdge.createElevatorHopEdge(
      mezzanine,
      platform,
      StreetTraversalPermission.PEDESTRIAN,
      Accessibility.POSSIBLE,
      1,
      30
    );

    var segments = walk(graph, "2", platform);

    assertThat(segments.stream().map(PedestrianSegment::pathwayMode).toList())
      .containsExactly(null, null, null, "ESCALATOR", "ELEVATOR")
      .inOrder();
    var escalator = segments.get(3);
    assertEquals("To trains", escalator.name());
    assertEquals(20, escalator.distanceMeters(), 0.01);
    assertTrue(escalator.indoor());
    assertEquals("Street Level", escalator.fromLevel());
    assertEquals("Mezzanine", escalator.toLevel());
    assertEquals(-1, escalator.levelChange(), 1e-9);
    assertTrue(segments.get(4).elevator());
  }

  @Test
  void noSegmentsWithoutAProfile() {
    var graph = new Graph();
    new OswModule(graph, List.of(DATASET), null).buildGraph();

    assertThat(walk(graph, "2", "12")).isEmpty();
  }

  private static Graph build() {
    var graph = new Graph();
    new OswModule(graph, List.of(DATASET), PROFILE).buildGraph();
    return graph;
  }

  private static List<PedestrianSegment> walk(Graph graph, String fromNodeId, String toNodeId) {
    return walk(graph, fromNodeId, vertex(graph, toNodeId));
  }

  private static List<PedestrianSegment> walk(Graph graph, String fromNodeId, Vertex to) {
    var state = StreetSearchBuilder.of()
      .withRequest(StreetSearchRequest.of().build())
      .withFrom(vertex(graph, fromNodeId))
      .withTo(to)
      .getShortestPathTree()
      .getState(to);
    return PedestrianSegments.of(new StreetPath(state).states(), null);
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

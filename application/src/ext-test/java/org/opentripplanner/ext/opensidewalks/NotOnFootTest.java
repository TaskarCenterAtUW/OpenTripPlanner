package org.opentripplanner.ext.opensidewalks;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentripplanner.street.model.StreetModelForTest.intersectionVertex;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opentripplanner.core.model.i18n.I18NString;
import org.opentripplanner.routing.linking.internal.VertexCreationService.LocationType;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.model.StreetMode;
import org.opentripplanner.street.model.StreetModelForTest;
import org.opentripplanner.street.model.StreetTraversalPermission;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.vertex.SplitterVertex;
import org.opentripplanner.street.model.vertex.StreetVertex;
import org.opentripplanner.street.search.StreetSearchBuilder;
import org.opentripplanner.street.search.TraverseMode;
import org.opentripplanner.street.search.TraverseModeSet;
import org.opentripplanner.street.search.request.StreetSearchRequest;
import org.opentripplanner.test.support.ResourceLoader;

/**
 * Streets from other data than OpenSidewalks: a road from A to B that all may use, and a footpath
 * from B to C.
 */
class NotOnFootTest {

  private static final ResourceLoader RESOURCES = ResourceLoader.of(NotOnFootTest.class);
  private static final TraverseModeSet WALK = new TraverseModeSet(TraverseMode.WALK);
  private static final TraverseModeSet BICYCLE = new TraverseModeSet(TraverseMode.BICYCLE);
  private static final TraverseModeSet CAR = new TraverseModeSet(TraverseMode.CAR);

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

  private void loadOpenSidewalks(boolean walkOnOtherStreets) {
    var graph = new Graph();
    graph.addVertex(a);
    graph.addVertex(b);
    graph.addVertex(c);
    new OswModule(
      graph,
      List.of(RESOURCES.file("tiny")),
      OswCosting.fromFile(RESOURCES.file("unweaver-dynamic.json"), Map.of()),
      walkOnOtherStreets
    ).buildGraph();
  }

  @Test
  void peopleOnFootAreNotLinkedToOtherStreets() {
    loadOpenSidewalks(false);

    assertFalse(road.canTraverse(WALK));
    assertFalse(footpath.canTraverse(WALK));
    // Those who drive and ride are, where they may
    assertTrue(road.canTraverse(CAR));
    assertTrue(road.canTraverse(BICYCLE));
    assertFalse(footpath.canTraverse(BICYCLE));
  }

  @Test
  void peopleOnFootDoNotWalkOtherStreets() {
    loadOpenSidewalks(false);

    assertFalse(reaches(StreetMode.WALK, c));
    assertFalse(reaches(StreetMode.WALK, b));
  }

  @Test
  void aBicycleIsRiddenAndWalkedOnOtherStreets() {
    loadOpenSidewalks(false);

    // Along the road, and then walking the bicycle along the footpath
    assertTrue(reaches(StreetMode.BIKE, c));
    assertTrue(reaches(StreetMode.CAR, b));
  }

  @Test
  void thePartsOfASplitStreetAreKeptFromPeopleOnFootToo() {
    loadOpenSidewalks(false);
    var middle = new SplitterVertex("middle", -122.3, 47.6605, I18NString.of("middle"));

    var parts = road.splitDestructively(middle);

    assertFalse(parts.head().canTraverse(WALK));
    assertFalse(parts.tail().canTraverse(WALK));
    assertTrue(parts.head().canTraverse(CAR));
  }

  @Test
  void otherStreetsCanBeLeftForPeopleOnFoot() {
    loadOpenSidewalks(true);

    assertTrue(road.canTraverse(WALK));
    assertTrue(reaches(StreetMode.WALK, c));
  }

  @Test
  void aTripWithOnesOwnBicycleIsLinkedToAStreetItCanBeRiddenOn() {
    assertTrue(OswLinking.isWithOwnBicycle(StreetMode.BIKE, LocationType.FROM));
    assertTrue(OswLinking.isWithOwnBicycle(StreetMode.BIKE, LocationType.TO));
    // After parking the bicycle the traveller is on foot
    assertTrue(OswLinking.isWithOwnBicycle(StreetMode.BIKE_TO_PARK, LocationType.FROM));
    assertFalse(OswLinking.isWithOwnBicycle(StreetMode.BIKE_TO_PARK, LocationType.TO));
    // As before picking up a rented one
    assertFalse(OswLinking.isWithOwnBicycle(StreetMode.BIKE_RENTAL, LocationType.FROM));
    assertFalse(OswLinking.isWithOwnBicycle(StreetMode.WALK, LocationType.FROM));
  }

  private boolean reaches(StreetMode mode, StreetVertex vertex) {
    var states = StreetSearchBuilder.of()
      .withRequest(StreetSearchRequest.of().withMode(mode).build())
      .withFrom(a)
      .getShortestPathTree()
      .getAllStates();
    return states.stream().anyMatch(s -> s.getVertex() == vertex);
  }
}

package org.opentripplanner.ext.opensidewalks;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentripplanner.street.model.StreetModelForTest.intersectionVertex;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentripplanner.apis.support.InvalidInputException;
import org.opentripplanner.core.model.i18n.I18NString;
import org.opentripplanner.core.model.i18n.NonLocalizedString;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.ext.flex.flexpathcalculator.StreetFlexPathCalculator;
import org.opentripplanner.framework.application.OTPFeature;
import org.opentripplanner.model.GenericLocation;
import org.opentripplanner.routing.linking.LinkingContextRequest;
import org.opentripplanner.routing.linking.VertexLinkerTestFactory;
import org.opentripplanner.service.vehicleparking.model.VehicleParking;
import org.opentripplanner.service.vehicleparking.model.VehicleParkingEntrance;
import org.opentripplanner.service.vehiclerental.model.TestVehicleRentalStationBuilder;
import org.opentripplanner.service.vehiclerental.street.StreetVehicleRentalLink;
import org.opentripplanner.service.vehiclerental.street.VehicleRentalPlaceVertex;
import org.opentripplanner.street.geometry.WgsCoordinate;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.linking.LinkingDirection;
import org.opentripplanner.street.model.StreetMode;
import org.opentripplanner.street.model.StreetModelForTest;
import org.opentripplanner.street.model.StreetTraversalPermission;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.edge.StreetVehicleParkingLink;
import org.opentripplanner.street.model.vertex.SplitterVertex;
import org.opentripplanner.street.model.vertex.StreetVertex;
import org.opentripplanner.street.model.vertex.VehicleParkingEntranceVertex;
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

  /**
   * A request on the OpenSidewalks edges, as a query with values is: someone who avoids curbs and
   * has limits on grades.
   */
  private static final PedestrianCostParameters WITH_LIMITS = PedestrianCostParameters.of(
    Map.of("uphill", "0.085", "avoid_curbs", "true"),
    StreetNetwork.OPEN_SIDEWALKS
  );

  /** Values that do not say which network, like those of the router configuration. */
  private static final PedestrianCostParameters CONFIGURED = PedestrianCostParameters.of(
    Map.of("uphill", "0.085", "avoid_curbs", "true")
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
  void valuesThatDoNotSayWhichNetworkAreOnTheOtherStreets() {
    assertTrue(reaches(a, StreetMode.WALK, CONFIGURED, c));
    assertFalse(reaches(node("2"), StreetMode.WALK, CONFIGURED, node("1")));
  }

  @Test
  void aTravellerWithLimitsWalksTheOpenSidewalksEdges() {
    assertTrue(reaches(node("2"), StreetMode.WALK, WITH_LIMITS, node("1")));
    // And not the other streets
    assertFalse(reaches(a, StreetMode.WALK, WITH_LIMITS, b));
  }

  @Test
  void bicyclesAndCarsAreOnTheNetworkOfTheRequestToo() {
    // Along the road, and then walking the bicycle along the footpath
    assertTrue(reaches(a, StreetMode.BIKE, null, c));
    assertTrue(reaches(a, StreetMode.CAR, null, b));
    assertFalse(reaches(node("2"), StreetMode.BIKE, null, node("1")));
    // A request on the OpenSidewalks edges uses none of the other streets
    assertFalse(reaches(a, StreetMode.BIKE, WITH_LIMITS, c));
    assertFalse(reaches(a, StreetMode.CAR, WITH_LIMITS, b));
    // and walks a bicycle along the sidewalks, which have no roads in this dataset
    assertTrue(reaches(node("2"), StreetMode.BIKE, WITH_LIMITS, node("1")));
  }

  @Test
  void thePlacesOfARequestAreLinkedToTheStreetsOfItsTraveller() {
    var sidewalk = edge("2", "1");

    try (var ignore = OswLinking.linkAs(null)) {
      assertTrue(road.canTraverse(WALK));
      assertFalse(sidewalk.canTraverse(WALK));
    }
    try (var ignore = OswLinking.linkAs(CONFIGURED)) {
      assertTrue(road.canTraverse(WALK));
      assertFalse(sidewalk.canTraverse(WALK));
    }
    try (var ignore = OswLinking.linkAs(WITH_LIMITS)) {
      assertFalse(road.canTraverse(WALK));
      assertFalse(footpath.canTraverse(WALK));
      assertTrue(sidewalk.canTraverse(WALK));
      // Not for driving either
      assertFalse(road.canTraverse(CAR));
    }
  }

  @Test
  void aRequestOnOpenSidewalksDrivesOnItsRoads() {
    var road = (OswStreetEdge) edge("3", "61");

    try (var ignore = OswLinking.linkAs(WITH_LIMITS)) {
      assertTrue(road.canTraverse(CAR));
    }
    assertTrue(reaches(node("3"), StreetMode.CAR, WITH_LIMITS, node("61")));
    assertFalse(reaches(node("3"), StreetMode.CAR, null, node("61")));
  }

  @Test
  void aRequestOnOpenSidewalksWithoutRoadsCanNotDrive() {
    var sidewalk = (OswStreetEdge) edge("2", "1");
    var withoutRoads = OswCosting.fromFile(RESOURCES.file("unweaver-dynamic.json"), Map.of());

    try (var ignore = OswLinking.linkAs(WITH_LIMITS)) {
      var e = assertThrows(InvalidInputException.class, () ->
        OswLinking.isLinkable(withoutRoads, sidewalk.attributes(), sidewalk, CAR)
      );
      assertThat(e.getMessage()).contains("no roads");
      // Walking is fine
      assertTrue(OswLinking.isLinkable(withoutRoads, sidewalk.attributes(), sidewalk, WALK));
    }
    // OTP links a request that cycles for walking. It is the request that cycles.
    var cycling = LinkingContextRequest.of()
      .withFrom(GenericLocation.fromCoordinate(47.65, -122.298))
      .withDirectMode(StreetMode.BIKE)
      .withExtensions(List.of(WITH_LIMITS))
      .build();
    try (var ignore = OswLinking.linkFor(cycling)) {
      assertThrows(InvalidInputException.class, () ->
        OswLinking.isLinkable(withoutRoads, sidewalk.attributes(), sidewalk, WALK)
      );
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
      assertFalse(parts.head().canTraverse(CAR));
    }
  }

  @Test
  void walkingInATripThatRentsOrIsPickedUpIsCostedWithTheProfile() {
    // The sidewalk from 2 to 3 is 10% uphill, more than WITH_LIMITS can manage
    var strong = PedestrianCostParameters.of(
      Map.of("uphill", "0.12"),
      StreetNetwork.OPEN_SIDEWALKS
    );
    for (var mode : List.of(StreetMode.BIKE_RENTAL, StreetMode.CAR_PICKUP)) {
      assertFalse(reaches(node("2"), mode, WITH_LIMITS, node("3")), mode.name());
      assertTrue(reaches(node("2"), mode, strong, node("3")), mode.name());
    }
  }

  @Test
  void aVehicleForRentIsLinkedToTheOpenSidewalksEdgesForTripsOnThem() {
    graph.index();
    var linker = VertexLinkerTestFactory.of(graph);
    var station = TestVehicleRentalStationBuilder.of().withCoordinates(47.65, -122.298).build();
    var place = new VehicleRentalPlaceVertex(station);
    graph.addVertex(place);

    // What OTP does: the other streets are too far away
    linker.linkVertexForRealTime(
      place,
      WALK,
      LinkingDirection.BIDIRECTIONAL,
      StreetVehicleRentalLink::createBidirectionalLinks
    );
    assertThat(place.getOutgoing()).isEmpty();

    try (var ignore = OswLinking.linkForEveryone()) {
      linker.linkVertexForRealTime(
        place,
        WALK,
        LinkingDirection.BIDIRECTIONAL,
        StreetVehicleRentalLink::createBidirectionalLinks
      );
    }
    assertThat(place.getOutgoing()).isNotEmpty();
    for (var link : place.getOutgoing()) {
      assertTrue(OswStreetEdge.isOnOpenSidewalksEdge(link.getToVertex()));
    }
  }

  @Test
  void aCarParkIsLinkedToTheRoadsOfTheOpenSidewalksDataForCars() {
    var parking = VehicleParking.of()
      .id(new FeedScopedId("F", "car-park"))
      .coordinate(new WgsCoordinate(47.6502, -122.297))
      .carPlaces(true)
      .build();
    var entrance = new VehicleParkingEntranceVertex(
      VehicleParkingEntrance.of()
        .vehicleParking(parking)
        .entranceId(new FeedScopedId("F", "entrance"))
        .coordinate(new WgsCoordinate(47.6502, -122.297))
        .name(new NonLocalizedString("entrance"))
        .walkAccessible(true)
        .carAccessible(true)
        .build()
    );
    graph.addVertex(entrance);
    graph.index();

    new OswLinkModule(graph, VertexLinkerTestFactory.of(graph)).buildGraph();

    // Along the road from 3 to 61, which only a trip on the OpenSidewalks edges drives on
    assertTrue(reaches(node("61"), StreetMode.CAR, WITH_LIMITS, entrance));
    assertTrue(
      entrance
        .getOutgoing()
        .stream()
        .anyMatch(
          e ->
            e instanceof StreetVehicleParkingLink &&
            !(e instanceof OswParkingLink) &&
            e
              .getToVertex()
              .getOutgoing()
              .stream()
              .anyMatch(r -> ((StreetEdge) r).getPermission() == StreetTraversalPermission.ALL)
        )
    );
  }

  @Test
  void aFlexVehicleThatStartsOnTheOpenSidewalksDataDrivesOnItsRoads() {
    var calculator = new StreetFlexPathCalculator(false, Duration.ofHours(1));

    OTPFeature.OpenSidewalks.testOn(() ->
      assertNotNull(calculator.calculateFlexPath(node("3"), node("61"), 0, 1))
    );
    // and one on the other streets on those
    assertNotNull(calculator.calculateFlexPath(a, b, 0, 1));
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

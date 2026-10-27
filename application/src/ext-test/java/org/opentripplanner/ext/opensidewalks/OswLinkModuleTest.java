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
import org.opentripplanner.core.model.i18n.NonLocalizedString;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.routing.linking.VertexLinkerTestFactory;
import org.opentripplanner.service.vehicleparking.model.VehicleParking;
import org.opentripplanner.service.vehicleparking.model.VehicleParkingEntrance;
import org.opentripplanner.street.geometry.WgsCoordinate;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.linking.LinkingDirection;
import org.opentripplanner.street.linking.VertexLinker;
import org.opentripplanner.street.model.StreetModelForTest;
import org.opentripplanner.street.model.edge.StreetTransitEntranceLink;
import org.opentripplanner.street.model.edge.StreetTransitStopLink;
import org.opentripplanner.street.model.edge.StreetVehicleParkingLink;
import org.opentripplanner.street.model.vertex.StationCentroidVertex;
import org.opentripplanner.street.model.vertex.TransitEntranceVertex;
import org.opentripplanner.street.model.vertex.TransitStopVertex;
import org.opentripplanner.street.model.vertex.VehicleParkingEntranceVertex;
import org.opentripplanner.street.model.vertex.Vertex;
import org.opentripplanner.street.model.vertex.VertexLabel;
import org.opentripplanner.street.search.StreetSearchBuilder;
import org.opentripplanner.street.search.TraverseMode;
import org.opentripplanner.street.search.TraverseModeSet;
import org.opentripplanner.street.search.request.StreetSearchRequest;
import org.opentripplanner.street.search.state.State;
import org.opentripplanner.test.support.ResourceLoader;

/**
 * A stop, and the entrance of a car park, stand halfway along the south sidewalk of the small
 * synthetic dataset, between the nodes 2 and 3. That part of the sidewalk is 10% steep, which not
 * every traveller can manage. The sidewalk across the street, 20 m to the north, is level.
 */
class OswLinkModuleTest {

  private static final ResourceLoader RESOURCES = ResourceLoader.of(OswLinkModuleTest.class);
  private static final File DATASET = RESOURCES.file("tiny");
  private static final File PROFILE = RESOURCES.file("unweaver-dynamic.json");
  private static final double SPEED = 1.3;

  private final Graph graph = new Graph();
  private final TransitStopVertex stop = TransitStopVertex.of()
    .withId(new FeedScopedId("F", "stop"))
    .withCoordinate(47.65, -122.298)
    .build();
  private final VehicleParkingEntranceVertex entrance = carParkEntrance();
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

  private void buildWithCarPark() {
    build();
    graph.addVertex(entrance);
    graph.index();
    // What OTP does with every car park that can be walked to
    linker.linkVertexPermanently(
      entrance,
      new TraverseModeSet(TraverseMode.WALK),
      LinkingDirection.BIDIRECTIONAL,
      (vertex, streetVertex) ->
        List.of(
          StreetVehicleParkingLink.createStreetVehicleParkingLink(entrance, streetVertex),
          StreetVehicleParkingLink.createStreetVehicleParkingLink(streetVertex, entrance)
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

    new OswLinkModule(graph, linker).buildGraph();

    assertThat(stop.getOutgoing().size()).isGreaterThan(before);
    assertThat(
      stop
        .getOutgoing()
        .stream()
        .map(e -> e.getToVertex().getLat())
        .toList()
    ).contains(47.65);
    assertEquals(stop.getOutgoing().size(), stop.getIncoming().size());
    // All of the links take the time of walking them, the one OTP made too
    for (var link : stop.getOutgoing()) {
      boolean acrossTheStreet = link.getToVertex().getLat() > 47.6501;
      // The sidewalk across the street is 22 m away where it passes the stop
      assertEquals(acrossTheStreet ? 17 : 0, ((OswStopLink) link).walkSeconds(), 1);
    }

    // A traveller who can manage the grade walks half the steep sidewalk up to node 3
    assertThat(secondsFromStopTo("3", strong)).isLessThan(wayRound);
    // The default traveller can not walk it up, and goes the way round as before. It now takes
    // the time of walking the 22 m to the level sidewalk as well.
    assertNull(secondsFromStopTo("3", null));
    assertThat(secondsFromStopTo("2", null) - wayRoundToTheLowerEnd)
      .isWithin(3)
      .of(20 / SPEED);
    // A traveller who can manage no grade at all still has the link to the level sidewalk
    var noGrades = PedestrianCostParameters.of(Map.of("uphill", "0", "downhill", "0"));
    assertNotNull(secondsFromStopTo("12", noGrades));
    assertNull(secondsFromStopTo("3", noGrades));
  }

  @Test
  void aStopIsNotLinkedToEdgesNobodyNeeds() {
    build();
    new OswLinkModule(graph, linker).buildGraph();

    // The level sidewalk across the street is for everyone who can not use the steep one. Nothing
    // beyond it is linked: not the crossings, and not the sidewalk west of node 2.
    for (var link : stop.getOutgoing()) {
      var place = link.getToVertex();
      assertThat(place.getLon()).isWithin(0.00002).of(-122.298);
    }
    assertThat(stop.getOutgoing().size()).isAtMost(4);
  }

  @Test
  void linkingAgainAddsNothing() {
    build();
    new OswLinkModule(graph, linker).buildGraph();
    int links = stop.getOutgoing().size();
    int vertices = graph.getVertices().size();

    new OswLinkModule(graph, linker).buildGraph();

    assertEquals(links, stop.getOutgoing().size());
    assertEquals(vertices, graph.getVertices().size());
  }

  @Test
  void aStationEntranceIsLinkedLikeAStop() {
    TransitEntranceVertex entrance = StreetModelForTest.transitEntranceVertex(
      "entrance",
      47.65,
      -122.298
    );
    build();
    graph.addVertex(entrance);
    graph.index();
    // What OTP does with every station entrance
    linker.linkVertexPermanently(
      entrance,
      new TraverseModeSet(TraverseMode.WALK),
      LinkingDirection.BIDIRECTIONAL,
      (vertex, streetVertex) ->
        List.of(
          StreetTransitEntranceLink.createStreetTransitEntranceLink(entrance, streetVertex),
          StreetTransitEntranceLink.createStreetTransitEntranceLink(streetVertex, entrance)
        )
    );
    var strong = traveller("uphill", "0.12");
    double wayRound = secondsFrom(entrance, "3", strong);

    new OswLinkModule(graph, linker).buildGraph();

    assertThat(
      entrance
        .getOutgoing()
        .stream()
        .map(e -> e.getToVertex().getLat())
        .toList()
    ).contains(47.65);
    assertEquals(entrance.getOutgoing().size(), entrance.getIncoming().size());
    for (var link : entrance.getOutgoing()) {
      boolean acrossTheStreet = link.getToVertex().getLat() > 47.6501;
      assertEquals(acrossTheStreet ? 17 : 0, ((OswEntranceLink) link).walkSeconds(), 1);
    }
    assertThat(secondsFrom(entrance, "3", strong)).isLessThan(wayRound);
  }

  @Test
  void aCarParkIsLinkedLikeAStop() {
    buildWithCarPark();
    var strong = traveller("uphill", "0.12");
    double wayRound = secondsFrom(entrance, "3", strong);

    new OswLinkModule(graph, linker).buildGraph();

    assertThat(
      entrance
        .getOutgoing()
        .stream()
        .map(e -> e.getToVertex().getLat())
        .toList()
    ).contains(47.65);
    assertEquals(entrance.getOutgoing().size(), entrance.getIncoming().size());
    // The link OTP made is replaced too, and they all take the time of walking them
    for (var link : entrance.getOutgoing()) {
      boolean acrossTheStreet = link.getToVertex().getLat() > 47.6501;
      assertEquals(acrossTheStreet ? 17 : 0, ((OswParkingLink) link).walkSeconds(), 1);
    }
    assertThat(entrance.isLinkedToGraph()).isTrue();

    // A traveller who parks there and can manage the grade walks half the steep sidewalk up
    assertThat(secondsFrom(entrance, "3", strong)).isLessThan(wayRound);
    assertNull(secondsFrom(entrance, "3", null));
  }

  @Test
  void aStationCentroidIsLinkedLikeAStop() {
    var centroid = new StationCentroidVertex(
      new FeedScopedId("F", "station"),
      new NonLocalizedString("station"),
      new WgsCoordinate(47.65, -122.298)
    );
    build();
    graph.addVertex(centroid);
    graph.index();
    var strong = traveller("uphill", "0.12");

    new OswLinkModule(graph, linker).buildGraph();

    assertThat(
      centroid
        .getOutgoing()
        .stream()
        .map(e -> e.getToVertex().getLat())
        .toList()
    ).contains(47.65);
    for (var link : centroid.getOutgoing()) {
      boolean acrossTheStreet = link.getToVertex().getLat() > 47.6501;
      assertEquals(acrossTheStreet ? 17 : 0, ((OswStationCentroidLink) link).walkSeconds(), 1);
    }
    assertNotNull(secondsFrom(centroid, "3", strong));
  }

  @Test
  void aBicycleParkIsLinkedToo() {
    var bicycleParking = VehicleParking.of()
      .id(new FeedScopedId("F", "bicycle-parking"))
      .coordinate(new WgsCoordinate(47.65, -122.298))
      .bicyclePlaces(true)
      .build();
    var bicycleEntrance = entrance(bicycleParking);
    build();
    graph.addVertex(bicycleEntrance);
    graph.index();

    new OswLinkModule(graph, linker).buildGraph();

    assertThat(bicycleEntrance.getOutgoing()).isNotEmpty();
    for (var link : bicycleEntrance.getOutgoing()) {
      assertThat(link).isInstanceOf(OswParkingLink.class);
    }
  }

  private static VehicleParkingEntranceVertex carParkEntrance() {
    return entrance(
      VehicleParking.of()
        .id(new FeedScopedId("F", "car-park"))
        .coordinate(new WgsCoordinate(47.65, -122.298))
        .carPlaces(true)
        .build()
    );
  }

  private static VehicleParkingEntranceVertex entrance(VehicleParking parking) {
    return new VehicleParkingEntranceVertex(
      VehicleParkingEntrance.of()
        .vehicleParking(parking)
        .entranceId(new FeedScopedId("F", parking.getId().getId() + "-entrance"))
        .coordinate(new WgsCoordinate(47.65, -122.298))
        .name(new NonLocalizedString("entrance"))
        .walkAccessible(true)
        .carAccessible(true)
        .build()
    );
  }

  private static PedestrianCostParameters traveller(String parameter, String value) {
    return PedestrianCostParameters.of(Map.of(parameter, value));
  }

  /** The time it takes to walk from the stop to a node, or null if there is no way. */
  @Nullable
  private Double secondsFromStopTo(String nodeId, @Nullable PedestrianCostParameters traveller) {
    return secondsFrom(stop, nodeId, traveller);
  }

  /** The time it takes to walk from the vertex to a node, or null if there is no way. */
  @Nullable
  private Double secondsFrom(
    Vertex from,
    String nodeId,
    @Nullable PedestrianCostParameters traveller
  ) {
    var request = StreetSearchRequest.of().withWalk(w -> w.withSpeed(SPEED));
    if (traveller != null) {
      request.withExtensionRequestContexts(List.of(traveller));
    }
    Vertex node = graph.getVertex(VertexLabel.string("osw:" + nodeId));
    State state = StreetSearchBuilder.of()
      .withRequest(request.build())
      .withFrom(from)
      .getShortestPathTree()
      .getState(node);
    return state == null ? null : state.getElapsedTimeMilliseconds() / 1000.0;
  }
}

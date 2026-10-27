package org.opentripplanner.ext.opensidewalks;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.opentripplanner.model.GenericLocation;
import org.opentripplanner.model.plan.Itinerary;
import org.opentripplanner.routing.algorithm.raptoradapter.router.street.DirectStreetRouter;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.routing.error.RoutingValidationException;
import org.opentripplanner.routing.linking.LinkingContextFactory;
import org.opentripplanner.routing.linking.VertexLinkerTestFactory;
import org.opentripplanner.routing.linking.internal.VertexCreationService;
import org.opentripplanner.routing.linking.mapping.LinkingContextRequestMapper;
import org.opentripplanner.standalone.api.TestServerContext;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.linking.TemporaryVerticesContainer;
import org.opentripplanner.test.support.ResourceLoader;
import org.opentripplanner.transfer.regular.TransferServiceTestFactory;
import org.opentripplanner.transit.service.TransitRepository;
import org.opentripplanner.transit.service.TransitService;

/**
 * Compares the walks OTP plans over an OpenSidewalks dataset with the ones two other routing
 * engines planned between the same places with the same traveller profiles: R5, in a run of the
 * TDEI quality reports, and AccessMap, which runs on Unweaver. The answers of both are saved in
 * {@code goldendale/routes.json}, which describes where they come from.
 * <p>
 * R5 was given the same dataset and the same pedestrian cost profile as OTP is here, so OTP has
 * to agree with it route by route, apart from the differences listed below. AccessMap has
 * another version of the dataset, so OTP only has to agree with it overall.
 * <p>
 * When a test here fails after a change, either the change is a mistake, or it is a new
 * difference to the other engines that has to be understood, and then listed here.
 */
class OswRoutingComparisonTest {

  private static final ResourceLoader RESOURCES = ResourceLoader.of(OswRoutingComparisonTest.class);
  private static final double WALK_SPEED = 1.3;
  private static final Instant TIME = Instant.parse("2026-10-07T12:00:00Z");

  /**
   * R5 and AccessMap do not start or end a route more than this far from the network. OTP links
   * a place to the nearest edge however far away it is, so it finds routes where they do not.
   */
  private static final double MAX_METERS_FROM_NETWORK = 50;

  /**
   * R5 walks 605 m here where a path of 556 m is quicker by its own costs. OTP takes the shorter
   * one, and so does AccessMap.
   */
  private static final Set<String> R5_MISSES_A_SHORTER_PATH = Set.of("manual_wheelchair 25");

  /**
   * The destination is 21 m from the network, but the edges there are crossings without curb
   * ramps. The nearest edge the traveller can use is 251 m away. OTP links the destination to
   * it and finds a route there. R5 and AccessMap find none.
   */
  private static final Set<String> LINKED_PAST_AN_UNUSABLE_EDGE = Set.of("manual_wheelchair 56");

  /**
   * The engines link a place to slightly different points of an edge, and AccessMap has another
   * version of the dataset, so a walk can begin or end a little off the path of another engine,
   * or cross on the other side of an intersection. The walks are 1.4 m off at the median, and
   * 18 m at the most. This is less than the width of a street, where a walk around another block
   * would be a hundred meters off.
   */
  private static final double MAX_METERS_OFF_PATH = 20;

  private static final double METERS_PER_DEGREE_LAT = 110_540;
  private static final double METERS_PER_DEGREE_LON = 111_320 * Math.cos(Math.toRadians(45.82));
  private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
  private static final JsonNode PATHS = readPaths();

  static Stream<String> profiles() {
    return Stream.of(
      "fan_out",
      "manual_wheelchair",
      "sidewalks_whenever_possible",
      "unconstrained_pedestrian_(sidewalks_only)"
    );
  }

  @ParameterizedTest
  @MethodSource("profiles")
  void routesMatchR5(String profile) throws IOException {
    var planner = new Planner(profile);
    int compared = 0;
    for (JsonNode route : planner.routes) {
      String id = profile + " " + route.path("route").asInt();
      var walk = planner.plan(route);
      JsonNode r5 = route.path("r5");

      if (r5.isNull()) {
        boolean farFromNetwork =
          route.path("metersFromNetwork").asDouble() > MAX_METERS_FROM_NETWORK;
        if (!farFromNetwork && !LINKED_PAST_AN_UNUSABLE_EDGE.contains(id)) {
          assertWithMessage("%s has no route in R5", id).that(walk).isNull();
        }
        continue;
      }
      assertWithMessage("%s has a route in R5", id).that(walk).isNotNull();
      compared++;
      if (!R5_MISSES_A_SHORTER_PATH.contains(id)) {
        assertWithMessage("length of %s", id)
          .that(walk.distanceMeters())
          .isWithin(0.02 * r5.path("length").asDouble())
          .of(r5.path("length").asDouble());
      }
      // R5 rounds the time of every edge up to a whole second, so it is a little slower
      double r5Duration = r5.path("duration").asDouble();
      double duration = walk.totalDuration().toSeconds();
      assertWithMessage("duration of %s", id)
        .that(duration)
        .isAtMost(r5Duration + 1);
      assertWithMessage("duration of %s", id)
        .that(duration)
        .isAtLeast(0.95 * r5Duration);
    }
    assertThat(compared).isAtLeast(40);
  }

  @ParameterizedTest
  @MethodSource("profiles")
  void routesAgreeWithAccessMap(String profile) throws IOException {
    var planner = new Planner(profile);
    List<Double> lengthDifferences = new ArrayList<>();
    List<Double> durationDifferences = new ArrayList<>();
    for (JsonNode route : planner.routes) {
      String id = profile + " " + route.path("route").asInt();
      var walk = planner.plan(route);
      JsonNode accessMap = route.path("accessMap");

      if (accessMap.path("code").asText().equals("Ok")) {
        assertWithMessage("%s has a route in AccessMap", id).that(walk).isNotNull();
        double length = accessMap.path("length").asDouble();
        double duration = accessMap.path("duration").asDouble();
        lengthDifferences.add(Math.abs(walk.distanceMeters() - length) / length);
        durationDifferences.add(Math.abs(walk.totalDuration().toSeconds() - duration) / duration);
      }
    }
    assertThat(lengthDifferences.size()).isAtLeast(40);
    // No walk is far off, and nearly all of them are the same
    assertThat(max(lengthDifferences)).isLessThan(0.05);
    assertThat(shareWithin(lengthDifferences, 0.02)).isAtLeast(0.9);
    assertThat(shareWithin(durationDifferences, 0.05)).isAtLeast(0.85);
    assertThat(median(durationDifferences)).isLessThan(0.02);
  }

  /**
   * The lengths and durations above can agree for a walk along other streets. Here the walk has
   * to be along the very path R5 or AccessMap took, which {@code goldendale/paths.json} has.
   * Where the two took the same path, that is the only one. The few times they did not, either
   * will do.
   */
  @ParameterizedTest
  @MethodSource("profiles")
  void pathsFollowTheOnesOfR5AndAccessMap(String profile) throws IOException {
    var planner = new Planner(profile);
    int compared = 0;
    for (JsonNode route : planner.routes) {
      int number = route.path("route").asInt();
      List<LineString> references = new ArrayList<>();
      for (JsonNode path : PATHS) {
        if (path.path("route").asInt() == number && contains(path.path("profiles"), profile)) {
          references.add(projected(path.path("coordinates")));
        }
      }
      if (references.isEmpty()) {
        continue;
      }
      var walk = planner.plan(route);
      assertWithMessage("%s %s has a path", profile, number).that(walk).isNotNull();
      double offPath = references
        .stream()
        .mapToDouble(reference -> metersOffPath(walk, reference))
        .min()
        .orElseThrow();
      assertWithMessage("meters %s %s strays from the path of R5 or AccessMap", profile, number)
        .that(offPath)
        .isLessThan(MAX_METERS_OFF_PATH);
      compared++;
    }
    assertThat(compared).isAtLeast(40);
  }

  /** How far the walk gets from the reference path, looking at it every meter or so. */
  private static double metersOffPath(Itinerary walk, LineString reference) {
    double worst = 0;
    for (var leg : walk.legs()) {
      Coordinate[] path = projected(leg.legGeometry()).getCoordinates();
      for (int i = 1; i < path.length; i++) {
        var segment = new LineSegment(path[i - 1], path[i]);
        int steps = Math.max(1, (int) segment.getLength());
        for (int step = 0; step <= steps; step++) {
          var point = GEOMETRY_FACTORY.createPoint(segment.pointAlong((double) step / steps));
          worst = Math.max(worst, reference.distance(point));
        }
      }
    }
    return worst;
  }

  /** In meters, which is close enough over a small town. */
  private static LineString projected(LineString lonLat) {
    return GEOMETRY_FACTORY.createLineString(
      Arrays.stream(lonLat.getCoordinates())
        .map(c -> new Coordinate(c.x * METERS_PER_DEGREE_LON, c.y * METERS_PER_DEGREE_LAT))
        .toArray(Coordinate[]::new)
    );
  }

  private static LineString projected(JsonNode lonLat) {
    var coordinates = new ArrayList<Coordinate>();
    lonLat.forEach(c -> coordinates.add(new Coordinate(c.get(0).asDouble(), c.get(1).asDouble())));
    return projected(GEOMETRY_FACTORY.createLineString(coordinates.toArray(Coordinate[]::new)));
  }

  private static boolean contains(JsonNode values, String value) {
    for (JsonNode v : values) {
      if (v.asText().equals(value)) {
        return true;
      }
    }
    return false;
  }

  private static JsonNode readPaths() {
    try {
      return new ObjectMapper().readTree(RESOURCES.file("goldendale/paths.json")).path("paths");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static double max(List<Double> values) {
    return values.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
  }

  private static double median(List<Double> values) {
    return values
      .stream()
      .sorted()
      .toList()
      .get(values.size() / 2);
  }

  private static double shareWithin(List<Double> values, double limit) {
    return (
      (double) values
        .stream()
        .filter(v -> v <= limit)
        .count() / values.size()
    );
  }

  /**
   * Plans walks over the dataset as a traveller with one of the profiles of the reports. The
   * travellers share one graph: who is walking is up to each request.
   */
  private static class Planner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Graph GRAPH = buildGraph();
    private static final TransitService TRANSIT_SERVICE = TestServerContext.createTransitService(
      new TransitRepository(),
      TransferServiceTestFactory.defaultTransferRepository()
    );

    private final JsonNode routes;
    private final PedestrianCostParameters traveller;

    Planner(String profileName) throws IOException {
      JsonNode profile = null;
      for (JsonNode p : MAPPER.readTree(RESOURCES.file("goldendale/routes.json")).path(
        "profiles"
      )) {
        if (p.path("name").asText().equals(profileName)) {
          profile = p;
        }
      }
      this.routes = profile.path("routes");
      Map<String, String> parameters = new HashMap<>();
      profile
        .path("parameters")
        .fields()
        .forEachRemaining(e -> parameters.put(e.getKey(), e.getValue().asText()));
      this.traveller = PedestrianCostParameters.of(parameters);
    }

    private static Graph buildGraph() {
      var graph = new Graph();
      new OswModule(
        graph,
        List.of(RESOURCES.file("goldendale/goldendale.osw.zip")),
        OswCosting.fromFile(RESOURCES.file("ws-prod.json"), Map.of())
      ).buildGraph();
      graph.index();
      return graph;
    }

    /** The walk between the two places of the route, or null if there is none. */
    @Nullable
    Itinerary plan(JsonNode route) {
      RouteRequest request = RouteRequest.of()
        .withDateTime(TIME)
        .withFrom(location(route.path("from")))
        .withTo(location(route.path("to")))
        .withPreferences(p ->
          p.withWalk(w -> w.withSpeed(WALK_SPEED)).withSystem(s -> s.withExtension(traveller))
        )
        .buildRequest();
      try (var temporaryVerticesContainer = new TemporaryVerticesContainer()) {
        var vertexCreationService = new VertexCreationService(VertexLinkerTestFactory.of(GRAPH));
        var linkingContext = new LinkingContextFactory(GRAPH, vertexCreationService).create(
          temporaryVerticesContainer,
          LinkingContextRequestMapper.map(request)
        );
        var itineraries = DirectStreetRouter.route(
          GRAPH,
          TRANSIT_SERVICE,
          TestServerContext.createStreetLimitationParametersService(),
          TestServerContext.createVehicleRentalService(),
          TestServerContext.createStreetDetailsService(),
          null,
          request,
          linkingContext
        );
        return itineraries.isEmpty() ? null : itineraries.getFirst();
      } catch (RoutingValidationException e) {
        // No route: the origin and the destination are not connected, or are the same place
        return null;
      }
    }

    private static GenericLocation location(JsonNode place) {
      return GenericLocation.fromCoordinate(
        place.path("lat").asDouble(),
        place.path("lon").asDouble()
      );
    }
  }
}

package org.opentripplanner.ext.opensidewalks;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opentripplanner.test.support.ResourceLoader;

/**
 * The expected identifiers and costs are those asserted by the R5 and Unweaver implementations of
 * pedestrian cost profiles, which keeps the three in agreement.
 */
class PedestrianCostProfileTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ResourceLoader RESOURCES = ResourceLoader.of(
    PedestrianCostProfileTest.class
  );

  private static final String PROFILE_ID = "6d87a93db06e4151686f7bd7b07ee2bd";
  private static final String RUN_SPEC_ID_DEFAULTS = "ef7e971e5b97d19665222263fc63b4e6";
  private static final String RUN_SPEC_ID_UPHILL_083 = "015efe75b5854214a521decc187d8330";
  private static final String RUN_SPEC_ID_NO_CURB_AVOIDANCE = "9b67b0e9d42998b48b5ad67ae19da156";

  private static final double SPEED = 1.3;
  private static final Map<String, String> SIDEWALK = Map.of(
    "highway",
    "footway",
    "footway",
    "sidewalk"
  );
  private static final Map<String, String> CROSSING = Map.of(
    "highway",
    "footway",
    "footway",
    "crossing"
  );

  static PedestrianCostProfile loadProfile() throws IOException {
    return PedestrianCostProfile.fromFile(RESOURCES.file("unweaver-dynamic.json"));
  }

  @Test
  void canonicalJsonIgnoresFormatting() throws IOException {
    var a = MAPPER.readTree(
      "{\"b\": [1.0, 0.10, -0.0, 1e-5, 30], \"a\": \"x\\\"\\n\", \"\u00e9\": true, \"c\": null}"
    );
    var b = MAPPER.readTree(
      "{\"\u00e9\":true,\"c\":null,\"a\":\"x\\\"\\n\",\"b\":[1,0.1,0,0.00001,3E1]}"
    );
    String expected = "{\"a\":\"x\\\"\\n\",\"b\":[1,0.1,0,0.00001,30],\"c\":null,\"\u00e9\":true}";
    assertEquals(expected, CanonicalJson.canonicalize(a));
    assertEquals(expected, CanonicalJson.canonicalize(b));
  }

  @Test
  void identifiersMatchTheOtherImplementations() throws IOException {
    var profile = loadProfile();
    assertEquals(PROFILE_ID, profile.profileId());
    assertEquals(RUN_SPEC_ID_DEFAULTS, profile.resolve(null).runSpecId());
    assertEquals(RUN_SPEC_ID_UPHILL_083, profile.resolve(Map.of("uphill", 0.083)).runSpecId());
    // Supplying a default explicitly does not change the run spec
    assertEquals(
      RUN_SPEC_ID_UPHILL_083,
      profile.resolve(Map.of("uphill", 0.083, "downhill", 0.1, "avoid_curbs", true)).runSpecId()
    );
    assertEquals(
      RUN_SPEC_ID_NO_CURB_AVOIDANCE,
      profile.resolve(Map.of("avoid_curbs", false)).runSpecId()
    );
  }

  @Test
  void parameterValuesMayBeGivenAsText() throws IOException {
    var profile = loadProfile();
    assertEquals(
      RUN_SPEC_ID_UPHILL_083,
      profile.resolve(Map.of("uphill", "0.083", "avoid_curbs", "true")).runSpecId()
    );
    assertEquals(
      RUN_SPEC_ID_NO_CURB_AVOIDANCE,
      profile.resolve(Map.of("avoid_curbs", "false")).runSpecId()
    );
  }

  @Test
  void anyChangeToTheDefinitionChangesTheProfileId() throws IOException {
    var source = MAPPER.readTree(RESOURCES.file("unweaver-dynamic.json"));
    var changed = source.deepCopy();
    ((ObjectNode) changed.get("layers").get(1)).put("delaySeconds", 31);
    assertNotEquals(PROFILE_ID, new PedestrianCostProfile(changed).profileId());
    // Reformatting does not
    String reformatted = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(source);
    assertEquals(PROFILE_ID, PedestrianCostProfile.fromJson(reformatted).profileId());
  }

  @Test
  void sidewalksSlowDownTowardsTheInclineLimits() throws IOException {
    var spec = loadProfile().resolve(null);

    // At the ideal grade of Tobler's hiking function, full speed
    var ideal = spec.evaluate(SIDEWALK, -0.0087, 100, CurbRamps.UNKNOWN);
    assertEquals(76.92307692307692, ideal.seconds(100, SPEED), 1e-9);

    // At the uphill limit, a fifth of it
    var limit = spec.evaluate(SIDEWALK, 0.085, 100, CurbRamps.UNKNOWN);
    assertEquals(0.2, limit.speedFactor(), 1e-12);
    assertEquals(384.6153846153846, limit.seconds(100, SPEED), 1e-9);

    // Beyond the limits: impassable, unless the edge is 3 m or shorter
    assertTrue(spec.evaluate(SIDEWALK, 0.09, 100, CurbRamps.UNKNOWN).isImpassable());
    assertTrue(spec.evaluate(SIDEWALK, -0.11, 100, CurbRamps.UNKNOWN).isImpassable());
    assertFalse(spec.evaluate(SIDEWALK, 0.2, 3, CurbRamps.UNKNOWN).isImpassable());
  }

  @Test
  void anEdgeWithoutInclineIsWalkedAtPlainSpeed() throws IOException {
    var spec = loadProfile().resolve(null);
    assertEquals(1.0, spec.evaluate(SIDEWALK, Double.NaN, 100, CurbRamps.UNKNOWN).speedFactor());
    // That is not the same as flat: level ground is a little slower than the ideal grade
    assertThat(spec.evaluate(SIDEWALK, 0, 100, CurbRamps.UNKNOWN).speedFactor()).isLessThan(1.0);
  }

  @Test
  void crossingsAddADelayAndMayRequireCurbRamps() throws IOException {
    var spec = loadProfile().resolve(null);
    var ramps = spec.evaluate(CROSSING, Double.NaN, 20, CurbRamps.YES);
    assertEquals(45.38461538461539, ramps.seconds(20, SPEED), 1e-9);
    assertTrue(spec.evaluate(CROSSING, Double.NaN, 20, CurbRamps.UNKNOWN).isImpassable());
    assertTrue(spec.evaluate(CROSSING, Double.NaN, 20, CurbRamps.NO).isImpassable());

    var noCurbAvoidance = loadProfile().resolve(Map.of("avoid_curbs", false));
    assertEquals(
      30,
      noCurbAvoidance.evaluate(CROSSING, Double.NaN, 20, CurbRamps.NO).delaySeconds()
    );
  }

  @Test
  void edgesMatchingNoLayerFollowOtherEdges() throws IOException {
    var road = Map.of("highway", "residential");
    assertTrue(loadProfile().resolve(null).evaluate(road, 0, 50, CurbRamps.UNKNOWN).isImpassable());

    var walk = PedestrianCostProfile.fromJson("{\"otherEdges\": \"walk\"}").resolve(null);
    assertEquals(50 / SPEED, walk.evaluate(road, 0, 50, CurbRamps.UNKNOWN).seconds(50, SPEED));
  }

  @Test
  void theProfileOfTheWalkshedsServiceCanBeUsed() throws IOException {
    var profile = PedestrianCostProfile.fromFile(RESOURCES.file("ws-prod.json"));
    var road = Map.of("highway", "residential");

    // Streets are closed unless the traveller accepts walking in them
    assertTrue(profile.resolve(null).evaluate(road, 0.0, 50, CurbRamps.UNKNOWN).isImpassable());
    var streets = profile.resolve(Map.of("street_avoidance", 0.5));
    var flatSidewalk = streets.evaluate(SIDEWALK, 0.0, 50, CurbRamps.UNKNOWN);
    assertEquals(
      flatSidewalk.seconds(50, SPEED) * Math.exp(1.5),
      streets.evaluate(road, 0.0, 50, CurbRamps.UNKNOWN).seconds(50, SPEED),
      1e-9
    );
    assertEquals("crossings", streets.layerName(CROSSING));
  }

  @Test
  void theRangeOfASliderIsTheRangeOfTheParameter() throws IOException {
    var profile = PedestrianCostProfile.fromFile(RESOURCES.file("ws-prod.json"));
    // The uphill limit can be anything from 0 to 1, but its slider goes from 4% to 15%
    profile.resolve(Map.of("uphill", 0.04));
    assertThrows(IllegalArgumentException.class, () -> profile.resolve(Map.of("uphill", 0.03)));
    assertThrows(IllegalArgumentException.class, () -> profile.resolve(Map.of("uphill", 0.16)));
  }

  @Test
  void theExtremeTravellersAreAtTheEndsOfTheRanges() throws IOException {
    var extremes = loadProfile().extremes();
    // Two numbers with a range and a switch
    assertThat(extremes).hasSize(8);

    // A sidewalk that is 9% uphill is passable for the ones who can manage any grade
    long usableBy = extremes
      .stream()
      .filter(t -> !t.evaluate(SIDEWALK, 0.09, 100, CurbRamps.UNKNOWN).isImpassable())
      .count();
    assertEquals(4, usableBy);
    // A crossing without curb ramps for the ones who do not avoid curbs
    usableBy = extremes
      .stream()
      .filter(t -> !t.evaluate(CROSSING, Double.NaN, 20, CurbRamps.NO).isImpassable())
      .count();
    assertEquals(4, usableBy);
  }

  @Test
  void invalidParametersAreRejected() throws IOException {
    var profile = loadProfile();
    assertThrows(IllegalArgumentException.class, () -> profile.resolve(Map.of("uphil", 0.1)));
    assertThrows(IllegalArgumentException.class, () -> profile.resolve(Map.of("uphill", 2)));
    assertThrows(IllegalArgumentException.class, () ->
      profile.resolve(Map.of("avoid_curbs", "yes"))
    );
    assertThrows(IllegalArgumentException.class, () -> profile.resolve(Map.of("uphill", "steep")));
  }

  @Test
  void layersCanBeBlockedOrAvoided() throws IOException {
    var profile = PedestrianCostProfile.fromJson(
      """
      {
        "parameters": {
          "avoid": {"type": "number", "default": 0.5},
          "no_steps": {"type": "boolean", "default": false},
          "fan": {"type": "boolean", "default": false}
        },
        "layers": [
          {"name": "steps", "match": {"highway": "steps"}, "speedFactor": 0.5,
           "blocked": "$no_steps"},
          {"name": "streets", "match": {"highway": "residential"}, "delaySeconds": 10,
           "avoidance": {"amount": "$avoid", "k": 3, "unless": "$fan"}}
        ]
      }
      """
    );
    var steps = Map.of("highway", "steps");
    var street = Map.of("highway", "residential");

    assertEquals(100 / (SPEED * 0.5), cost(profile.resolve(null), steps).seconds(100, SPEED), 1e-9);
    assertTrue(cost(profile.resolve(Map.of("no_steps", true)), steps).isImpassable());

    // The whole cost, delay included, is multiplied by exp(k * amount)
    assertEquals(
      (100 / SPEED + 10) * Math.exp(1.5),
      cost(profile.resolve(null), street).seconds(100, SPEED),
      1e-9
    );
    assertEquals(
      100 / SPEED + 10,
      cost(profile.resolve(Map.of("avoid", 0)), street).seconds(100, SPEED),
      1e-9
    );
    // At an amount of 1 the layer is impassable
    assertTrue(cost(profile.resolve(Map.of("avoid", 1)), street).isImpassable());
    // "unless" switches the avoidance off altogether, even at 1
    assertEquals(
      100 / SPEED + 10,
      cost(profile.resolve(Map.of("avoid", 1, "fan", true)), street).seconds(100, SPEED),
      1e-9
    );
  }

  @Test
  void misspelledReservedAndWronglyTypedSettingsAreRejected() {
    assertInvalid("{\"layers\": [{\"name\": \"x\", \"delaySecs\": 30}]}");
    assertInvalid("{\"otherEdges\": \"maybe\"}");
    assertInvalid("{\"layers\": [{\"name\": \"x\", \"match\": {\"curbramps\": \"1\"}}]}");
    assertInvalid("{\"layers\": [{\"name\": \"x\", \"avoidance\": {\"k\": 2}}]}");
    assertInvalid("{\"layers\": [{\"name\": \"x\", \"requireCurbRamps\": \"false\"}]}");
    assertInvalid("{\"layers\": [{\"name\": \"x\", \"delaySeconds\": \"30\"}]}");
    assertInvalid("{\"layers\": [{\"name\": \"x\", \"blocked\": \"yes\"}]}");
  }

  @Test
  void curbRampsComeFromAnExplicitValueOrElseTheKerbs() {
    assertEquals(CurbRamps.YES, CurbRamps.parse("1.0"));
    assertEquals(CurbRamps.YES, CurbRamps.parse("true"));
    assertEquals(CurbRamps.NO, CurbRamps.parse("0"));
    assertEquals(CurbRamps.UNKNOWN, CurbRamps.parse("maybe"));

    assertEquals(CurbRamps.YES, CurbRamps.of(null, "lowered", "flush"));
    assertEquals(CurbRamps.YES, CurbRamps.of(null, null, null));
    assertEquals(CurbRamps.NO, CurbRamps.of(null, "lowered", "raised"));
    assertEquals(CurbRamps.NO, CurbRamps.of(null, "unknown", null));
    assertEquals(CurbRamps.YES, CurbRamps.of("yes", "raised", "raised"));
    assertEquals(CurbRamps.NO, CurbRamps.of("no", "lowered", "lowered"));
  }

  private static PedestrianCostSpec.EdgeCost cost(
    PedestrianCostSpec spec,
    Map<String, String> tags
  ) {
    return spec.evaluate(tags, Double.NaN, 100, CurbRamps.UNKNOWN);
  }

  /** A profile may be invalid as it is, or only once its settings are resolved. */
  private static void assertInvalid(String json) {
    assertThrows(IllegalArgumentException.class, () ->
      PedestrianCostProfile.fromJson(json).resolve(null)
    );
  }
}

package org.opentripplanner.ext.opensidewalks;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opentripplanner.test.support.ResourceLoader;

class OswTravellersTest {

  private static final ResourceLoader RESOURCES = ResourceLoader.of(OswTravellersTest.class);

  private final PedestrianCostProfile profile = profile("ws-prod.json");
  private final OswTravellers travellers = new OswTravellers(profile);
  private final int sidewalks = profile.layerIndex(
    Map.of("highway", "footway", "footway", "sidewalk")
  );
  private final int crossings = profile.layerIndex(
    Map.of("highway", "footway", "footway", "crossing")
  );
  private final int streets = profile.layerIndex(Map.of("highway", "residential"));

  @Test
  void theLeastAbleUserOfASteepSidewalkCanJustManageItsGrade() {
    var sidewalk = new OswEdgeAttributes(sidewalks, 0.1, 50, CurbRamps.YES, false);

    var leastAble = travellers.leastAbleUsers(sidewalk);

    assertThat(leastAble).hasSize(1);
    var values = leastAble.getFirst().values();
    assertEquals(0.1, Double.parseDouble(values.get("uphill")), 1e-6);
    // Nothing else is asked of them: no downhill, and they may avoid curbs and streets
    assertEquals(0.04, Double.parseDouble(values.get("downhill")), 1e-6);
    assertEquals("true", values.get("avoid_curbs"));
    assertEquals("false", values.get("fan_out"));

    assertTrue(travellers.canUse(leastAble.getFirst(), sidewalk));
    assertFalse(
      travellers.canUse(
        leastAble.getFirst(),
        new OswEdgeAttributes(sidewalks, 0.11, 50, CurbRamps.YES, false)
      )
    );
  }

  @Test
  void aCrossingWithoutCurbRampsAsksForNotAvoidingCurbs() {
    var crossing = new OswEdgeAttributes(crossings, Double.NaN, 15, CurbRamps.NO, false);

    var leastAble = travellers.leastAbleUsers(crossing);

    assertThat(leastAble).hasSize(1);
    assertEquals("false", leastAble.getFirst().values().get("avoid_curbs"));
    assertEquals(0.04, Double.parseDouble(leastAble.getFirst().values().get("uphill")), 1e-6);
  }

  @Test
  void aStreetCanBeUsedInTwoWays() {
    var street = new OswEdgeAttributes(streets, Double.NaN, 50, CurbRamps.YES, false);

    var leastAble = travellers.leastAbleUsers(street);

    // By avoiding streets a little less than altogether, or by turning fan mode on
    assertThat(leastAble).hasSize(2);
    assertThat(
      leastAble
        .stream()
        .map(t -> t.values().get("fan_out"))
        .toList()
    ).containsExactly("true", "false");
    for (var traveller : leastAble) {
      assertTrue(travellers.canUse(traveller, street));
    }
  }

  @Test
  void anEdgeForEveryoneAsksForNothing() {
    var level = new OswEdgeAttributes(sidewalks, 0.0, 50, CurbRamps.YES, true);

    var leastAble = travellers.leastAbleUsers(level);

    assertThat(leastAble).hasSize(1);
    assertEquals(0.04, Double.parseDouble(leastAble.getFirst().values().get("uphill")), 1e-6);
    assertEquals("true", leastAble.getFirst().values().get("avoid_curbs"));
  }

  @Test
  void anEdgeNobodyCanUseHasNoUsers() {
    var tooSteep = new OswEdgeAttributes(sidewalks, 0.3, 50, CurbRamps.YES, false);
    assertThat(travellers.leastAbleUsers(tooSteep)).isEmpty();
  }

  @Test
  void theExtremeTravellersOnlyDifferInWhatDecidesWhetherAnEdgeCanBeUsed() {
    // All five parameters of this profile can close an edge
    assertThat(profile.extremeValues()).hasSize(32);
    // Here two limits and a switch can. A profile could have a parameter for a delay as well,
    // and that would not add to them.
    assertThat(profile("unweaver-dynamic.json").extremeValues()).hasSize(8);
  }

  private static PedestrianCostProfile profile(String file) {
    try {
      return PedestrianCostProfile.fromFile(RESOURCES.file(file));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}

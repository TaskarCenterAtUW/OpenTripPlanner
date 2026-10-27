package org.opentripplanner.ext.opensidewalks;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opentripplanner.street.model.StreetTraversalPermission;

class OswRoadsTest {

  @Test
  void roadsCanBeUsedByEveryoneAndTheRestOnlyWalked() {
    assertEquals(
      StreetTraversalPermission.ALL,
      OswRoads.permission(Map.of("highway", "residential"))
    );
    assertEquals(StreetTraversalPermission.ALL, OswRoads.permission(Map.of("highway", "service")));
    assertEquals(
      StreetTraversalPermission.PEDESTRIAN,
      OswRoads.permission(Map.of("highway", "footway", "footway", "sidewalk"))
    );
    assertEquals(
      StreetTraversalPermission.PEDESTRIAN,
      OswRoads.permission(Map.of("highway", "pedestrian"))
    );
    assertEquals(StreetTraversalPermission.PEDESTRIAN, OswRoads.permission(Map.of()));
  }

  @Test
  void aCarGoesAtTheMaxspeedOfTheRoad() {
    assertThat((double) OswRoads.carSpeed(Map.of("highway", "primary", "ext:maxspeed", "40 mph")))
      .isWithin(0.01)
      .of(17.88);
    assertThat((double) OswRoads.carSpeed(Map.of("highway", "primary", "ext:maxspeed", "50")))
      .isWithin(0.01)
      .of(13.89);
    assertThat((double) OswRoads.carSpeed(Map.of("highway", "primary", "ext:maxspeed", "30 km/h")))
      .isWithin(0.01)
      .of(8.33);
  }

  @Test
  void orElseAtTheDefaultForTheKindOfRoad() {
    assertEquals(20f, OswRoads.carSpeed(Map.of("highway", "primary")));
    assertEquals(6.7f, OswRoads.carSpeed(Map.of("highway", "service")));
    assertEquals(6.7f, OswRoads.carSpeed(Map.of("highway", "service", "ext:maxspeed", "walk")));
  }
}

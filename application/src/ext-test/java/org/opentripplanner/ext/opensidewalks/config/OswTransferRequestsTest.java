package org.opentripplanner.ext.opensidewalks.config;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opentripplanner.ext.opensidewalks.PedestrianCostParameters;
import org.opentripplanner.framework.application.OTPFeature;
import org.opentripplanner.graph_builder.module.transfer.api.RegularTransferParameters;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.test.support.ResourceLoader;

class OswTransferRequestsTest {

  private static final File DIRECTORY = ResourceLoader.of(OswTransferRequestsTest.class)
    .file("../unweaver-dynamic.json")
    .getParentFile();

  private final RegularTransferParameters configured = RegularTransferParameters.of()
    .withMaxDuration(Duration.ofMinutes(20))
    .withRequests(List.of(RouteRequest.defaultValue()))
    .build();

  @Test
  void aRequestIsAddedForEachOfTheExtremeTravellers() {
    OTPFeature.OpenSidewalks.testOn(() -> {
      var result = OswTransferRequests.withTravellers(configured, config(true), DIRECTORY);

      // The request that was there, and two limits and a switch at either end
      assertThat(result.requests()).hasSize(9);
      assertEquals(RouteRequest.defaultValue(), result.requests().getFirst());
      assertEquals(Duration.ofMinutes(20), result.maxDuration());
      var travellers = result
        .requests()
        .stream()
        .skip(1)
        .map(r -> (PedestrianCostParameters) r.preferences().system().extensions().getFirst())
        .toList();
      assertThat(travellers).containsNoDuplicates();
      assertThat(travellers).contains(
        PedestrianCostParameters.of(
          Map.of("uphill", "0.0", "downhill", "0.0", "avoid_curbs", "true")
        )
      );
    });
  }

  @Test
  void nothingIsAddedWhenItIsTurnedOffOrThereIsNoProfile() {
    OTPFeature.OpenSidewalks.testOn(() -> {
      assertSame(
        configured,
        OswTransferRequests.withTravellers(configured, config(false), DIRECTORY)
      );
      var noProfile = new OpenSidewalksConfig(List.of("tiny"), null, Map.of(), true);
      assertSame(configured, OswTransferRequests.withTravellers(configured, noProfile, DIRECTORY));
    });
    OTPFeature.OpenSidewalks.testOff(() ->
      assertSame(
        configured,
        OswTransferRequests.withTravellers(configured, config(true), DIRECTORY)
      )
    );
  }

  private static OpenSidewalksConfig config(boolean findTransfersForAllTravellers) {
    return new OpenSidewalksConfig(
      List.of("tiny"),
      "unweaver-dynamic.json",
      Map.of(),
      findTransfersForAllTravellers
    );
  }
}

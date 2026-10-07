package org.opentripplanner.ext.opensidewalks;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.opentripplanner.framework.application.OTPFeature;
import org.opentripplanner.graph_builder.module.transfer.api.RegularTransferParameters;
import org.opentripplanner.routing.algorithm.GraphRoutingTest;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.street.model.StreetMode;
import org.opentripplanner.street.model.vertex.StreetVertex;
import org.opentripplanner.street.model.vertex.TransitStopVertex;
import org.opentripplanner.street.model.vertex.VertexLabel;
import org.opentripplanner.street.search.request.StreetSearchRequest;
import org.opentripplanner.test.support.ResourceLoader;
import org.opentripplanner.transfer.regular.TransferRepository;
import org.opentripplanner.transfer.regular.TransferServiceTestFactory;
import org.opentripplanner.transfer.regular.model.PathTransfer;

/**
 * Stop A is at node 2 of the small synthetic dataset and stop B at node 3, at the ends of a
 * sidewalk that is 100 m long and 10% steep. The only other way between them is over the street
 * and back, 140 m with a crossing that has a raised kerb.
 */
class OswTransferModuleTest extends GraphRoutingTest {

  private static final ResourceLoader RESOURCES = ResourceLoader.of(OswTransferModuleTest.class);
  private static final double SPEED = 1.3;

  private TransitStopVertex stopA;
  private TransitStopVertex stopB;

  private TransferRepository findTransfers() {
    var model = modelOf(
      new Builder() {
        @Override
        public void build() {
          new OswModule(
            graph(),
            List.of(RESOURCES.file("tiny")),
            OswCosting.fromFile(RESOURCES.file("unweaver-dynamic.json"), Map.of())
          ).buildGraph();
          stopA = stop("A", 47.65, -122.2986665);
          stopB = stop("B", 47.65, -122.297333);
          biLink(node("2"), stopA);
          biLink(node("3"), stopB);
        }

        private StreetVertex node(String id) {
          return (StreetVertex) graph().getVertex(VertexLabel.string("osw:" + id));
        }
      }
    );
    var transfers = TransferServiceTestFactory.defaultTransferRepository();
    var parameters = RegularTransferParameters.of()
      .withMaxDuration(Duration.ofMinutes(30))
      .withRequests(List.of(RouteRequest.defaultValue()))
      .build();
    // The stops of this test have no trips, which would leave nothing to transfer to
    OTPFeature.ConsiderPatternsForDirectTransfers.testOff(() ->
      new OswTransferModule(
        model.graph(),
        model.transitRepository(),
        transfers,
        parameters
      ).buildGraph()
    );
    return transfers;
  }

  @Test
  void aPathIsFoundForTheTravellersWhoCanNotUseTheFirst() {
    var transfers = findTransfers();

    var uphill = fromAToB(transfers);
    assertThat(uphill).hasSize(2);
    assertEquals(99.89, uphill.get(0).getDistanceMeters(), 0.5);
    assertEquals(140, uphill.get(1).getDistanceMeters(), 1);
    // Down the sidewalk and round the other way
    assertThat(
      transfers
        .listPathTransfers()
        .stream()
        .filter(t -> t.from.getId().getId().equals("B"))
        .toList()
    ).hasSize(2);
  }

  @Test
  void aTravellerIsGivenThePathTheyCanUse() {
    var uphill = fromAToB(findTransfers());
    var steep = uphill.get(0);
    var wayRound = uphill.get(1);

    // Strong enough for the sidewalk, and avoiding curbs
    var strong = request(PedestrianCostParameters.of(Map.of("uphill", "0.12")));
    assertThat(steep.asRaptorTransfer(strong)).isPresent();
    assertThat(wayRound.asRaptorTransfer(strong)).isEmpty();

    // Not strong enough, but fine with a raised kerb
    var curbs = request(PedestrianCostParameters.of(Map.of("avoid_curbs", "false")));
    assertThat(steep.asRaptorTransfer(curbs)).isEmpty();
    assertThat(wayRound.asRaptorTransfer(curbs)).isPresent();

    // Neither: there is no way between the two stops
    assertThat(steep.asRaptorTransfer(request(null))).isEmpty();
    assertThat(wayRound.asRaptorTransfer(request(null))).isEmpty();
  }

  @Test
  void theSearchEndsWhenNothingNewIsFound() {
    var transfers = findTransfers();
    // Each of the four paths is found once, however many travellers searched
    assertThat(transfers.listPathTransfers()).hasSize(4);
  }

  private static List<PathTransfer> fromAToB(TransferRepository transfers) {
    return transfers
      .listPathTransfers()
      .stream()
      .filter(t -> t.from.getId().getId().equals("A") && t.to.getId().getId().equals("B"))
      .sorted(Comparator.comparingDouble(PathTransfer::getDistanceMeters))
      .toList();
  }

  private static StreetSearchRequest request(@Nullable PedestrianCostParameters traveller) {
    var builder = StreetSearchRequest.of()
      .withMode(StreetMode.WALK)
      .withStartTime(Instant.EPOCH)
      .withWalk(w -> w.withSpeed(SPEED));
    if (traveller != null) {
      builder.withExtensionRequestContexts(List.of(traveller));
    }
    return builder.build();
  }
}

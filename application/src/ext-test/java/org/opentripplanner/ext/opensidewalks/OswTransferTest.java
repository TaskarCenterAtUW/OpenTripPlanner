package org.opentripplanner.ext.opensidewalks;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.opentripplanner.core.model.id.FeedScopedIdForTestFactory.id;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.model.StreetMode;
import org.opentripplanner.street.model.edge.Edge;
import org.opentripplanner.street.model.vertex.VertexLabel;
import org.opentripplanner.street.search.request.StreetSearchRequest;
import org.opentripplanner.test.support.ResourceLoader;
import org.opentripplanner.transfer.regular.model.PathTransfer;
import org.opentripplanner.transit.model.site.RegularStop;

/**
 * OTP finds the paths of the transfers between stops when the graph is built, and walks them
 * again for each kind of request to see what they cost. On OpenSidewalks edges that is what they
 * cost the traveller of the request, if that traveller can use them at all.
 */
class OswTransferTest {

  private static final ResourceLoader RESOURCES = ResourceLoader.of(OswTransferTest.class);
  private static final double SPEED = 1.3;

  private final Graph graph = new Graph();
  private final RegularStop stopA = RegularStop.of(id("A"), () -> 1).build();
  private final RegularStop stopB = RegularStop.of(id("B"), () -> 2).build();

  {
    new OswModule(
      graph,
      List.of(RESOURCES.file("tiny")),
      OswCosting.fromFile(RESOURCES.file("unweaver-dynamic.json"), Map.of())
    ).buildGraph();
  }

  @Test
  void aTransferIsDroppedForATravellerWhoCanNotUseItsPath() {
    // Over the crossing with a raised kerb
    var transfer = transfer(edge("31", "32"));

    assertThat(transfer.asRaptorTransfer(request(null))).isEmpty();
    var withoutAvoidingCurbs = transfer.asRaptorTransfer(
      request(PedestrianCostParameters.of(Map.of("avoid_curbs", "false")))
    );
    // The length of the crossing at walking speed, and the 30 s to wait
    assertEquals(
      (int) Math.ceil(15.98 / SPEED + 30),
      withoutAvoidingCurbs.orElseThrow().durationInSeconds()
    );
  }

  @Test
  void aTransferTakesTheTimeItTakesTheTraveller() {
    // Along the sidewalk that is 3% uphill
    var transfer = transfer(edge("1", "2"));

    int byDefault = transfer.asRaptorTransfer(request(null)).orElseThrow().durationInSeconds();
    int forTheStrong = transfer
      .asRaptorTransfer(request(PedestrianCostParameters.of(Map.of("uphill", "0.5"))))
      .orElseThrow()
      .durationInSeconds();

    assertThat(forTheStrong).isLessThan(byDefault);
  }

  private PathTransfer transfer(Edge... path) {
    return new PathTransfer(stopA, stopB, 100, List.of(path), EnumSet.of(StreetMode.WALK));
  }

  private Edge edge(String fromNodeId, String toNodeId) {
    var to = graph.getVertex(VertexLabel.string("osw:" + toNodeId));
    return graph
      .getVertex(VertexLabel.string("osw:" + fromNodeId))
      .getOutgoing()
      .stream()
      .filter(e -> e.getToVertex() == to)
      .findFirst()
      .orElseThrow();
  }

  private static StreetSearchRequest request(@Nullable PedestrianCostParameters traveller) {
    var builder = StreetSearchRequest.of()
      .withMode(StreetMode.WALK)
      // Transfers are walked from the start of time, to get how long they take
      .withStartTime(Instant.EPOCH)
      .withWalk(w -> w.withSpeed(SPEED));
    if (traveller != null) {
      builder.withExtensionRequestContexts(List.of(traveller));
    }
    return builder.build();
  }
}

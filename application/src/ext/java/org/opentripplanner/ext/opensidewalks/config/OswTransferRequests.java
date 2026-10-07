package org.opentripplanner.ext.opensidewalks.config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.opentripplanner.ext.opensidewalks.OswCosting;
import org.opentripplanner.ext.opensidewalks.PedestrianCostParameters;
import org.opentripplanner.framework.application.OTPFeature;
import org.opentripplanner.graph_builder.module.transfer.api.RegularTransferParameters;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.street.model.StreetMode;
import org.opentripplanner.street.model.edge.ExtensionRequestContext;

/**
 * Adds transfer requests for the travellers at the ends of the ranges of the parameters of the
 * pedestrian cost profile.
 * <p>
 * OTP finds a path between stops for each transfer request when the graph is built, keeps the
 * different paths side by side, and walks them again as the traveller of each request to pick
 * one. A traveller who can use none of the paths has no transfer, also when there is another way.
 * The path found for the least able traveller who can transfer at all is one every traveller can
 * use, and that traveller is at one of the ends.
 */
public class OswTransferRequests {

  /**
   * @param baseDirectory the directory the path of the profile is resolved against
   */
  public static RegularTransferParameters withTravellers(
    RegularTransferParameters parameters,
    OpenSidewalksConfig config,
    File baseDirectory
  ) {
    if (
      OTPFeature.OpenSidewalks.isOff() ||
      config.profile() == null ||
      !config.findTransfersForAllTravellers()
    ) {
      return parameters;
    }
    File file = new File(config.profile());
    var costing = OswCosting.fromFile(
      file.isAbsolute() ? file : new File(baseDirectory, config.profile()),
      config.parameters()
    );

    List<RouteRequest> requests = new ArrayList<>(parameters.requests());
    // The travellers walk like the first walking request does, apart from what they can manage
    RouteRequest walk = requests
      .stream()
      .filter(it -> it.journey().transfer().mode() == StreetMode.WALK)
      .findFirst()
      .orElse(RouteRequest.defaultValue());
    for (var values : costing.profile().extremeValues()) {
      ExtensionRequestContext traveller = PedestrianCostParameters.of(values);
      var request = walk
        .copyOf()
        .withPreferences(p -> p.withSystem(s -> s.withExtension(traveller)))
        .buildDefault();
      if (!requests.contains(request)) {
        requests.add(request);
      }
    }
    return RegularTransferParameters.of()
      .withMaxDuration(parameters.maxDuration())
      .withParametersForMode(parameters.parametersForMode())
      .withRequests(requests)
      .build();
  }
}

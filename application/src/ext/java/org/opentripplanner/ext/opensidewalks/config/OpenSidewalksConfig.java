package org.opentripplanner.ext.opensidewalks.config;

import static org.opentripplanner.standalone.config.framework.json.OtpVersion.V2_11;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import org.opentripplanner.ext.opensidewalks.PedestrianCostParameters;
import org.opentripplanner.standalone.config.framework.json.NodeAdapter;

/**
 * The build configuration of the OpenSidewalks sandbox feature.
 *
 * @param sources    the datasets to build pedestrian street edges from
 * @param profile    the file with the pedestrian cost profile to cost the edges with, or
 *                   {@code null} to leave the cost of walking them to OTP
 * @param parameters the values of the parameters of the profile, by name
 * @param findTransfersForAllTravellers whether to find the paths of transfers between stops for
 *                   the travellers at the ends of the parameter ranges, besides the transfer
 *                   requests of the build configuration
 */
public record OpenSidewalksConfig(
  List<String> sources,
  @Nullable String profile,
  Map<String, String> parameters,
  boolean findTransfersForAllTravellers
) {
  public static OpenSidewalksConfig fromConfig(NodeAdapter root, String parameterName) {
    var c = root
      .of(parameterName)
      .since(V2_11)
      .summary("Build the pedestrian street network from OpenSidewalks datasets.")
      .description(
        """
        Each edge of the datasets becomes a street that can only be walked. With a `profile`, the
        edges are costed by that pedestrian cost profile for the traveller of each request. See the [OpenSidewalks sandbox documentation](sandbox/OpenSidewalks.md).
        """
      )
      .asObject();

    return new OpenSidewalksConfig(
      c
        .of("sources")
        .since(V2_11)
        .summary("The OpenSidewalks datasets to load.")
        .description(
          """
          Each is the path of a zip file or a directory containing `*.nodes.geojson` and
          `*.edges.geojson`, or of an `*.edges.geojson` file. A relative path is resolved against
          the OTP base directory.
          """
        )
        .asStringList(List.of()),
      c
        .of("profile")
        .since(V2_11)
        .summary("The path of the pedestrian cost profile to cost the edges with.")
        .description(
          """
          A JSON file in the pedestrian cost profile format. A relative path is resolved against
          the OTP base directory. Without a profile every edge can be walked, at the cost OTP
          gives any other street.
          """
        )
        .asString(null),
      c
        .of("parameters")
        .since(V2_11)
        .summary("The values of the parameters of the profile.")
        .description(
          """
          For example `{ "uphill": 0.083, "avoid_curbs": true }`. These are the values a request
          has for the parameters it gives no value itself. A parameter that is left out here has
          the default value the profile gives it.
          """
        )
        .asStringMap(),
      c
        .of("findTransfersForAllTravellers")
        .since(V2_11)
        .summary("Find the paths of transfers between stops for every kind of traveller.")
        .description(
          """
          Transfers are then also found for the travellers at the ends of the ranges of the
          parameters of the profile, so that every traveller who can transfer between two stops
          has a path to do so. Each of them takes as long as the transfers took to find before.
          Turn this off to find transfers only for the `transferRequests`.
          """
        )
        .asBoolean(true)
    );
  }

  /**
   * The parameter values a request has for the pedestrian cost profile, in the routing defaults
   * of the router configuration, if there are any.
   */
  public static Optional<PedestrianCostParameters> mapPedestrianCost(
    NodeAdapter c,
    String parameterName
  ) {
    // This is read whether the feature is on or not, since OTP reads the configuration files
    // before it turns the features on
    var values = c
      .of(parameterName)
      .since(V2_11)
      .summary("The values of the parameters of the OpenSidewalks pedestrian cost profile.")
      .description(
        """
        What the traveller can manage, for example `{ "uphill": 0.083, "avoid_curbs": true }`.
        A parameter that is left out has the value of the build configuration. See the
        [OpenSidewalks sandbox documentation](sandbox/OpenSidewalks.md).
        """
      )
      .asStringMap();
    return values.isEmpty() ? Optional.empty() : Optional.of(PedestrianCostParameters.of(values));
  }
}

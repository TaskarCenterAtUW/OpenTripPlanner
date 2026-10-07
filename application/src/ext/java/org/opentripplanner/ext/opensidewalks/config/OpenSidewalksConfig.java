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
 * @param walkOnOtherStreets whether people on foot can walk the streets that come from other
 *                   data than OpenSidewalks, such as OpenStreetMap
 */
public record OpenSidewalksConfig(
  List<String> sources,
  @Nullable String profile,
  Map<String, String> parameters,
  boolean walkOnOtherStreets
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
        .of("walkOnOtherStreets")
        .since(V2_11)
        .summary("Let people on foot walk the streets that come from other data, too.")
        .description(
          """
          The streets from OpenStreetMap data and the OpenSidewalks edges are not joined to each
          other. By default people on foot only walk the OpenSidewalks edges, and the other streets
          are for driving and for bicycles, ridden or walked. Set this to `true` to let people on
          foot walk the other streets as well, at the cost OTP gives them. A walk then stays on
          the kind of street that is nearest to where it starts.
          """
        )
        .asBoolean(false)
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

package org.opentripplanner.ext.opensidewalks.config;

import static org.opentripplanner.standalone.config.framework.json.OtpVersion.V2_11;

import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.opentripplanner.standalone.config.framework.json.NodeAdapter;

/**
 * The build configuration of the OpenSidewalks sandbox feature.
 *
 * @param sources    the datasets to build pedestrian street edges from
 * @param profile    the file with the pedestrian cost profile to cost the edges with, or
 *                   {@code null} to leave the cost of walking them to OTP
 * @param parameters the values of the parameters of the profile, by name
 */
public record OpenSidewalksConfig(
  List<String> sources,
  @Nullable String profile,
  Map<String, String> parameters
) {
  public static OpenSidewalksConfig fromConfig(NodeAdapter root, String parameterName) {
    var c = root
      .of(parameterName)
      .since(V2_11)
      .summary("Build the pedestrian street network from OpenSidewalks datasets.")
      .description(
        """
        Each edge of the datasets becomes a street that can only be walked. With a `profile`, the
        edges are costed by that pedestrian cost profile, and edges the profile makes impassable
        are left out. See the [OpenSidewalks sandbox documentation](sandbox/OpenSidewalks.md).
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
          For example `{ "uphill": 0.083, "avoid_curbs": true }`. A parameter that is left out
          has the default value the profile gives it.
          """
        )
        .asStringMap()
    );
  }
}

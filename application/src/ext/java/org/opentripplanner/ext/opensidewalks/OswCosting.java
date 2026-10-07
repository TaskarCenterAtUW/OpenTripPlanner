package org.opentripplanner.ext.opensidewalks;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * The pedestrian cost profile the OpenSidewalks edges of a graph are costed with. It is part of
 * the graph, shared by all those edges, and turns the parameter values of a request into the
 * cost function for the traveller of that request.
 */
public final class OswCosting implements Serializable {

  private final String profileJson;
  private final TreeMap<String, String> defaults;

  private transient volatile PedestrianCostProfile profile;

  /**
   * @param profileJson the definition of the profile
   * @param defaults    the parameter values of the build configuration, which a request has
   *                    for the parameters it gives no value
   */
  OswCosting(String profileJson, Map<String, String> defaults) {
    this.profileJson = profileJson;
    this.defaults = new TreeMap<>(defaults);
    // Fail when the graph is built, not on the first request
    spec(null);
  }

  /**
   * @throws IllegalArgumentException if the file is not a profile, or the values are not ones
   * its parameters can have.
   */
  public static OswCosting fromFile(File file, Map<String, String> defaults) {
    try {
      return new OswCosting(Files.readString(file.toPath()), defaults);
    } catch (IOException | IllegalArgumentException e) {
      throw new IllegalArgumentException(
        "Unable to use the pedestrian cost profile %s: %s".formatted(file, e.getMessage()),
        e
      );
    }
  }

  public PedestrianCostProfile profile() {
    var p = profile;
    if (p == null) {
      try {
        p = PedestrianCostProfile.fromJson(profileJson);
      } catch (IOException e) {
        throw new IllegalArgumentException("The profile is not JSON: " + e.getMessage(), e);
      }
      profile = p;
    }
    return p;
  }

  /**
   * The cost function for the traveller of a request.
   *
   * @param parameters the parameter values of the request, or {@code null} if it has none
   */
  public PedestrianCostSpec spec(@Nullable PedestrianCostParameters parameters) {
    var p = parameters == null ? PedestrianCostParameters.DEFAULT : parameters;
    return p.resolve(profile(), defaults);
  }
}

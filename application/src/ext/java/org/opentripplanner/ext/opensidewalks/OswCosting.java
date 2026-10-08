package org.opentripplanner.ext.opensidewalks;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.annotation.Nullable;
import org.opentripplanner.apis.support.InvalidInputException;
import org.opentripplanner.street.graph.Graph;

/**
 * The pedestrian cost profile the OpenSidewalks edges of a graph are costed with. It is part of
 * the graph, shared by all those edges, and turns the parameter values of a request into the
 * cost function for the traveller of that request.
 */
public final class OswCosting implements Serializable {

  private final String profileJson;
  private final TreeMap<String, String> defaults;

  /** Whether the graph has streets from other data that can be walked, too. */
  private boolean besideOtherStreets = false;

  private transient volatile PedestrianCostProfile profile;
  private transient volatile PedestrianCostSpec mostAble;

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

  /** The profile the OpenSidewalks edges of the graph are costed with, if it has such edges. */
  public static Optional<OswCosting> of(Graph graph) {
    for (var edge : graph.findEdges(OswStreetEdge.class)) {
      return Optional.of(edge.costing());
    }
    return Optional.empty();
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
   * Whether the graph has streets from other data that can be walked. Travellers the profile
   * makes no difference for then walk those, and the OpenSidewalks edges are for the others.
   */
  public boolean besideOtherStreets() {
    return besideOtherStreets;
  }

  void setBesideOtherStreets() {
    this.besideOtherStreets = true;
  }

  /**
   * Whether the traveller of a request walks the OpenSidewalks edges, and not the other streets
   * of the graph, if it has any. That is so if the values of the request make the profile cost
   * an edge differently than for the most able traveller: someone it closes nothing for that it
   * could leave open. A request without values walks the other streets.
   *
   * @param parameters the parameter values of the request, or {@code null} if it has none
   */
  public boolean walksOpenSidewalks(@Nullable PedestrianCostParameters parameters) {
    if (!besideOtherStreets) {
      return true;
    }
    if (parameters == null) {
      return false;
    }
    return (
      parameters.isOnOpenSidewalks() || parameters.differsFrom(mostAble(), profile(), defaults)
    );
  }

  private PedestrianCostSpec mostAble() {
    var spec = mostAble;
    if (spec == null) {
      Map<String, String> values = new TreeMap<>(defaults);
      values.putAll(new OswTravellers(profile()).mostAble());
      spec = profile().resolve(values);
      mostAble = spec;
    }
    return spec;
  }

  /**
   * The cost function for the traveller of a request.
   *
   * @param parameters the parameter values of the request, or {@code null} if it has none
   * @throws InvalidInputException if the profile has no such parameters, or the values are not
   * ones the parameters can have.
   */
  public PedestrianCostSpec spec(@Nullable PedestrianCostParameters parameters) {
    if (parameters == null) {
      return PedestrianCostParameters.DEFAULT.resolve(profile(), defaults);
    }
    try {
      return parameters.resolve(profile(), defaults);
    } catch (IllegalArgumentException e) {
      // The values of a request are checked here, where the request first meets the profile
      throw new InvalidInputException("Invalid pedestrian cost parameter. " + e.getMessage());
    }
  }
}

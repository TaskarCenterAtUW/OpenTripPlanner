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

  /** Whether the OpenSidewalks data has roads, which bicycles and cars can use. */
  private boolean hasRoads = false;

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
   * Whether the graph has streets from other data that can be walked. A request then travels on
   * one of the two, see {@link #usesOpenSidewalks}.
   */
  public boolean besideOtherStreets() {
    return besideOtherStreets;
  }

  void setBesideOtherStreets() {
    this.besideOtherStreets = true;
  }

  /** Whether the OpenSidewalks data has roads, which bicycles and cars can use. */
  public boolean hasRoads() {
    return hasRoads;
  }

  void setHasRoads() {
    this.hasRoads = true;
  }

  /**
   * Whether a request travels on the OpenSidewalks edges, and not on the other streets of the
   * graph, if it has any. That is so if the request says it does, see {@link StreetNetwork}. A
   * request that does not say travels on the other streets.
   *
   * @param parameters the parameter values of the request, or {@code null} if it has none
   */
  public boolean usesOpenSidewalks(@Nullable PedestrianCostParameters parameters) {
    if (!besideOtherStreets) {
      return true;
    }
    return parameters != null && parameters.isOnOpenSidewalks();
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

package org.opentripplanner.ext.opensidewalks;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.annotation.Nullable;
import org.opentripplanner.street.model.edge.ExtensionRequestContext;

/**
 * The values a request gives the parameters of the pedestrian cost profile: what the traveller
 * of this request can manage, such as the steepest uphill grade. A parameter the request has no
 * value for has the one of the build configuration, or else the default of the profile.
 * <p>
 * The values are text here. They are checked against the profile when the request first walks an
 * OpenSidewalks edge or is linked to one, since the profile is part of the graph.
 * <p>
 * THIS CLASS IS IMMUTABLE AND THREAD-SAFE
 */
public final class PedestrianCostParameters implements ExtensionRequestContext, Serializable {

  /** No values: the traveller the graph was built for. */
  public static final PedestrianCostParameters DEFAULT = new PedestrianCostParameters(Map.of());

  private final SortedMap<String, String> values;
  private final int hashCode;

  /** The cost function for these values, remembered for the profile it was last asked for. */
  @Nullable
  private transient volatile Resolved resolved;

  private PedestrianCostParameters(Map<String, String> values) {
    this.values = Collections.unmodifiableSortedMap(new TreeMap<>(values));
    this.hashCode = this.values.hashCode();
  }

  public static PedestrianCostParameters of(@Nullable Map<String, String> values) {
    return values == null || values.isEmpty() ? DEFAULT : new PedestrianCostParameters(values);
  }

  public SortedMap<String, String> values() {
    return values;
  }

  /**
   * @throws IllegalArgumentException if the profile has no such parameters, or the values are
   * not ones the parameters can have.
   */
  PedestrianCostSpec resolve(PedestrianCostProfile profile, Map<String, String> defaults) {
    var r = resolved;
    if (r == null || r.profile != profile) {
      Map<String, String> all = new TreeMap<>(defaults);
      all.putAll(values);
      r = new Resolved(profile, profile.resolve(all));
      resolved = r;
    }
    return r.spec;
  }

  @Override
  public boolean equals(Object o) {
    return (
      this == o || (o instanceof PedestrianCostParameters other && values.equals(other.values))
    );
  }

  @Override
  public int hashCode() {
    return hashCode;
  }

  @Override
  public String toString() {
    return "PedestrianCostParameters" + values;
  }

  private record Resolved(PedestrianCostProfile profile, PedestrianCostSpec spec) {}
}

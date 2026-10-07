package org.opentripplanner.ext.opensidewalks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A {@link PedestrianCostProfile} combined with specific parameter values: everything needed to
 * cost an edge.
 * <p>
 * Two identifiers say what produced a result:
 * <ul>
 *   <li>The profile id: which version of the cost function was used.</li>
 *   <li>The run spec id: which cost function and which parameter values. It is the md5 of the
 *   canonical {@code {"parameters": {...every value...}, "profileId": ...}}.</li>
 * </ul>
 */
public class PedestrianCostSpec {

  private final PedestrianCostProfile profile;
  private final Map<String, JsonNode> parameterValues;
  private final String runSpecId;
  private final List<ResolvedLayer> layers;

  PedestrianCostSpec(PedestrianCostProfile profile, Map<String, JsonNode> parameterValues) {
    this.profile = profile;
    this.parameterValues = Collections.unmodifiableMap(new LinkedHashMap<>(parameterValues));
    ObjectNode runSpec = JsonNodeFactory.instance.objectNode();
    runSpec.put("profileId", profile.profileId());
    ObjectNode params = runSpec.putObject("parameters");
    parameterValues.forEach(params::set);
    this.runSpecId = CanonicalJson.md5OfCanonical(runSpec);
    this.layers = profile
      .layers()
      .stream()
      .map(l -> new ResolvedLayer(l, parameterValues))
      .toList();
  }

  public PedestrianCostProfile profile() {
    return profile;
  }

  /** Identifies the cost function together with the parameter values. */
  public String runSpecId() {
    return runSpecId;
  }

  /** Every parameter's value, with defaults filled in, in the order declared by the profile. */
  public Map<String, JsonNode> parameterValues() {
    return parameterValues;
  }

  /**
   * Evaluate the cost function for one directed edge.
   *
   * @param tags         the OpenSidewalks properties of the edge
   * @param incline      grade in the direction of travel, NaN if unknown. The edge is then walked
   *                     at the plain speed of its layer. It is not treated as flat: level ground
   *                     is slightly slower than the ideal, gently downhill, grade.
   * @param lengthMeters length of the edge, used only for the minimum length below which incline
   *                     limits are not applied
   */
  public EdgeCost evaluate(
    Map<String, String> tags,
    double incline,
    double lengthMeters,
    CurbRamps curbRamps
  ) {
    ResolvedLayer layer = findLayer(tags);
    if (layer == null) {
      return profile.otherEdgesWalkable() ? EdgeCost.WALK : EdgeCost.IMPASSABLE;
    }
    if (layer.blocked) {
      return EdgeCost.IMPASSABLE;
    }
    double factor = layer.speedFactor;
    if (layer.hasIncline && !Double.isNaN(incline)) {
      if (
        lengthMeters > layer.minLengthForLimits &&
        (incline > layer.maxUphill || incline < -layer.maxDownhill)
      ) {
        return EdgeCost.IMPASSABLE;
      }
      double k = incline > layer.ideal ? layer.kUp : layer.kDown;
      factor *= Math.exp(-k * Math.abs(incline - layer.ideal));
    }
    if (layer.requireCurbRamps && curbRamps != CurbRamps.YES) {
      return EdgeCost.IMPASSABLE;
    }
    return new EdgeCost(factor / layer.costFactor, layer.delaySeconds * layer.costFactor);
  }

  /** The name of the layer the given tags fall into, or null if they match no layer. */
  @Nullable
  public String layerName(Map<String, String> tags) {
    ResolvedLayer layer = findLayer(tags);
    return layer == null ? null : layer.name;
  }

  @Nullable
  private ResolvedLayer findLayer(Map<String, String> tags) {
    for (ResolvedLayer l : layers) {
      if (l.match.matches(tags)) {
        return l;
      }
    }
    return null;
  }

  /**
   * What it costs to walk an edge: the walking speed is multiplied with the speed factor, and the
   * delay is added to the time it takes. An edge with a speed factor of zero is impassable.
   */
  public record EdgeCost(double speedFactor, double delaySeconds) {
    public static final EdgeCost IMPASSABLE = new EdgeCost(0, 0);
    static final EdgeCost WALK = new EdgeCost(1, 0);

    public boolean isImpassable() {
      return !(speedFactor > 0);
    }

    /** The time it takes to walk the given distance at the given base speed. */
    public double seconds(double lengthMeters, double baseSpeed) {
      return isImpassable()
        ? Double.POSITIVE_INFINITY
        : lengthMeters / (baseSpeed * speedFactor) + delaySeconds;
    }
  }

  private static class ResolvedLayer {

    final String name;
    final PedestrianCostProfile.Match match;
    final boolean hasIncline;
    final double maxUphill;
    final double maxDownhill;
    final double ideal;
    final double kUp;
    final double kDown;
    final double minLengthForLimits;
    final double delaySeconds;
    final boolean requireCurbRamps;
    final double speedFactor;
    final boolean blocked;
    /** What the whole cost, delay included, is multiplied by. 1 unless the layer is avoided. */
    final double costFactor;

    ResolvedLayer(PedestrianCostProfile.Layer layer, Map<String, JsonNode> params) {
      this.name = layer.name;
      this.match = layer.match;
      var incline = layer.inclineSpeed;
      this.hasIncline = incline != null;
      if (incline != null) {
        maxUphill = incline.maxUphill.number(params, "maxUphill");
        maxDownhill = incline.maxDownhill.number(params, "maxDownhill");
        ideal = incline.ideal;
        minLengthForLimits = incline.minLengthForLimits;
        // Choose k so that the speed at the incline limit is base speed / divisor
        kUp = Math.log(incline.divisor) / Math.abs(maxUphill - ideal);
        kDown = Math.log(incline.divisor) / Math.abs(-maxDownhill - ideal);
      } else {
        maxUphill = maxDownhill = ideal = kUp = kDown = minLengthForLimits = 0;
      }
      this.delaySeconds =
        layer.delaySeconds == null ? 0 : layer.delaySeconds.number(params, "delaySeconds");
      this.requireCurbRamps =
        layer.requireCurbRamps != null && layer.requireCurbRamps.bool(params, "requireCurbRamps");
      this.speedFactor =
        layer.speedFactor == null ? 1 : layer.speedFactor.number(params, "speedFactor");
      boolean closed = layer.blocked != null && layer.blocked.bool(params, "blocked");
      double factor = 1;
      var avoidance = layer.avoidance;
      boolean avoid =
        avoidance != null &&
        !(avoidance.unless != null && avoidance.unless.bool(params, "avoidance unless"));
      if (avoid) {
        double amount = avoidance.amount.number(params, "avoidance amount");
        if (amount >= 1) {
          closed = true;
        } else {
          factor = Math.exp(avoidance.k * amount);
        }
      }
      this.blocked = closed;
      this.costFactor = factor;
    }
  }
}

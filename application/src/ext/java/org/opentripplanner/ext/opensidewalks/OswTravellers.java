package org.opentripplanner.ext.opensidewalks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.opentripplanner.ext.opensidewalks.PedestrianCostProfile.Parameter;

/**
 * Finds the travellers who can only just use an edge.
 * <p>
 * Whether a traveller can use an edge depends on the values of some of the parameters of the
 * profile, and each of those only ever makes edges easier to use in one direction: a higher
 * uphill limit, or not avoiding curbs. So a traveller who is at least as able as one who can use
 * an edge can use it too, and to know who can use an edge it is enough to know the least able
 * travellers who can. Those are found here, by taking the most able traveller there is and
 * taking away what the edge does not ask for.
 */
final class OswTravellers {

  /** Halving the range of a number this often finds a limit to well below what data has. */
  private static final int HALVINGS = 30;

  /** How much less able the next traveller is. Grades in the data are given to a thousandth. */
  private static final double STEP = 1e-5;

  private final PedestrianCostProfile profile;
  private final List<Parameter> parameters;
  private final Map<OswEdgeAttributes, List<PedestrianCostParameters>> leastAble =
    new ConcurrentHashMap<>();

  OswTravellers(PedestrianCostProfile profile) {
    this.profile = profile;
    this.parameters = profile.usabilityParameters();
  }

  /**
   * The least able travellers who can use the edge: any traveller who can use it is at least as
   * able as one of them. Empty if no traveller can use the edge.
   */
  List<PedestrianCostParameters> leastAbleUsers(OswEdgeAttributes edge) {
    return leastAble.computeIfAbsent(edge, this::findLeastAbleUsers);
  }

  /** The values of the traveller who can use every edge that anyone can use. */
  Map<String, String> mostAble() {
    Map<String, String> values = new TreeMap<>();
    for (Parameter p : parameters) {
      values.put(p.name, profile.extreme(p, true).toString());
    }
    return values;
  }

  /**
   * The travellers who are a little less able than the given one, each in one respect, so that
   * they can not walk a path of the given edges that the given traveller can. Every traveller
   * who is no more able than the given one and can not walk the path is no more able than one of
   * them.
   *
   * @param traveller the parameter values of a traveller who can use all of the edges
   */
  List<Map<String, String>> justShortOf(
    Map<String, String> traveller,
    List<OswEdgeAttributes> path
  ) {
    Map<String, Object> mostAble = new LinkedHashMap<>();
    for (Parameter p : parameters) {
      mostAble.put(p.name, profile.extreme(p, true));
    }
    if (!canUse(mostAble, path)) {
      return List.of();
    }
    var needed = reduce(mostAble, parameters, path).values();
    List<Map<String, String>> result = new ArrayList<>();
    for (Parameter p : parameters) {
      Object leastAbleValue = profile.extreme(p, false);
      String next;
      if (p.isBoolean) {
        if (needed.get(p.name).equals(leastAbleValue.toString())) {
          continue;
        }
        next = leastAbleValue.toString();
      } else {
        double limit = Double.parseDouble(needed.get(p.name));
        double end = (double) leastAbleValue;
        if (Math.abs(limit - end) < 2 * STEP) {
          continue;
        }
        // The path asks for the limit, so a step beyond it is not enough
        double value = limit + Math.signum(end - limit) * STEP;
        next = String.format(Locale.ROOT, "%.6f", value);
      }
      Map<String, String> lessAble = new TreeMap<>(traveller);
      lessAble.put(p.name, next);
      result.add(lessAble);
    }
    return result;
  }

  boolean canUse(PedestrianCostParameters traveller, OswEdgeAttributes edge) {
    return edge.speedFactor(profile.resolve(traveller.values())) > 0;
  }

  private List<PedestrianCostParameters> findLeastAbleUsers(OswEdgeAttributes edge) {
    Map<String, Object> mostAble = new LinkedHashMap<>();
    for (Parameter p : parameters) {
      mostAble.put(p.name, profile.extreme(p, true));
    }
    if (!canUse(mostAble, List.of(edge))) {
      return List.of();
    }
    // An edge may be usable in more than one way, with a switch or without it, for example. Going
    // through the parameters in both orders finds the least able traveller of either way.
    Set<PedestrianCostParameters> result = new LinkedHashSet<>();
    result.add(reduce(mostAble, parameters, List.of(edge)));
    result.add(reduce(mostAble, parameters.reversed(), List.of(edge)));
    return List.copyOf(result);
  }

  /** Take away as much ability as the edges allow, one parameter after the other. */
  private PedestrianCostParameters reduce(
    Map<String, Object> mostAble,
    List<Parameter> order,
    List<OswEdgeAttributes> edges
  ) {
    Map<String, Object> values = new HashMap<>(mostAble);
    for (Parameter p : order) {
      Object able = values.get(p.name);
      Object leastAbleValue = profile.extreme(p, false);
      values.put(p.name, leastAbleValue);
      if (canUse(values, edges)) {
        continue;
      }
      values.put(p.name, able);
      if (p.isBoolean) {
        continue;
      }
      // The limit is somewhere in between. Close in on it from the side the edge can be used.
      double usable = (double) able;
      double unusable = (double) leastAbleValue;
      for (int i = 0; i < HALVINGS; i++) {
        double middle = (usable + unusable) / 2;
        values.put(p.name, middle);
        if (canUse(values, edges)) {
          usable = middle;
        } else {
          unusable = middle;
        }
      }
      values.put(p.name, usable);
    }
    Map<String, String> text = new HashMap<>();
    values.forEach((name, value) -> text.put(name, value.toString()));
    return PedestrianCostParameters.of(text);
  }

  private boolean canUse(Map<String, Object> values, List<OswEdgeAttributes> edges) {
    var spec = profile.resolve(values);
    for (var edge : edges) {
      if (!(edge.speedFactor(spec) > 0)) {
        return false;
      }
    }
    return true;
  }
}

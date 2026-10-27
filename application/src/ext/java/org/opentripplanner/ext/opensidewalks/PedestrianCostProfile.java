package org.opentripplanner.ext.opensidewalks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * A configurable pedestrian cost function, expressed as data rather than code so that the same
 * definition can be evaluated identically by OTP, R5 and Unweaver, and so that every result can
 * be traced to the exact definition that produced it.
 * <p>
 * The semantics follow the cost functions of the TDEI Walksheds service: each edge is assigned to
 * the first layer whose "match" rules it satisfies. Edges in a layer with an "inclineSpeed" block
 * move at the speed of Tobler's hiking function, tuned so that speed falls to
 * {@code baseSpeed / divisor} at the traveller's uphill and downhill limits, and are impassable
 * beyond those limits. Layers can add a fixed delay (waiting to cross), require curb ramps, be
 * closed, or cost more the more the traveller wants to avoid them.
 * <p>
 * Cost of an edge, in seconds: {@code length / (baseSpeed * speedFactor) + delaySeconds}, or
 * impassable. Any setting may be a literal or a "$parameter" reference.
 * <p>
 * The profile id is the md5 of the canonical form of the profile JSON, see {@link CanonicalJson}:
 * it identifies the definition independent of formatting, and changes whenever any part of the
 * definition changes.
 */
public class PedestrianCostProfile {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Keys that layers may not match on: values that vary per edge, and keys that only exist in one
   * routing engine's view of an edge. Matching on these would make the engines disagree.
   */
  static final Set<String> RESERVED_MATCH_KEYS = Set.of(
    "_id",
    "_u_id",
    "_v_id",
    "_u",
    "_v",
    "length",
    "incline",
    "incline_text",
    "curbramps",
    "_layer",
    "geom",
    "fid"
  );

  private final String profileId;
  private final String name;
  private final Map<String, Parameter> parameters;
  private final List<Layer> layers;
  private final boolean otherEdgesWalkable;

  /**
   * For each parameter that decides whether an edge can be used: whether a higher value, or true,
   * lets a traveller use more edges. A parameter that only changes how long an edge takes is not
   * in here.
   */
  private final Map<String, Boolean> looserWhenHigher = new LinkedHashMap<>();

  public PedestrianCostProfile(JsonNode source) {
    if (source == null || !source.isObject()) {
      throw new IllegalArgumentException("A profile must be a JSON object.");
    }
    this.profileId = CanonicalJson.md5OfCanonical(source);
    this.name = text(source, "name", "unnamed");
    Map<String, Parameter> params = new LinkedHashMap<>();
    source
      .path("parameters")
      .fields()
      .forEachRemaining(e -> params.put(e.getKey(), new Parameter(e.getKey(), e.getValue())));
    this.parameters = Collections.unmodifiableMap(params);
    List<Layer> layerList = new ArrayList<>();
    for (JsonNode layer : source.path("layers")) {
      layerList.add(new Layer(layer));
    }
    this.layers = List.copyOf(layerList);
    String other = text(source, "otherEdges", "impassable");
    this.otherEdgesWalkable = switch (other) {
      case "impassable" -> false;
      case "walk" -> true;
      default -> throw new IllegalArgumentException(
        "otherEdges must be impassable or walk, was " + other
      );
    };
    checkKeys(
      source,
      Set.of("name", "description", "parameters", "layers", "otherEdges", "schemaVersion"),
      "profile"
    );
    for (Layer layer : layers) {
      if (layer.inclineSpeed != null) {
        orient(layer.inclineSpeed.maxUphill, true);
        orient(layer.inclineSpeed.maxDownhill, true);
      }
      orient(layer.requireCurbRamps, false);
      orient(layer.blocked, false);
      if (layer.avoidance != null) {
        orient(layer.avoidance.amount, false);
        orient(layer.avoidance.unless, true);
      }
    }
  }

  private void orient(@Nullable Value value, boolean higherIsLooser) {
    if (value == null || value.parameter == null) {
      return;
    }
    Boolean known = looserWhenHigher.putIfAbsent(value.parameter, higherIsLooser);
    if (known != null && known != higherIsLooser) {
      throw new IllegalArgumentException(
        "Parameter %s makes some edges easier to use and others harder.".formatted(value.parameter)
      );
    }
  }

  public static PedestrianCostProfile fromFile(File file) throws IOException {
    return new PedestrianCostProfile(MAPPER.readTree(file));
  }

  public static PedestrianCostProfile fromJson(String json) throws IOException {
    return new PedestrianCostProfile(MAPPER.readTree(json));
  }

  /** md5 of the canonical JSON of the whole profile definition. */
  public String profileId() {
    return profileId;
  }

  public String name() {
    return name;
  }

  /**
   * What the parameters are, by their names and in the order of the profile: what a user
   * interface needs to offer them. Each is described by the values it can have, after the
   * description the profile gives it, if it gives one.
   */
  public Map<String, String> parameterDescriptions() {
    Map<String, String> descriptions = new LinkedHashMap<>();
    for (Parameter p : parameters.values()) {
      descriptions.put(p.name, p.describe());
    }
    return descriptions;
  }

  List<Layer> layers() {
    return layers;
  }

  /** Whether edges that match no layer are walked at the base speed, or are impassable. */
  boolean otherEdgesWalkable() {
    return otherEdgesWalkable;
  }

  /**
   * Combine this profile with parameter values, validating them and filling in defaults.
   *
   * @param values parameter values by name; may be null or omit parameters that have defaults.
   *               A value may be a number, a boolean, or the text form of either.
   */
  public PedestrianCostSpec resolve(@Nullable Map<String, ?> values) {
    Map<String, ?> supplied = values == null ? Map.of() : values;
    for (String key : supplied.keySet()) {
      if (!parameters.containsKey(key)) {
        throw new IllegalArgumentException("Profile " + name + " has no parameter named " + key);
      }
    }
    Map<String, JsonNode> resolved = new LinkedHashMap<>();
    for (Parameter p : parameters.values()) {
      Object value = supplied.get(p.name);
      JsonNode v = value == null ? p.defaultValue : p.parse(value);
      if (v == null || v.isNull()) {
        throw new IllegalArgumentException("No value for parameter " + p.name);
      }
      if (p.isBoolean && !v.isBoolean()) {
        throw new IllegalArgumentException("Parameter " + p.name + " must be true or false.");
      }
      if (!p.isBoolean) {
        if (!v.isNumber()) {
          throw new IllegalArgumentException("Parameter " + p.name + " must be a number.");
        }
        double d = v.asDouble();
        if ((p.min != null && d < p.min) || (p.max != null && d > p.max)) {
          throw new IllegalArgumentException(
            "Parameter %s=%s is outside [%s, %s].".formatted(p.name, d, p.min, p.max)
          );
        }
      }
      resolved.put(p.name, v);
    }
    return new PedestrianCostSpec(this, resolved);
  }

  /**
   * The travellers at the corners of the ranges of the parameters. An edge is as hard to use as
   * it gets for one of them, and as easy as it gets for another, since a higher limit or a
   * switch never makes an edge both easier for some and harder for others.
   */
  List<PedestrianCostSpec> extremes() {
    return extremeValues().stream().map(this::resolve).toList();
  }

  /**
   * The parameter values of the travellers at the corners of the ranges of the parameters. Only
   * the parameters that decide whether an edge can be used have a value. The others would not
   * change which edges a traveller can use.
   */
  public List<Map<String, String>> extremeValues() {
    List<Map<String, String>> corners = new ArrayList<>();
    corners.add(new LinkedHashMap<>());
    for (String name : looserWhenHigher.keySet()) {
      Parameter p = parameters.get(name);
      if (p == null) {
        continue;
      }
      List<Map<String, String>> next = new ArrayList<>();
      for (var corner : corners) {
        for (Object value : p.extremes()) {
          var withValue = new LinkedHashMap<>(corner);
          withValue.put(p.name, value.toString());
          next.add(withValue);
        }
      }
      if (!next.isEmpty()) {
        corners = next;
      }
    }
    return corners;
  }

  /** The parameters that decide whether an edge can be used, in the order they are first used. */
  List<Parameter> usabilityParameters() {
    return looserWhenHigher
      .keySet()
      .stream()
      .map(parameters::get)
      .filter(java.util.Objects::nonNull)
      .toList();
  }

  /**
   * The value of a parameter at the end of its range where a traveller can use the most edges,
   * or the fewest.
   */
  Object extreme(Parameter parameter, boolean loosest) {
    var ends = parameter.extremes();
    boolean highest = looserWhenHigher.get(parameter.name) == loosest;
    return highest ? ends.getLast() : ends.getFirst();
  }

  /** The index of the first layer the tags of an edge match, or -1 if they match none. */
  int layerIndex(Map<String, String> tags) {
    for (int i = 0; i < layers.size(); i++) {
      if (layers.get(i).match.matches(tags)) {
        return i;
      }
    }
    return -1;
  }

  private static JsonNode require(JsonNode spec, String key) {
    JsonNode v = spec.get(key);
    if (v == null || v.isNull()) {
      throw new IllegalArgumentException("Missing required setting " + key);
    }
    return v;
  }

  private static String text(JsonNode spec, String key, String defaultValue) {
    JsonNode v = spec.get(key);
    return v == null || v.isNull() ? defaultValue : v.asText();
  }

  /**
   * Reject misspelled settings, which would otherwise be silently ignored but still change the
   * profile id.
   */
  private static void checkKeys(JsonNode spec, Set<String> allowed, String where) {
    spec.fieldNames().forEachRemaining(k -> {
      if (!allowed.contains(k)) {
        throw new IllegalArgumentException("Unknown setting '" + k + "' in " + where);
      }
    });
  }

  /** An input of the traveller, like the steepest uphill grade they can manage. */
  static class Parameter {

    final String name;
    final boolean isBoolean;
    final JsonNode defaultValue;
    final Double min;
    final Double max;

    @Nullable
    private final String description;

    Parameter(String name, JsonNode spec) {
      this.name = name;
      this.description = text(spec, "description", null);
      String type = text(spec, "type", "number");
      if (!type.equals("number") && !type.equals("boolean")) {
        throw new IllegalArgumentException(
          "Parameter " + name + " type must be number or boolean, was " + type
        );
      }
      this.isBoolean = type.equals("boolean");
      this.defaultValue = spec.get("default");
      // The range a user interface offers, if one is given, is the range travellers can have
      this.min = number(spec, "sliderMin", "min");
      this.max = number(spec, "sliderMax", "max");
      // The settings beyond type, default, min and max are for user interfaces showing the
      // parameter. They are accepted so that a profile can be shared with R5 unchanged.
      checkKeys(
        spec,
        Set.of(
          "type",
          "default",
          "min",
          "max",
          "step",
          "label",
          "unit",
          "description",
          "sliderMin",
          "sliderMax",
          "hidden"
        ),
        "parameter " + name
      );
    }

    /** What the parameter means, if the profile says, and the values it can have. */
    String describe() {
      String values;
      if (isBoolean) {
        values = "`true` or `false`";
      } else if (min != null && max != null) {
        values = "A number from " + min + " to " + max;
      } else if (min != null) {
        values = "A number, at least " + min;
      } else if (max != null) {
        values = "A number, at most " + max;
      } else {
        values = "A number";
      }
      if (defaultValue != null) {
        values += ", " + defaultValue.asText() + " if it is not set";
      }
      if (description == null || description.isBlank()) {
        return values + ".";
      }
      String text = description.strip();
      return text + (text.endsWith(".") ? " " : ". ") + values + ".";
    }

    @Nullable
    private static Double number(JsonNode spec, String key, String otherwise) {
      JsonNode v = spec.has(key) ? spec.get(key) : spec.get(otherwise);
      return v == null || v.isNull() ? null : v.asDouble();
    }

    /** The values at the ends of the range of the parameter, or its default if it has no range. */
    List<Object> extremes() {
      if (isBoolean) {
        return List.of(false, true);
      }
      if (min != null && max != null) {
        return List.of(min, max);
      }
      return defaultValue == null || defaultValue.isNull()
        ? List.of()
        : List.of(defaultValue.asDouble());
    }

    /** Text is read as the type the parameter declares, anything else must already have it. */
    JsonNode parse(Object value) {
      if (value instanceof String s) {
        String text = s.trim();
        if (isBoolean && (text.equals("true") || text.equals("false"))) {
          return BooleanNode.valueOf(Boolean.parseBoolean(text));
        }
        if (!isBoolean) {
          try {
            return DoubleNode.valueOf(Double.parseDouble(text));
          } catch (NumberFormatException ignore) {
            // Reported as a value of the wrong type by the caller
          }
        }
      }
      return MAPPER.valueToTree(value);
    }
  }

  /** A value that is either a literal or a reference to a parameter ("$name"). */
  static class Value {

    private final JsonNode literal;
    private final String parameter;

    Value(JsonNode node) {
      if (node.isTextual() && node.textValue().startsWith("$")) {
        this.parameter = node.textValue().substring(1);
        this.literal = null;
      } else {
        this.parameter = null;
        this.literal = node;
      }
    }

    /** Settings must have the right JSON type, so that all engines interpret them alike. */
    double number(Map<String, JsonNode> params, String setting) {
      JsonNode v = resolve(params);
      if (!v.isNumber()) {
        throw new IllegalArgumentException(setting + " must be a number.");
      }
      return v.asDouble();
    }

    boolean bool(Map<String, JsonNode> params, String setting) {
      JsonNode v = resolve(params);
      if (!v.isBoolean()) {
        throw new IllegalArgumentException(setting + " must be true or false.");
      }
      return v.booleanValue();
    }

    private JsonNode resolve(Map<String, JsonNode> params) {
      if (parameter == null) {
        return literal;
      }
      JsonNode v = params.get(parameter);
      if (v == null) {
        throw new IllegalArgumentException("Unknown parameter $" + parameter);
      }
      return v;
    }
  }

  /**
   * Matches the tags of an edge: every key must match. A value may be a string, a list of
   * strings, or "*" (the tag is present).
   */
  static class Match {

    private final Map<String, Set<String>> anyOf = new LinkedHashMap<>();
    private final Set<String> present = new HashSet<>();

    Match(@Nullable JsonNode spec) {
      if (spec == null || !spec.isObject()) {
        return;
      }
      spec.fields().forEachRemaining(e -> {
        if (RESERVED_MATCH_KEYS.contains(e.getKey())) {
          throw new IllegalArgumentException("Layers cannot match on '" + e.getKey() + "'.");
        }
        JsonNode v = e.getValue();
        if (v.isTextual() && v.textValue().equals("*")) {
          present.add(e.getKey());
        } else if (v.isArray()) {
          Set<String> values = new HashSet<>();
          v.forEach(x -> values.add(x.asText()));
          anyOf.put(e.getKey(), values);
        } else if (v.isValueNode()) {
          anyOf.put(e.getKey(), Set.of(v.asText()));
        } else {
          throw new IllegalArgumentException(
            "Match values must be strings, lists of strings, or \"*\"."
          );
        }
      });
    }

    boolean matches(Map<String, String> tags) {
      for (String key : present) {
        if (!tags.containsKey(key)) {
          return false;
        }
      }
      for (var e : anyOf.entrySet()) {
        String v = tags.get(e.getKey());
        if (v == null || !e.getValue().contains(v)) {
          return false;
        }
      }
      return true;
    }
  }

  static class InclineSpeed {

    final Value maxUphill;
    final Value maxDownhill;
    /** Grade at which the speed is highest. -0.0087 in Tobler's hiking function. */
    final double ideal;
    /** Speed at the traveller's incline limit is baseSpeed / divisor. */
    final double divisor;
    /** The incline limits only close edges longer than this, tolerating short steep segments. */
    final double minLengthForLimits;

    InclineSpeed(JsonNode spec) {
      this.maxUphill = new Value(require(spec, "maxUphill"));
      this.maxDownhill = new Value(require(spec, "maxDownhill"));
      this.ideal = spec.path("ideal").asDouble(-0.0087);
      this.divisor = spec.path("divisor").asDouble(5);
      this.minLengthForLimits = spec.path("minLengthForLimits").asDouble(3);
      checkKeys(
        spec,
        Set.of("maxUphill", "maxDownhill", "ideal", "divisor", "minLengthForLimits"),
        "inclineSpeed"
      );
    }
  }

  /**
   * Makes the edges of a layer cost more the more the traveller wants to avoid them: the cost is
   * multiplied by {@code exp(k * amount)}, and at an amount of 1 or more the edges are impassable.
   * "unless" switches the avoidance off, leaving the ordinary cost of the layer.
   */
  static class Avoidance {

    final Value amount;
    final double k;

    @Nullable
    final Value unless;

    Avoidance(JsonNode spec) {
      this.amount = new Value(require(spec, "amount"));
      this.k = spec.path("k").asDouble(1);
      this.unless = spec.has("unless") ? new Value(spec.get("unless")) : null;
      checkKeys(spec, Set.of("amount", "k", "unless"), "avoidance");
    }
  }

  static class Layer {

    final String name;
    final Match match;

    @Nullable
    final InclineSpeed inclineSpeed;

    @Nullable
    final Value delaySeconds;

    @Nullable
    final Value requireCurbRamps;

    @Nullable
    final Value speedFactor;

    @Nullable
    final Value blocked;

    @Nullable
    final Avoidance avoidance;

    Layer(JsonNode spec) {
      this.name = text(spec, "name", null);
      if (name == null) {
        throw new IllegalArgumentException("Every layer needs a name.");
      }
      this.match = new Match(spec.get("match"));
      this.inclineSpeed = spec.has("inclineSpeed")
        ? new InclineSpeed(spec.get("inclineSpeed"))
        : null;
      this.delaySeconds = value(spec, "delaySeconds");
      this.requireCurbRamps = value(spec, "requireCurbRamps");
      this.speedFactor = value(spec, "speedFactor");
      this.blocked = value(spec, "blocked");
      this.avoidance = spec.has("avoidance") ? new Avoidance(spec.get("avoidance")) : null;
      checkKeys(
        spec,
        Set.of(
          "name",
          "description",
          "match",
          "inclineSpeed",
          "delaySeconds",
          "requireCurbRamps",
          "speedFactor",
          "blocked",
          "avoidance"
        ),
        "layer " + name
      );
    }

    @Nullable
    private static Value value(JsonNode spec, String key) {
      return spec.has(key) ? new Value(spec.get(key)) : null;
    }
  }
}

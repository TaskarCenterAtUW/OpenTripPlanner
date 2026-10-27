package org.opentripplanner.ext.opensidewalks;

import java.util.Map;
import java.util.OptionalDouble;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.opentripplanner.street.model.StreetTraversalPermission;

/**
 * The roads of an OpenSidewalks dataset: the edges that bicycles and cars can use, and how fast
 * a car goes on them. OpenSidewalks has no one-way streets, turn restrictions or access tags, so
 * a road can be used in both directions by everyone.
 */
final class OswRoads {

  /**
   * The kinds of road and the speed of a car on them, in meters per second, where the edge has no
   * `ext:maxspeed`. These are the defaults OTP has for OpenStreetMap.
   */
  private static final Map<String, Float> CAR_SPEEDS = Map.of(
    "trunk",
    24.6f,
    "primary",
    20f,
    "secondary",
    15f,
    "tertiary",
    11.2f,
    "unclassified",
    11.2f,
    "residential",
    11.2f,
    "service",
    6.7f,
    "living_street",
    2.2f
  );

  private static final double METERS_PER_SECOND_PER_MPH = 0.44704;
  private static final double METERS_PER_SECOND_PER_KMH = 1 / 3.6;
  private static final Pattern SPEED = Pattern.compile(
    "\\s*(\\d+(?:\\.\\d+)?)\\s*(mph|km/h|kmh|kph)?\\s*"
  );

  private OswRoads() {}

  /** Whether an edge with the given `highway` is a road. */
  static boolean isRoad(@Nullable String highway) {
    return highway != null && CAR_SPEEDS.containsKey(highway);
  }

  /** Who can use an edge with the given tags. Everything that is not a road is only walked. */
  static StreetTraversalPermission permission(Map<String, String> tags) {
    return isRoad(tags.get("highway"))
      ? StreetTraversalPermission.ALL
      : StreetTraversalPermission.PEDESTRIAN;
  }

  /**
   * The speed of a car on a road with the given tags, in meters per second: its `ext:maxspeed` if
   * it has one OTP understands, otherwise the default for the kind of road.
   */
  static float carSpeed(Map<String, String> tags) {
    var maxspeed = maxspeed(tags.get("ext:maxspeed"));
    if (maxspeed.isPresent()) {
      return (float) maxspeed.getAsDouble();
    }
    return CAR_SPEEDS.getOrDefault(tags.get("highway"), CAR_SPEEDS.get("residential"));
  }

  /** A speed such as `40 mph` or `50`, which is in km/h, in meters per second. */
  static OptionalDouble maxspeed(@Nullable String value) {
    if (value == null) {
      return OptionalDouble.empty();
    }
    var matcher = SPEED.matcher(value);
    if (!matcher.matches()) {
      return OptionalDouble.empty();
    }
    double speed = Double.parseDouble(matcher.group(1));
    if (speed <= 0) {
      return OptionalDouble.empty();
    }
    boolean mph = "mph".equals(matcher.group(2));
    return OptionalDouble.of(speed * (mph ? METERS_PER_SECOND_PER_MPH : METERS_PER_SECOND_PER_KMH));
  }
}

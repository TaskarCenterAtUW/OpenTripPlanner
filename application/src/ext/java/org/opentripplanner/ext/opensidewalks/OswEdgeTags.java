package org.opentripplanner.ext.opensidewalks;

import java.io.Serializable;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * The tags of an OpenSidewalks edge that a trip planner shows to the traveller, for example to
 * say "cross the street" or "use the stairs". The two directions of an edge, and the parts it is
 * split into, share these.
 *
 * @param name        the {@code name} of the edge, or {@code null} if it has none
 * @param highway     the kind of way, for example {@code footway} or {@code steps}
 * @param footway     the kind of footway, for example {@code sidewalk} or {@code crossing}
 * @param description the {@code description} of the edge
 * @param surface     the {@code surface} of the edge, for example {@code concrete}
 * @param crossing    whether a crossing is {@code marked} or {@code unmarked}, {@code null} if
 *                    that is not known
 */
record OswEdgeTags(
  @Nullable String name,
  @Nullable String highway,
  @Nullable String footway,
  @Nullable String description,
  @Nullable String surface,
  @Nullable String crossing,
  boolean elevator,
  boolean indoor
) implements Serializable {
  static final OswEdgeTags NONE = new OswEdgeTags(null, null, null, null, null, null, false, false);

  static OswEdgeTags of(Map<String, String> tags) {
    String highway = intern(tags.get("highway"));
    return new OswEdgeTags(
      tags.get("name"),
      highway,
      intern(tags.get("footway")),
      tags.get("description"),
      intern(tags.get("surface")),
      crossing(tags),
      "elevator".equals(highway) || "yes".equals(tags.get("elevator")),
      "yes".equals(tags.get("indoor"))
    );
  }

  /**
   * OSW 0.2 says how a crossing is marked with {@code crossing:markings}, for example
   * {@code zebra} or {@code no}. Older data has {@code crossing=marked} or {@code unmarked}.
   */
  @Nullable
  private static String crossing(Map<String, String> tags) {
    String markings = tags.get("crossing:markings");
    if (markings != null) {
      return "no".equals(markings) ? "unmarked" : "marked";
    }
    String crossing = tags.get("crossing");
    if ("marked".equals(crossing) || "unmarked".equals(crossing)) {
      return crossing;
    }
    return null;
  }

  /** The kinds of ways and surfaces are a handful of values, shared by many edges. */
  @Nullable
  private static String intern(@Nullable String value) {
    return value == null ? null : value.intern();
  }
}

package org.opentripplanner.ext.opensidewalks;

import java.util.Locale;
import javax.annotation.Nullable;

/**
 * Whether an OpenSidewalks edge, typically a crossing, has curb ramps. It is derived from the
 * kerb nodes at the ends of the edge, or from an explicit {@code curbramps} property of the edge.
 */
public enum CurbRamps {
  UNKNOWN,
  YES,
  NO;

  /**
   * Interpret an explicit {@code curbramps} value: true/yes/1 or false/no/0. Anything else is
   * unknown, in which case the status is derived from the kerb nodes.
   */
  static CurbRamps parse(@Nullable String value) {
    if (value == null) {
      return UNKNOWN;
    }
    String v = value.trim().toLowerCase(Locale.ROOT);
    if (v.equals("true") || v.equals("yes")) {
      return YES;
    }
    if (v.equals("false") || v.equals("no")) {
      return NO;
    }
    try {
      double d = Double.parseDouble(v);
      if (d == 1) {
        return YES;
      }
      if (d == 0) {
        return NO;
      }
    } catch (NumberFormatException ignore) {
      // Not numeric
    }
    return UNKNOWN;
  }

  /**
   * Derive the status the way the TDEI Walksheds service does: an explicit value takes
   * precedence. Otherwise the edge has curb ramps unless a kerb stands in the way at either end:
   * any kerb that is not lowered or flush, including one with no type given. An end with no kerb
   * node at all is no obstacle.
   *
   * @param beginKerb the kerb type of the node at the start of the edge, or {@code null} if that
   *                  node is not a kerb. See {@link OswDataset.Node#kerb()}.
   */
  static CurbRamps of(
    @Nullable String explicitValue,
    @Nullable String beginKerb,
    @Nullable String endKerb
  ) {
    CurbRamps explicit = parse(explicitValue);
    if (explicit != UNKNOWN) {
      return explicit;
    }
    return isObstacle(beginKerb) || isObstacle(endKerb) ? NO : YES;
  }

  private static boolean isObstacle(@Nullable String kerb) {
    return kerb != null && !kerb.equals("lowered") && !kerb.equals("flush");
  }
}

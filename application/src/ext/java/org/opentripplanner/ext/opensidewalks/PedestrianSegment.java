package org.opentripplanner.ext.opensidewalks;

import javax.annotation.Nullable;
import org.locationtech.jts.geom.LineString;

/**
 * The part of an OpenSidewalks edge that a walking leg of an itinerary goes along, or a pathway of
 * a station it goes through, with what a trip planner shows the traveller about it. This is part
 * of the OpenSidewalks sandbox feature.
 *
 * @param geometry       the part of the edge that is walked, in the direction it is walked
 * @param distanceMeters the length of that part
 * @param incline        the grade in the direction it is walked, {@code null} if unknown
 * @param curbRamps      whether the edge has curb ramps, {@code null} if unknown
 * @param pathwayMode    the kind of station pathway, for example {@code ESCALATOR}, or {@code null}
 *                       if this is an OpenSidewalks edge
 * @param fromLevel      the name of the level of the station a pathway starts on, if known
 * @param toLevel        the name of the level a pathway ends on, if known
 * @param levelChange    how many levels a pathway goes up, negative if it goes down, if known
 */
public record PedestrianSegment(
  LineString geometry,
  double distanceMeters,
  @Nullable String name,
  @Nullable String highway,
  @Nullable String footway,
  @Nullable String description,
  @Nullable String surface,
  @Nullable Double incline,
  @Nullable Boolean curbRamps,
  boolean elevator,
  boolean indoor,
  @Nullable String pathwayMode,
  @Nullable String fromLevel,
  @Nullable String toLevel,
  @Nullable Double levelChange
) {}

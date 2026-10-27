package org.opentripplanner.ext.opensidewalks;

/**
 * Which of the two street networks of a graph with OpenSidewalks data and other street data a
 * request travels on. The two are not joined to each other, so a trip is on one or the other.
 */
public enum StreetNetwork {
  /**
   * The OpenSidewalks data, for everything the trip does: walking, at the cost the pedestrian
   * cost profile gives it, and cycling and driving on the roads of the data, if it has any.
   */
  OPEN_SIDEWALKS,

  /** The other street data, such as OpenStreetMap, as OTP has it. */
  STREETS,
}

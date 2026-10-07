package org.opentripplanner.ext.opensidewalks;

import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.edge.StreetEdgeBuilder;

class OswStreetEdgeBuilder extends StreetEdgeBuilder<OswStreetEdgeBuilder> {

  private float speedFactor = 1;
  private float delaySeconds = 0;

  OswStreetEdgeBuilder() {
    super();
  }

  /** Start from the properties of an existing edge. */
  OswStreetEdgeBuilder(StreetEdge original) {
    super(original);
  }

  @Override
  public OswStreetEdge buildAndConnect() {
    return OswStreetEdge.createAndConnect(this);
  }

  float speedFactor() {
    return speedFactor;
  }

  OswStreetEdgeBuilder withSpeedFactor(float speedFactor) {
    if (!(speedFactor > 0) || !Float.isFinite(speedFactor)) {
      throw new IllegalArgumentException("Invalid speed factor: " + speedFactor);
    }
    this.speedFactor = speedFactor;
    return this;
  }

  float delaySeconds() {
    return delaySeconds;
  }

  OswStreetEdgeBuilder withDelaySeconds(float delaySeconds) {
    this.delaySeconds = delaySeconds;
    return this;
  }
}

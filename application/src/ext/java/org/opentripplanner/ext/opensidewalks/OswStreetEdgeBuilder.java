package org.opentripplanner.ext.opensidewalks;

import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.edge.StreetEdgeBuilder;

class OswStreetEdgeBuilder extends StreetEdgeBuilder<OswStreetEdgeBuilder> {

  private OswCosting costing;
  private OswEdgeAttributes attributes;
  private float delayShare = 1;

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

  OswCosting costing() {
    return costing;
  }

  OswEdgeAttributes attributes() {
    return attributes;
  }

  OswStreetEdgeBuilder withCosting(OswCosting costing, OswEdgeAttributes attributes) {
    this.costing = costing;
    this.attributes = attributes;
    return this;
  }

  float delayShare() {
    return delayShare;
  }

  OswStreetEdgeBuilder withDelayShare(float delayShare) {
    this.delayShare = delayShare;
    return this;
  }
}

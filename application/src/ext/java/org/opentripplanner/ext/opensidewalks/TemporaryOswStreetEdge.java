package org.opentripplanner.ext.opensidewalks;

import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.edge.TemporaryPartialStreetEdge;
import org.opentripplanner.street.model.edge.TemporaryPartialStreetEdgeBuilder;
import org.opentripplanner.street.model.vertex.StreetVertex;
import org.opentripplanner.street.search.state.State;

/**
 * The part of an {@link OswStreetEdge} that is walked where a trip starts or ends partway along
 * it. It exists for the duration of one request, and is costed like the edge it is a part of.
 */
public class TemporaryOswStreetEdge extends TemporaryPartialStreetEdge {

  private final OswCosting costing;
  private final OswEdgeAttributes attributes;
  private final float delayShare;

  private TemporaryOswStreetEdge(
    TemporaryPartialStreetEdgeBuilder builder,
    OswCosting costing,
    OswEdgeAttributes attributes,
    float delayShare
  ) {
    super(builder);
    this.costing = costing;
    this.attributes = attributes;
    this.delayShare = delayShare;
  }

  /**
   * @param plainPart the part of the parent as OTP built it, which the new edge takes the place of
   */
  static TemporaryOswStreetEdge createAndConnect(
    OswStreetEdge parent,
    StreetEdge plainPart,
    OswCosting costing,
    OswEdgeAttributes attributes,
    float delayShare
  ) {
    var builder = new TemporaryPartialStreetEdgeBuilder()
      .withParentEdge(parent)
      .withFromVertex((StreetVertex) plainPart.getFromVertex())
      .withToVertex((StreetVertex) plainPart.getToVertex())
      .withGeometry(plainPart.getGeometry())
      .withName(plainPart.getName())
      .withMilliMeterLength((int) Math.round(plainPart.getDistanceMeters() * 1000))
      .withBack(plainPart.isBack());
    return connectToGraph(new TemporaryOswStreetEdge(builder, costing, attributes, delayShare));
  }

  OswEdgeAttributes attributes() {
    return attributes;
  }

  /** The share of the delay of the whole OpenSidewalks edge that this edge has. */
  public float delayShare() {
    return delayShare;
  }

  @Override
  public State[] traverse(State s0) {
    var states = OswWalkCost.traverse(s0, this, costing, attributes, delayShare);
    if (states != null) {
      return states;
    }
    // Cycling and driving on roads, and walking with a vehicle, as OTP has them
    return OswWalkCost.withoutWalksTheTravellerCanNotMake(
      s0,
      super.traverse(s0),
      this,
      costing,
      attributes,
      delayShare
    );
  }
}

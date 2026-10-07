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

  private final float speedFactor;
  private final float delaySeconds;

  private TemporaryOswStreetEdge(
    TemporaryPartialStreetEdgeBuilder builder,
    float speedFactor,
    float delaySeconds
  ) {
    super(builder);
    this.speedFactor = speedFactor;
    this.delaySeconds = delaySeconds;
  }

  /**
   * @param plainPart the part of the parent as OTP built it, which the new edge takes the place of
   */
  static TemporaryOswStreetEdge createAndConnect(
    OswStreetEdge parent,
    StreetEdge plainPart,
    float speedFactor,
    float delaySeconds
  ) {
    var builder = new TemporaryPartialStreetEdgeBuilder()
      .withParentEdge(parent)
      .withFromVertex((StreetVertex) plainPart.getFromVertex())
      .withToVertex((StreetVertex) plainPart.getToVertex())
      .withGeometry(plainPart.getGeometry())
      .withName(plainPart.getName())
      .withMilliMeterLength((int) Math.round(plainPart.getDistanceMeters() * 1000))
      .withBack(plainPart.isBack());
    return connectToGraph(new TemporaryOswStreetEdge(builder, speedFactor, delaySeconds));
  }

  public float delaySeconds() {
    return delaySeconds;
  }

  @Override
  public State[] traverse(State s0) {
    var states = OswWalkCost.traverse(s0, this, speedFactor, delaySeconds);
    return states != null ? states : super.traverse(s0);
  }
}

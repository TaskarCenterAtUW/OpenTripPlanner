package org.opentripplanner.ext.opensidewalks;

import java.util.Optional;
import javax.annotation.Nullable;
import org.opentripplanner.street.linking.LinkingDirection;
import org.opentripplanner.street.model.edge.Edge;
import org.opentripplanner.street.model.edge.SplitStreetEdge;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.vertex.SplitterVertex;
import org.opentripplanner.street.model.vertex.StreetVertex;
import org.opentripplanner.street.search.state.State;

/**
 * A street edge built from an OpenSidewalks edge, which is walked at the cost a
 * {@link PedestrianCostProfile} gives it. See {@link OswWalkCost}.
 * <p>
 * The parts this edge is split into, permanently where a stop is linked to it and temporarily
 * where a trip starts or ends partway along it, are costed like this edge. Each part gets the
 * share of the delay that corresponds to its share of the length, so that walking all of the
 * parts costs the same as walking this edge.
 */
public class OswStreetEdge extends StreetEdge {

  private final float speedFactor;
  private final float delaySeconds;

  OswStreetEdge(OswStreetEdgeBuilder builder) {
    super(builder);
    this.speedFactor = builder.speedFactor();
    this.delaySeconds = builder.delaySeconds();
  }

  static OswStreetEdge createAndConnect(OswStreetEdgeBuilder builder) {
    return connectToGraph(new OswStreetEdge(builder));
  }

  /** What the walking speed of the request is multiplied with on this edge. */
  public float speedFactor() {
    return speedFactor;
  }

  /** The time added to the time it takes to walk this edge, such as waiting to cross. */
  public float delaySeconds() {
    return delaySeconds;
  }

  /** The time it takes to walk this edge at the given speed in meters per second. */
  public double walkDurationSeconds(double walkSpeed) {
    return OswWalkCost.durationSeconds(this, speedFactor, delaySeconds, walkSpeed);
  }

  @Override
  public State[] traverse(State s0) {
    var states = OswWalkCost.traverse(s0, this, speedFactor, delaySeconds);
    return states != null ? states : super.traverse(s0);
  }

  @Override
  public SplitStreetEdge splitDestructively(SplitterVertex v) {
    var split = super.splitDestructively(v);
    return new SplitStreetEdge(part(split.head()), part(split.tail()));
  }

  @Override
  public SplitStreetEdge splitNonDestructively(SplitterVertex v, LinkingDirection direction) {
    var split = super.splitNonDestructively(v, direction);
    return new SplitStreetEdge(temporaryPart(split.head()), temporaryPart(split.tail()));
  }

  @Override
  public Optional<Edge> createPartialEdge(StreetVertex from, StreetVertex to) {
    return super.createPartialEdge(from, to).map(it -> temporaryPart((StreetEdge) it));
  }

  private OswStreetEdge part(StreetEdge plainPart) {
    plainPart.remove();
    return new OswStreetEdgeBuilder(plainPart)
      .withSpeedFactor(speedFactor)
      .withDelaySeconds(delayOfPart(plainPart))
      .buildAndConnect();
  }

  @Nullable
  private TemporaryOswStreetEdge temporaryPart(@Nullable StreetEdge plainPart) {
    if (plainPart == null) {
      return null;
    }
    plainPart.remove();
    return TemporaryOswStreetEdge.createAndConnect(
      this,
      plainPart,
      speedFactor,
      delayOfPart(plainPart)
    );
  }

  private float delayOfPart(StreetEdge part) {
    return (float) ((delaySeconds * part.getDistanceMeters()) / getDistanceMeters());
  }
}

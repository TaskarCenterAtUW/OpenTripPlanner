package org.opentripplanner.ext.opensidewalks;

import java.util.Optional;
import javax.annotation.Nullable;
import org.opentripplanner.street.linking.LinkingDirection;
import org.opentripplanner.street.model.edge.Edge;
import org.opentripplanner.street.model.edge.SplitStreetEdge;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.vertex.SplitterVertex;
import org.opentripplanner.street.model.vertex.StreetVertex;
import org.opentripplanner.street.search.TraverseModeSet;
import org.opentripplanner.street.search.state.State;

/**
 * A street edge built from an OpenSidewalks edge, which is walked at the cost a
 * {@link PedestrianCostProfile} gives it for the traveller of the request, and not at all if
 * that traveller can not use it. See {@link OswWalkCost}.
 * <p>
 * The parts this edge is split into, permanently where a stop is linked to it and temporarily
 * where a trip starts or ends partway along it, are costed like this edge. Each part gets the
 * share of the delay that corresponds to its share of the length, so that walking all of the
 * parts costs the same as walking this edge.
 */
public class OswStreetEdge extends StreetEdge {

  private final OswCosting costing;
  private final OswEdgeAttributes attributes;
  private final float delayShare;

  OswStreetEdge(OswStreetEdgeBuilder builder) {
    super(builder);
    this.costing = builder.costing();
    this.attributes = builder.attributes();
    this.delayShare = builder.delayShare();
  }

  static OswStreetEdge createAndConnect(OswStreetEdgeBuilder builder) {
    return connectToGraph(new OswStreetEdge(builder));
  }

  /**
   * The time it takes the traveller with the given parameter values to walk this edge at the
   * given speed in meters per second, or infinity if that traveller can not use it.
   *
   * @param parameters the parameter values of a request, or {@code null} for the traveller the
   *                   graph was built for
   */
  public double walkDurationSeconds(@Nullable PedestrianCostParameters parameters, double speed) {
    return OswWalkCost.durationSeconds(
      this,
      attributes,
      delayShare,
      costing.spec(parameters),
      speed
    );
  }

  /** The share of the delay of the whole OpenSidewalks edge that this edge has. */
  public float delayShare() {
    return delayShare;
  }

  /** Whether every traveller the parameters of the profile allow can use this edge. */
  public boolean isUsableByAll() {
    return attributes.usableByAll();
  }

  @Override
  public State[] traverse(State s0) {
    var states = OswWalkCost.traverse(s0, this, costing, attributes, delayShare);
    return states != null ? states : super.traverse(s0);
  }

  /**
   * This is what OTP asks to find the edges a place can be linked to. Walking it is up to
   * {@link #traverse(State)}.
   */
  @Override
  public boolean canTraverse(TraverseModeSet modes) {
    return super.canTraverse(modes) && OswLinking.isLinkable(costing, attributes);
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
      .withCosting(costing, attributes)
      .withDelayShare(delayShareOfPart(plainPart))
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
      costing,
      attributes,
      delayShareOfPart(plainPart)
    );
  }

  private float delayShareOfPart(StreetEdge part) {
    return (float) ((delayShare * part.getDistanceMeters()) / getDistanceMeters());
  }
}

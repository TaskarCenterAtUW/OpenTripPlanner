package org.opentripplanner.ext.opensidewalks;

import java.util.Collection;
import javax.annotation.Nullable;
import org.opentripplanner.street.model.edge.ExtensionRequestContext;

/**
 * Which OpenSidewalks edges a place can be linked to.
 * <p>
 * The places of a request, where the trip starts and ends, are linked to the nearest edge the
 * traveller of the request can use. OTP links them in {@code LinkingContextFactory}, on the
 * thread of the request, and says here who the traveller is while it does.
 * <p>
 * Anything else, such as the stops linked when the graph is built, is there for all travellers
 * and is linked to the nearest edge all of them can use.
 */
public final class OswLinking {

  private static final ThreadLocal<PedestrianCostParameters> TRAVELLER = new ThreadLocal<>();

  private OswLinking() {}

  /**
   * Link as the traveller with the given parameter values, until the returned scope is closed.
   *
   * @param parameters the parameter values of the request, or {@code null} if it has none
   */
  public static Scope linkAs(@Nullable PedestrianCostParameters parameters) {
    var previous = TRAVELLER.get();
    TRAVELLER.set(parameters == null ? PedestrianCostParameters.DEFAULT : parameters);
    return () -> {
      if (previous == null) {
        TRAVELLER.remove();
      } else {
        TRAVELLER.set(previous);
      }
    };
  }

  /**
   * Link as the traveller of the request with the given extension contexts, until the returned
   * scope is closed.
   */
  public static Scope linkFor(Collection<ExtensionRequestContext> extensions) {
    for (var extension : extensions) {
      if (extension instanceof PedestrianCostParameters parameters) {
        return linkAs(parameters);
      }
    }
    return linkAs(null);
  }

  static boolean isLinkable(OswCosting costing, OswEdgeAttributes attributes) {
    var traveller = TRAVELLER.get();
    return traveller == null
      ? attributes.usableByAll()
      : attributes.speedFactor(costing.spec(traveller)) > 0;
  }

  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}

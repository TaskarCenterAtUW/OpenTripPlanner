package org.opentripplanner.ext.opensidewalks.api;

import java.util.TreeMap;
import javax.annotation.Nullable;
import org.opentripplanner.apis.gtfs.generated.GraphQLTypes;
import org.opentripplanner.ext.opensidewalks.PedestrianCostParameters;
import org.opentripplanner.routing.api.request.preference.RoutingPreferencesBuilder;

/**
 * Maps the pedestrian cost parameter values of a plan query of the GTFS GraphQL API to the
 * request.
 */
public class PedestrianCostParametersMapper {

  /**
   * Add the values of the query to the ones the request already has from the router
   * configuration. A value of the query replaces one of the configuration.
   */
  public static void setPedestrianCost(
    RoutingPreferencesBuilder preferences,
    @Nullable GraphQLTypes.GraphQLWalkPreferencesInput walk
  ) {
    if (walk == null || walk.getGraphQLPedestrianCost() == null) {
      return;
    }
    var values = new TreeMap<String, String>();
    for (var extension : preferences.system().extensions()) {
      if (extension instanceof PedestrianCostParameters configured) {
        values.putAll(configured.values());
      }
    }
    for (var parameter : walk.getGraphQLPedestrianCost()) {
      values.put(parameter.getGraphQLName(), parameter.getGraphQLValue());
    }
    preferences.withSystem(system -> system.withExtension(PedestrianCostParameters.of(values)));
  }
}

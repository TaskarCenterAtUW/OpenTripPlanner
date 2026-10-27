package org.opentripplanner.ext.opensidewalks.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nullable;
import org.opentripplanner.apis.gtfs.generated.GraphQLTypes;
import org.opentripplanner.apis.support.InvalidInputException;
import org.opentripplanner.ext.opensidewalks.PedestrianCostParameters;
import org.opentripplanner.ext.opensidewalks.StreetNetwork;
import org.opentripplanner.routing.api.request.preference.RoutingPreferencesBuilder;

/**
 * Maps the pedestrian cost parameter values, and the street network, of a plan query of the GTFS
 * GraphQL API to the request.
 */
public class PedestrianCostParametersMapper {

  /**
   * Add the values of the query to the ones the request already has from the router
   * configuration, and say which network the request is on. A value of the query replaces one of
   * the configuration.
   */
  public static void setPedestrianCost(
    RoutingPreferencesBuilder preferences,
    @Nullable GraphQLTypes.GraphQLWalkPreferencesInput walk,
    @Nullable GraphQLTypes.GraphQLStreetNetwork network
  ) {
    Map<String, String> values = new LinkedHashMap<>();
    if (walk != null && walk.getGraphQLPedestrianCost() != null) {
      for (var parameter : walk.getGraphQLPedestrianCost()) {
        values.put(parameter.getGraphQLName(), parameter.getGraphQLValue());
      }
    }
    addPedestrianCost(preferences, values, network == null ? null : map(network));
  }

  /**
   * Add the given values of a request to the ones it has from the router configuration, and say
   * which network the request is on. A request with values that does not say is on the
   * OpenSidewalks edges. One with neither keeps what the router configuration has, which says
   * nothing about the network.
   *
   * @throws InvalidInputException if the request has values and says it is on the other streets,
   *                               where they have no effect
   */
  public static void addPedestrianCost(
    RoutingPreferencesBuilder preferences,
    Map<String, String> ofTheRequest,
    @Nullable StreetNetwork network
  ) {
    if (ofTheRequest.isEmpty() && network == null) {
      return;
    }
    if (network == StreetNetwork.STREETS && !ofTheRequest.isEmpty()) {
      throw new InvalidInputException(
        "Pedestrian cost parameters only apply to trips on the OPEN_SIDEWALKS network."
      );
    }
    var values = new TreeMap<String, String>();
    for (var extension : preferences.system().extensions()) {
      if (extension instanceof PedestrianCostParameters configured) {
        values.putAll(configured.values());
      }
    }
    values.putAll(ofTheRequest);
    var on = network == null ? StreetNetwork.OPEN_SIDEWALKS : network;
    preferences.withSystem(system -> system.withExtension(PedestrianCostParameters.of(values, on)));
  }

  private static StreetNetwork map(GraphQLTypes.GraphQLStreetNetwork network) {
    return switch (network) {
      case OPEN_SIDEWALKS -> StreetNetwork.OPEN_SIDEWALKS;
      case STREETS -> StreetNetwork.STREETS;
    };
  }
}

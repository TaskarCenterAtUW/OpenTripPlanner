package org.opentripplanner.ext.opensidewalks.api;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opentripplanner.apis.gtfs.generated.GraphQLTypes;
import org.opentripplanner.apis.support.InvalidInputException;
import org.opentripplanner.ext.opensidewalks.PedestrianCostParameters;
import org.opentripplanner.ext.opensidewalks.StreetNetwork;
import org.opentripplanner.routing.api.request.preference.RoutingPreferences;

class PedestrianCostParametersMapperTest {

  @Test
  void theValuesOfTheQueryAreSetOnTheRequestWhichIsThenOnOpenSidewalks() {
    var preferences = RoutingPreferences.of();

    PedestrianCostParametersMapper.setPedestrianCost(
      preferences,
      walk(parameter("uphill", "0.083"), parameter("avoid_curbs", "true")),
      null
    );

    assertThat(preferences.build().system().extensions()).containsExactly(
      PedestrianCostParameters.of(
        Map.of("uphill", "0.083", "avoid_curbs", "true"),
        StreetNetwork.OPEN_SIDEWALKS
      )
    );
  }

  @Test
  void aQueryCanAskForOpenSidewalksWithoutValues() {
    var configured = PedestrianCostParameters.of(Map.of("uphill", "0.1"));
    var preferences = RoutingPreferences.of().withSystem(s -> s.withExtension(configured));

    PedestrianCostParametersMapper.setPedestrianCost(
      preferences,
      null,
      GraphQLTypes.GraphQLStreetNetwork.OPEN_SIDEWALKS
    );

    assertThat(preferences.build().system().extensions()).containsExactly(
      PedestrianCostParameters.of(Map.of("uphill", "0.1"), StreetNetwork.OPEN_SIDEWALKS)
    );
  }

  @Test
  void aQueryCanAskForTheOtherStreets() {
    var preferences = RoutingPreferences.of();

    PedestrianCostParametersMapper.setPedestrianCost(
      preferences,
      null,
      GraphQLTypes.GraphQLStreetNetwork.STREETS
    );

    assertThat(preferences.build().system().extensions()).containsExactly(
      PedestrianCostParameters.of(Map.of(), StreetNetwork.STREETS)
    );
  }

  @Test
  void valuesOnTheOtherStreetsAreRefused() {
    var preferences = RoutingPreferences.of();

    assertThrows(InvalidInputException.class, () ->
      PedestrianCostParametersMapper.setPedestrianCost(
        preferences,
        walk(parameter("uphill", "0.083")),
        GraphQLTypes.GraphQLStreetNetwork.STREETS
      )
    );
  }

  @Test
  void theValuesOfTheQueryReplaceTheConfiguredOnes() {
    var configured = PedestrianCostParameters.of(Map.of("uphill", "0.1", "downhill", "0.1"));
    var preferences = RoutingPreferences.of().withSystem(s -> s.withExtension(configured));

    PedestrianCostParametersMapper.setPedestrianCost(
      preferences,
      walk(parameter("uphill", "0.083")),
      null
    );

    assertThat(preferences.build().system().extensions()).containsExactly(
      PedestrianCostParameters.of(
        Map.of("uphill", "0.083", "downhill", "0.1"),
        StreetNetwork.OPEN_SIDEWALKS
      )
    );
  }

  /** Values in the router configuration alone do not move a request to OpenSidewalks. */
  @Test
  void aQueryWithoutValuesOrNetworkLeavesTheRequestAsItIs() {
    var configured = PedestrianCostParameters.of(Map.of("uphill", "0.1"));
    var preferences = RoutingPreferences.of().withSystem(s -> s.withExtension(configured));

    PedestrianCostParametersMapper.setPedestrianCost(preferences, null, null);
    PedestrianCostParametersMapper.setPedestrianCost(
      preferences,
      new GraphQLTypes.GraphQLWalkPreferencesInput(Map.of("speed", 1.3)),
      null
    );

    assertThat(preferences.build().system().extensions()).containsExactly(configured);
  }

  @SafeVarargs
  private static GraphQLTypes.GraphQLWalkPreferencesInput walk(Map<String, Object>... parameters) {
    return new GraphQLTypes.GraphQLWalkPreferencesInput(
      Map.of("pedestrianCost", List.of(parameters))
    );
  }

  private static Map<String, Object> parameter(String name, String value) {
    return Map.of("name", name, "value", value);
  }
}

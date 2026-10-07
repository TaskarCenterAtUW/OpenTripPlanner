package org.opentripplanner.ext.opensidewalks.api;

import static com.google.common.truth.Truth.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opentripplanner.apis.gtfs.generated.GraphQLTypes;
import org.opentripplanner.ext.opensidewalks.PedestrianCostParameters;
import org.opentripplanner.routing.api.request.preference.RoutingPreferences;

class PedestrianCostParametersMapperTest {

  @Test
  void theValuesOfTheQueryAreSetOnTheRequest() {
    var preferences = RoutingPreferences.of();

    PedestrianCostParametersMapper.setPedestrianCost(
      preferences,
      walk(parameter("uphill", "0.083"), parameter("avoid_curbs", "true"))
    );

    assertThat(preferences.build().system().extensions()).containsExactly(
      PedestrianCostParameters.of(Map.of("uphill", "0.083", "avoid_curbs", "true"))
    );
  }

  @Test
  void theValuesOfTheQueryReplaceTheConfiguredOnes() {
    var configured = PedestrianCostParameters.of(Map.of("uphill", "0.1", "downhill", "0.1"));
    var preferences = RoutingPreferences.of().withSystem(s -> s.withExtension(configured));

    PedestrianCostParametersMapper.setPedestrianCost(
      preferences,
      walk(parameter("uphill", "0.083"))
    );

    assertThat(preferences.build().system().extensions()).containsExactly(
      PedestrianCostParameters.of(Map.of("uphill", "0.083", "downhill", "0.1"))
    );
  }

  @Test
  void aQueryWithoutValuesLeavesTheRequestAsItIs() {
    var configured = PedestrianCostParameters.of(Map.of("uphill", "0.1"));
    var preferences = RoutingPreferences.of().withSystem(s -> s.withExtension(configured));

    PedestrianCostParametersMapper.setPedestrianCost(preferences, null);
    PedestrianCostParametersMapper.setPedestrianCost(
      preferences,
      new GraphQLTypes.GraphQLWalkPreferencesInput(Map.of("speed", 1.3))
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

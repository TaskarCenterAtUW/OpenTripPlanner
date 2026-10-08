package org.opentripplanner.ext.opensidewalks.api;

import static com.google.common.truth.Truth.assertThat;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLEnumValueDefinition;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opentripplanner._support.time.ZoneIds;
import org.opentripplanner.api.model.transit.DefaultFeedIdMapper;
import org.opentripplanner.apis.support.graphql.injectdoc.ApiDocumentationProfile;
import org.opentripplanner.apis.transmodel.TransmodelGraphQLSchemaFactory;
import org.opentripplanner.ext.opensidewalks.OswCosting;
import org.opentripplanner.ext.opensidewalks.OswModule;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.TransitTuningParametersTestFactory;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.test.support.ResourceLoader;

class TransmodelPedestrianCostTest {

  private static final ResourceLoader RESOURCES = ResourceLoader.of(OswCosting.class);

  private final GraphQLSchema schema = new TransmodelGraphQLSchemaFactory(
    RouteRequest.defaultValue(),
    ZoneIds.OSLO,
    TransitTuningParametersTestFactory.forTest(),
    new DefaultFeedIdMapper(),
    ApiDocumentationProfile.DEFAULT
  ).create();

  @Test
  void theNamesOfTheParametersOfTheProfileAreAnEnumeration() {
    var graph = new Graph();
    new OswModule(
      graph,
      List.of(RESOURCES.file("tiny")),
      OswCosting.fromFile(RESOURCES.file("ws-prod.json"), Map.of())
    ).buildGraph();

    var names = (GraphQLEnumType) nameType(
      TransmodelPedestrianCost.withParameterNamesOf(graph, schema)
    );

    assertThat(names.getValues().stream().map(GraphQLEnumValueDefinition::getName))
      .containsAtLeast("uphill", "downhill", "avoid_curbs", "street_avoidance")
      .inOrder();
    assertThat(names.getValue("uphill").getDescription()).contains("A number from 0.04 to 0.15");
    assertThat(names.getValue("avoid_curbs").getDescription()).contains("`true` or `false`");
  }

  @Test
  void withoutAProfileTheNameIsText() {
    var same = TransmodelPedestrianCost.withParameterNamesOf(new Graph(), schema);

    assertThat(same).isSameInstanceAs(schema);
    assertThat(GraphQLTypeUtil.simplePrint(nameType(same))).isEqualTo("String");
  }

  private static GraphQLType nameType(GraphQLSchema schema) {
    var parameter = (GraphQLInputObjectType) schema.getType("PedestrianCostParameterInput");
    return GraphQLTypeUtil.unwrapNonNull(parameter.getField("name").getType());
  }
}

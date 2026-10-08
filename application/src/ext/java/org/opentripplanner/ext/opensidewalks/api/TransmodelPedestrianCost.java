package org.opentripplanner.ext.opensidewalks.api;

import graphql.Scalars;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLSchemaElement;
import graphql.schema.GraphQLTypeVisitorStub;
import graphql.schema.SchemaTransformer;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.opentripplanner.ext.opensidewalks.OswCosting;
import org.opentripplanner.routing.api.request.preference.RoutingPreferencesBuilder;
import org.opentripplanner.street.graph.Graph;

/**
 * The pedestrian cost parameter values of a trip query of the Transmodel GraphQL API: the
 * argument, and its mapping to the request.
 */
public class TransmodelPedestrianCost {

  private static final String NAME = "pedestrianCost";

  private static final String PARAMETER_TYPE = "PedestrianCostParameterInput";
  private static final String PARAMETER_NAME = "name";
  private static final Pattern GRAPHQL_NAME = Pattern.compile("[_A-Za-z][_0-9A-Za-z]*");

  private static final GraphQLInputObjectType PARAMETER = GraphQLInputObjectType.newInputObject()
    .name(PARAMETER_TYPE)
    .description(
      "The value of a parameter of the pedestrian cost profile that OpenSidewalks streets are " +
        "costed with. This is part of the OpenSidewalks sandbox feature."
    )
    .field(f ->
      f
        .name(PARAMETER_NAME)
        .description(
          "The name the profile gives the parameter, for example `uphill` or `avoid_curbs`."
        )
        .type(new GraphQLNonNull(Scalars.GraphQLString))
    )
    .field(f ->
      f
        .name("value")
        .description("The value as text, for example `0.083` or `true`.")
        .type(new GraphQLNonNull(Scalars.GraphQLString))
    )
    .build();

  public static final GraphQLArgument ARGUMENT = GraphQLArgument.newArgument()
    .name(NAME)
    .description(
      "What the traveller can manage on OpenSidewalks streets, as values of the parameters of " +
        "the pedestrian cost profile the streets are costed with: the steepest uphill grade, " +
        "for example. A parameter that is left out has the value the server is configured " +
        "with. This has no effect unless the server has streets built by the OpenSidewalks " +
        "sandbox feature."
    )
    .type(new GraphQLList(new GraphQLNonNull(PARAMETER)))
    .build();

  /**
   * The schema with the names of the parameters of the pedestrian cost profile of the graph as
   * an enumeration, so that a client can offer them and a query with another name is refused
   * when it is read. The schema is returned as it is if the graph has no profile, or if a name
   * can not be the name of an enumeration value.
   */
  public static GraphQLSchema withParameterNamesOf(Graph graph, GraphQLSchema schema) {
    var parameters = OswCosting.of(graph)
      .map(costing -> costing.profile().parameterDescriptions())
      .orElse(Map.of());
    if (
      parameters.isEmpty() ||
      !parameters
        .keySet()
        .stream()
        .allMatch(name -> GRAPHQL_NAME.matcher(name).matches())
    ) {
      return schema;
    }
    var names = GraphQLEnumType.newEnum()
      .name("PedestrianCostParameterName")
      .description("The parameters of the pedestrian cost profile of this server.");
    parameters.forEach((name, description) -> names.value(name, name, description));
    var type = new GraphQLNonNull(names.build());

    return SchemaTransformer.transformSchema(
      schema,
      new GraphQLTypeVisitorStub() {
        @Override
        public TraversalControl visitGraphQLInputObjectField(
          GraphQLInputObjectField field,
          TraverserContext<GraphQLSchemaElement> context
        ) {
          if (
            field.getName().equals(PARAMETER_NAME) &&
            context.getParentNode() instanceof GraphQLInputObjectType parent &&
            parent.getName().equals(PARAMETER_TYPE)
          ) {
            return changeNode(context, field.transform(f -> f.type(type)));
          }
          return TraversalControl.CONTINUE;
        }
      }
    );
  }

  /** Add the values of the query to the ones the request has from the router configuration. */
  public static void map(
    DataFetchingEnvironment environment,
    RoutingPreferencesBuilder preferences
  ) {
    List<Map<String, Object>> parameters = environment.getArgument(NAME);
    if (parameters == null) {
      return;
    }
    Map<String, String> values = new LinkedHashMap<>();
    for (var parameter : parameters) {
      values.put((String) parameter.get("name"), (String) parameter.get("value"));
    }
    PedestrianCostParametersMapper.addPedestrianCost(preferences, values);
  }
}

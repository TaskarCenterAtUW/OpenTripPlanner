package org.opentripplanner.apis.transmodel.configure;

import dagger.Module;
import dagger.Provides;
import graphql.schema.GraphQLSchema;
import jakarta.inject.Singleton;
import javax.annotation.Nullable;
import org.opentripplanner.api.model.transit.DefaultFeedIdMapper;
import org.opentripplanner.api.model.transit.FeedScopedIdMapper;
import org.opentripplanner.api.model.transit.HideFeedIdMapper;
import org.opentripplanner.apis.transmodel.TransmodelGraphQLSchemaFactory;
import org.opentripplanner.apis.transmodel.mapping.FixedFeedIdGenerator;
import org.opentripplanner.ext.opensidewalks.api.TransmodelPedestrianCost;
import org.opentripplanner.framework.time.ZoneIdFallback;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.standalone.config.RouterConfig;
import org.opentripplanner.standalone.configure.StaticRouteRequestDefaults;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.transit.service.TransitRepository;

@Module
public class TransmodelSchemaModule {

  @Provides
  @Singleton
  @Nullable
  @TransmodelSchema
  public GraphQLSchema provideTransmodelSchema(
    @StaticRouteRequestDefaults RouteRequest defaultRouteRequest,
    TransitRepository transitRepository,
    RouterConfig routerConfig,
    Graph graph
  ) {
    FeedScopedIdMapper feedIdMapper;
    if (routerConfig.transmodelApi().hideFeedId()) {
      String fixedFeedId = FixedFeedIdGenerator.generate(transitRepository.getAgencies());
      feedIdMapper = new HideFeedIdMapper(fixedFeedId);
    } else {
      feedIdMapper = new DefaultFeedIdMapper();
    }

    TransmodelGraphQLSchemaFactory factory = new TransmodelGraphQLSchemaFactory(
      defaultRouteRequest,
      ZoneIdFallback.zoneId(transitRepository.getTimeZone()),
      routerConfig.transitTuningConfig(),
      feedIdMapper,
      routerConfig.server().apiDocumentationProfile()
    );

    return TransmodelPedestrianCost.withParameterNamesOf(graph, factory.create());
  }
}

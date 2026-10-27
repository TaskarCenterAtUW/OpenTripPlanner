package org.opentripplanner.apis.gtfs.datafetchers;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import org.locationtech.jts.geom.Geometry;
import org.opentripplanner.apis.gtfs.generated.GraphQLDataFetchers;
import org.opentripplanner.ext.opensidewalks.PedestrianSegment;

public class PedestrianSegmentImpl implements GraphQLDataFetchers.GraphQLPedestrianSegment {

  @Override
  public DataFetcher<Boolean> curbRamps() {
    return environment -> getSource(environment).curbRamps();
  }

  @Override
  public DataFetcher<String> description() {
    return environment -> getSource(environment).description();
  }

  @Override
  public DataFetcher<Double> distance() {
    return environment -> getSource(environment).distanceMeters();
  }

  @Override
  public DataFetcher<Boolean> elevator() {
    return environment -> getSource(environment).elevator();
  }

  @Override
  public DataFetcher<String> footway() {
    return environment -> getSource(environment).footway();
  }

  @Override
  public DataFetcher<String> fromLevel() {
    return environment -> getSource(environment).fromLevel();
  }

  @Override
  public DataFetcher<Geometry> geometry() {
    return environment -> getSource(environment).geometry();
  }

  @Override
  public DataFetcher<String> highway() {
    return environment -> getSource(environment).highway();
  }

  @Override
  public DataFetcher<Double> incline() {
    return environment -> getSource(environment).incline();
  }

  @Override
  public DataFetcher<Boolean> indoor() {
    return environment -> getSource(environment).indoor();
  }

  @Override
  public DataFetcher<Double> levelChange() {
    return environment -> getSource(environment).levelChange();
  }

  @Override
  public DataFetcher<String> name() {
    return environment -> getSource(environment).name();
  }

  @Override
  public DataFetcher<String> pathwayMode() {
    return environment -> getSource(environment).pathwayMode();
  }

  @Override
  public DataFetcher<String> surface() {
    return environment -> getSource(environment).surface();
  }

  @Override
  public DataFetcher<String> toLevel() {
    return environment -> getSource(environment).toLevel();
  }

  private PedestrianSegment getSource(DataFetchingEnvironment environment) {
    return environment.getSource();
  }
}

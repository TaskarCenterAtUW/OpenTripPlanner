package org.opentripplanner.ext.opensidewalks;

import static org.opentripplanner.inspector.vector.KeyValue.kv;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.opentripplanner.apis.support.mapping.PropertyMapper;
import org.opentripplanner.inspector.vector.KeyValue;
import org.opentripplanner.inspector.vector.LayerBuilder;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.model.edge.Edge;

/**
 * A vector tile layer of the OpenSidewalks edges of the graph, one feature per edge in the
 * direction it is mapped in the dataset, with the properties the AccessMap web app styles
 * sidewalks and crossings by: {@code footway}, {@code subclass} (the {@code highway} of the edge),
 * {@code incline}, {@code curbramps}, {@code crossing} and {@code elevator}.
 * <p>
 * The map shows the edges as they are in the graph, so an edge that no traveller can use in
 * either direction is not on it. An edge that only some travellers can use is, so that the map
 * can show who it is for.
 */
public class OswEdgeLayerBuilder extends LayerBuilder<OswEdgeLayerBuilder.MapEdge> {

  private final Graph graph;

  public OswEdgeLayerBuilder(Graph graph, String layerName, double expansionFactor) {
    super(new Mapper(), layerName, expansionFactor);
    this.graph = graph;
  }

  @Override
  protected List<Geometry> findGeometries(Envelope query) {
    var geometries = new ArrayList<Geometry>();
    for (Edge edge : graph.findEdges(query)) {
      if (!(edge instanceof OswStreetEdge osw)) {
        continue;
      }
      var attributes = osw.attributes();
      Geometry geometry;
      double incline;
      if (!osw.isBack()) {
        geometry = osw.getGeometry().copy();
        incline = attributes.incline();
      } else if (hasMappedDirection(osw)) {
        // That direction is the feature
        continue;
      } else {
        // Nobody can use the edge the way it is mapped, so it is only in the graph the other way
        geometry = osw.getGeometry().reverse();
        incline = -attributes.incline();
      }
      geometry.setUserData(new MapEdge(attributes, incline, osw.getDistanceMeters()));
      geometries.add(geometry);
    }
    return geometries;
  }

  /** Whether the graph has the edge the given back edge is the other direction of. */
  private static boolean hasMappedDirection(OswStreetEdge back) {
    var tags = back.attributes().tags();
    for (Edge edge : back.getToVertex().getOutgoing()) {
      if (
        edge instanceof OswStreetEdge osw &&
        !osw.isBack() &&
        osw.getToVertex() == back.getFromVertex() &&
        osw.attributes().tags() == tags
      ) {
        return true;
      }
    }
    return false;
  }

  /**
   * An OpenSidewalks edge as it is shown on the map.
   *
   * @param incline the grade in the direction the edge is mapped, NaN if unknown
   */
  record MapEdge(OswEdgeAttributes attributes, double incline, double lengthMeters) {}

  private static class Mapper extends PropertyMapper<MapEdge> {

    @Override
    protected Collection<KeyValue> map(MapEdge edge) {
      var tags = edge.attributes().tags();
      return List.of(
        kv("footway", tags.footway()),
        kv("subclass", tags.highway()),
        kv("incline", Double.isNaN(edge.incline()) ? null : edge.incline()),
        kv("curbramps", flag(edge.attributes().curbRamps())),
        kv("crossing", tags.crossing()),
        kv("elevator", tags.elevator() ? 1 : 0),
        kv("indoor", tags.indoor() ? 1 : 0),
        kv("name", tags.name()),
        kv("description", tags.description()),
        kv("surface", tags.surface()),
        kv("length", edge.lengthMeters())
      );
    }

    @Nullable
    private static Integer flag(CurbRamps curbRamps) {
      return switch (curbRamps) {
        case YES -> 1;
        case NO -> 0;
        case UNKNOWN -> null;
      };
    }
  }
}

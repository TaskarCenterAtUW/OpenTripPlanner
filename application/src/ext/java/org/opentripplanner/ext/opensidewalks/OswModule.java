package org.opentripplanner.ext.opensidewalks;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.opentripplanner.core.model.i18n.I18NString;
import org.opentripplanner.ext.opensidewalks.config.OpenSidewalksConfig;
import org.opentripplanner.graph_builder.model.GraphBuilderModule;
import org.opentripplanner.street.geometry.GeometryUtils;
import org.opentripplanner.street.geometry.SphericalDistanceLibrary;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.edge.StreetEdgeBuilder;
import org.opentripplanner.street.model.vertex.StreetVertex;
import org.opentripplanner.street.search.TraverseMode;
import org.opentripplanner.streetadapter.VertexFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds pedestrian street edges from OpenSidewalks datasets. Each edge of a dataset becomes a
 * pair of street edges, one in each direction, that can only be walked.
 * <p>
 * If there is a pedestrian cost profile, the edges become {@link OswStreetEdge}s, which are walked
 * at the cost the profile gives them for the traveller of each request. A direction of an edge
 * that no traveller the parameters of the profile allow can use is left out of the graph.
 */
public class OswModule implements GraphBuilderModule {

  private static final Logger LOG = LoggerFactory.getLogger(OswModule.class);

  /** Edges without a kind of way, and ways that do not exist yet, are not built. */
  private static final Set<String> UNUSABLE_HIGHWAYS = Set.of("construction", "proposed");

  private final Graph graph;
  private final List<File> sources;

  @Nullable
  private final OswCosting costing;

  /** The travellers at the corners of the ranges of the parameters of the profile. */
  private final List<PedestrianCostSpec> extremeTravellers;

  private final VertexFactory vertexFactory;

  private int builtEdges = 0;
  private int edgesNotForAll = 0;
  private int impassableEdges = 0;
  private int unusableEdges = 0;
  private int roadEdges = 0;
  private boolean fasterThanWalking = false;

  /**
   * @param costing the profile to cost the edges with, or {@code null} to leave the cost of
   *                walking them to OTP
   */
  public OswModule(Graph graph, List<File> sources, @Nullable OswCosting costing) {
    this.graph = graph;
    this.sources = List.copyOf(sources);
    this.costing = costing;
    this.extremeTravellers = costing == null ? List.of() : costing.profile().extremes();
    this.vertexFactory = new VertexFactory(graph);
  }

  /**
   * @return the module building the datasets of the given configuration, or {@code null} if it
   * has none.
   */
  @Nullable
  public static OswModule create(OpenSidewalksConfig config, Graph graph, File baseDirectory) {
    if (config.sources().isEmpty()) {
      return null;
    }
    var sources = config
      .sources()
      .stream()
      .map(it -> resolve(baseDirectory, it))
      .toList();
    OswCosting costing =
      config.profile() == null
        ? null
        : OswCosting.fromFile(resolve(baseDirectory, config.profile()), config.parameters());
    return new OswModule(graph, sources, costing);
  }

  private static File resolve(File baseDirectory, String path) {
    File file = new File(path);
    return file.isAbsolute() ? file : new File(baseDirectory, path);
  }

  @Override
  public void checkInputs() {
    for (File source : sources) {
      if (!source.exists()) {
        throw new IllegalArgumentException("OpenSidewalks dataset not found: " + source);
      }
    }
  }

  @Override
  public void buildGraph() {
    if (costing != null) {
      var spec = costing.spec(null);
      LOG.info(
        "Costing OpenSidewalks edges with pedestrian cost profile {} (profileId {}). A request without parameter values has {} (runSpecId {})",
        spec.profile().name(),
        spec.profile().profileId(),
        spec.parameterValues(),
        spec.runSpecId()
      );
    }
    if (costing != null) {
      shareTheGraphWithOtherStreets(costing);
    }
    for (int i = 0; i < sources.size(); i++) {
      // Node ids are only unique within a dataset
      String labelPrefix = sources.size() == 1 ? "osw:" : "osw:" + i + ":";
      build(sources.get(i), labelPrefix);
    }
    graph.hasStreets = true;
    if (roadEdges > 0) {
      if (costing != null) {
        costing.setHasRoads();
      }
      LOG.info(
        "{} of the street edges from OpenSidewalks data are roads, which bicycles and cars can use in both directions.",
        roadEdges
      );
    }
    LOG.info(
      "Built {} street edges from OpenSidewalks data, {} of which only some travellers can use. Left out {} that no traveller can walk and are not roads, and {} that are not usable ways.",
      builtEdges,
      edgesNotForAll,
      impassableEdges,
      unusableEdges
    );
    if (fasterThanWalking) {
      LOG.warn(
        "The pedestrian cost profile makes some edges faster than the walking speed of the request. Street searches may then miss the best path."
      );
    }
  }

  /**
   * The streets that are in the graph before the OpenSidewalks edges are from other data, and are
   * not joined to the OpenSidewalks edges. If people can walk them, a request travels on one of
   * the two networks from here on, see {@link OtherStreet}.
   */
  private void shareTheGraphWithOtherStreets(OswCosting costing) {
    var otherStreet = new OtherStreet(costing);
    int streets = 0;
    List<StreetEdge> edges = new ArrayList<>();
    for (StreetEdge edge : graph.findEdges(StreetEdge.class)) {
      edges.add(edge);
      if (edge.getPermission().allows(TraverseMode.WALK)) {
        streets++;
      }
    }
    if (streets > 0) {
      // Every street, so that a request on the OpenSidewalks edges does not drive on any of them
      edges.forEach(edge -> edge.setCostExtension(otherStreet));
      costing.setBesideOtherStreets();
      LOG.info(
        "The graph has {} street edges from other data that can be walked. A request travels on the OpenSidewalks edges if it says so, and otherwise on the other streets.",
        streets
      );
    }
  }

  private void build(File source, String labelPrefix) {
    LOG.info("Reading OpenSidewalks dataset {}", source);
    var dataset = OswDataset.read(source);
    unusableEdges += dataset.skippedEdges();
    Map<OswDataset.Node, StreetVertex> vertices = new HashMap<>();
    for (var edge : dataset.edges()) {
      String highway = edge.tags().get("highway");
      if (highway == null || UNUSABLE_HIGHWAYS.contains(highway)) {
        unusableEdges++;
        continue;
      }
      var from = vertices.computeIfAbsent(edge.from(), n -> vertex(n, labelPrefix));
      var to = vertices.computeIfAbsent(edge.to(), n -> vertex(n, labelPrefix));
      LineString geometry = GeometryUtils.makeLineString(edge.coordinates());
      double length = edge.number("length");
      if (!(length >= 0)) {
        length = length(edge.coordinates());
      }
      double incline = edge.number("incline");
      var curbRamps = CurbRamps.of(
        edge.tags().get("curbramps"),
        edge.from().kerb(),
        edge.to().kerb()
      );
      var tags = costing == null ? null : OswEdgeTags.of(edge.tags());
      // The incline of an edge describes it in the direction it is mapped. Walked the other way,
      // uphill is downhill.
      buildEdge(edge, from, to, geometry, length, incline, curbRamps, tags, false);
      buildEdge(edge, to, from, geometry.reverse(), length, -incline, curbRamps, tags, true);
    }
  }

  private StreetVertex vertex(OswDataset.Node node, String labelPrefix) {
    return vertexFactory.intersection(labelPrefix + node.id(), node.lon(), node.lat());
  }

  private void buildEdge(
    OswDataset.Edge edge,
    StreetVertex from,
    StreetVertex to,
    LineString geometry,
    double length,
    double incline,
    CurbRamps curbRamps,
    @Nullable OswEdgeTags tags,
    boolean back
  ) {
    boolean road = OswRoads.isRoad(edge.tags().get("highway"));
    StreetEdgeBuilder<?> builder;
    if (costing == null) {
      builder = new StreetEdgeBuilder<>();
    } else {
      int layer = costing.profile().layerIndex(edge.tags());
      int usableBy = 0;
      for (var traveller : extremeTravellers) {
        double speedFactor = traveller.speedFactor(layer, incline, length, curbRamps);
        if (speedFactor > 0) {
          usableBy++;
        }
        fasterThanWalking |= speedFactor > 1;
      }
      if (usableBy == 0 && !road) {
        impassableEdges++;
        return;
      }
      boolean usableByAll = usableBy == extremeTravellers.size();
      if (!usableByAll) {
        edgesNotForAll++;
      }
      builder = new OswStreetEdgeBuilder().withCosting(
        costing,
        new OswEdgeAttributes(layer, incline, length, curbRamps, usableByAll, tags)
      );
    }
    String name = edge.tags().get("name");
    builder
      .withFromVertex(from)
      .withToVertex(to)
      .withGeometry(geometry)
      .withMilliMeterLength((int) Math.round(length * 1000))
      .withName(I18NString.of(name != null ? name : derivedName(edge.tags())))
      .withBogusName(name == null)
      .withPermission(OswRoads.permission(edge.tags()))
      .withBack(back);
    if (road) {
      builder.withCarSpeed(OswRoads.carSpeed(edge.tags()));
      roadEdges++;
    }
    builder.buildAndConnect();
    builtEdges++;
  }

  /** What to call an edge without a name: the kind of footway it is, or else the kind of way. */
  private static String derivedName(Map<String, String> tags) {
    String footway = tags.get("footway");
    return footway != null ? footway : tags.get("highway");
  }

  private static double length(List<Coordinate> coordinates) {
    double length = 0;
    for (int i = 1; i < coordinates.size(); i++) {
      length += SphericalDistanceLibrary.distance(coordinates.get(i - 1), coordinates.get(i));
    }
    return length;
  }
}

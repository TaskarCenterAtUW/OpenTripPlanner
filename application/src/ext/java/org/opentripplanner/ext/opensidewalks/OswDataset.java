package org.opentripplanner.ext.opensidewalks;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.annotation.Nullable;
import org.locationtech.jts.geom.Coordinate;

/**
 * The nodes and edges of an <a href="https://github.com/OpenSidewalks/OpenSidewalks-Schema">
 * OpenSidewalks</a> (OSW 0.2) dataset.
 * <p>
 * The input may be a zip file or a directory containing {@code *.nodes.geojson} and
 * {@code *.edges.geojson}, or a single {@code *.edges.geojson} file, whose sibling
 * {@code *.nodes.geojson} is used if present. Names are matched case-insensitively, may carry
 * an ".OSW" infix, and may be just {@code nodes.geojson} and {@code edges.geojson}. Points, lines, polygons and zones are ignored.
 * <p>
 * Nodes at the same position, to 7 decimal places, are treated as one node whatever their ids.
 * Some datasets rely on that: steps, for instance, may end on a node of their own that sits
 * exactly on the node of a sidewalk.
 */
class OswDataset {

  private static final double POSITION_SCALE = 1e7;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Map<String, Node> nodesById = new HashMap<>();
  private final Map<Long, Node> nodesByPosition = new HashMap<>();
  private final List<Edge> edges = new ArrayList<>();
  private int skippedEdges = 0;

  private OswDataset() {}

  static OswDataset read(File file) {
    var dataset = new OswDataset();
    try {
      dataset.readPath(file);
    } catch (IOException e) {
      throw new IllegalArgumentException("Could not read OpenSidewalks dataset " + file, e);
    }
    return dataset;
  }

  List<Edge> edges() {
    return edges;
  }

  /** The number of features in the edges file that are not usable edges. */
  int skippedEdges() {
    return skippedEdges;
  }

  private void readPath(File file) throws IOException {
    if (file.isDirectory()) {
      File nodes = null;
      File edges = null;
      File[] files = file.listFiles();
      for (File f : files == null ? new File[0] : files) {
        if (isNodesFile(f.getName())) {
          nodes = f;
        }
        if (isEdgesFile(f.getName())) {
          edges = f;
        }
      }
      readFiles(nodes, edges, file);
    } else if (isEdgesFile(file.getName())) {
      // The nodes file has the same name, with "nodes" in place of "edges"
      String name = file.getName();
      int i = name.toLowerCase(Locale.ROOT).lastIndexOf("edges.");
      File nodes = new File(
        file.getParentFile(),
        name.substring(0, i) + "nodes." + name.substring(i + 6)
      );
      readFiles(nodes.exists() ? nodes : null, file, file);
    } else {
      readZip(file);
    }
  }

  private void readFiles(@Nullable File nodes, @Nullable File edges, File source)
    throws IOException {
    if (edges == null) {
      throw new IllegalArgumentException("No *.edges.geojson found in " + source);
    }
    if (nodes != null) {
      try (InputStream in = new FileInputStream(nodes)) {
        forEachFeature(in, this::readNode);
      }
    }
    try (InputStream in = new FileInputStream(edges)) {
      forEachFeature(in, this::readEdge);
    }
  }

  private void readZip(File file) throws IOException {
    try (ZipFile zip = new ZipFile(file)) {
      ZipEntry nodes = null;
      ZipEntry edges = null;
      for (var it = zip.entries().asIterator(); it.hasNext(); ) {
        ZipEntry e = it.next();
        if (e.getName().contains("__MACOSX")) {
          continue;
        }
        if (isNodesFile(e.getName())) {
          nodes = e;
        }
        if (isEdgesFile(e.getName())) {
          edges = e;
        }
      }
      if (edges == null) {
        throw new IllegalArgumentException("No *.edges.geojson found in " + file);
      }
      if (nodes != null) {
        try (InputStream in = zip.getInputStream(nodes)) {
          forEachFeature(in, this::readNode);
        }
      }
      try (InputStream in = zip.getInputStream(edges)) {
        forEachFeature(in, this::readEdge);
      }
    }
  }

  private static boolean isEdgesFile(String name) {
    return name.toLowerCase(Locale.ROOT).matches("(.*\\.)?edges(\\.osw)?\\.geojson");
  }

  private static boolean isNodesFile(String name) {
    return name.toLowerCase(Locale.ROOT).matches("(.*\\.)?nodes(\\.osw)?\\.geojson");
  }

  /** Stream the features of a GeoJSON FeatureCollection without loading the whole file. */
  private static void forEachFeature(InputStream in, Consumer<JsonNode> handler)
    throws IOException {
    try (JsonParser parser = MAPPER.getFactory().createParser(in)) {
      if (parser.nextToken() != JsonToken.START_OBJECT) {
        throw new IOException("GeoJSON must be an object.");
      }
      while (parser.nextToken() == JsonToken.FIELD_NAME) {
        String field = parser.currentName();
        JsonToken token = parser.nextToken();
        if ("features".equals(field) && token == JsonToken.START_ARRAY) {
          while (parser.nextToken() == JsonToken.START_OBJECT) {
            handler.accept(parser.readValueAsTree());
          }
        } else {
          parser.skipChildren();
        }
      }
    }
  }

  private void readNode(JsonNode feature) {
    JsonNode geometry = feature.path("geometry");
    JsonNode properties = feature.path("properties");
    if (!"Point".equals(geometry.path("type").asText()) || !properties.isObject()) {
      return;
    }
    JsonNode position = geometry.path("coordinates");
    double lon = position.path(0).asDouble();
    double lat = position.path(1).asDouble();
    String id = properties.path("_id").asText(null);
    Map<String, String> tags = tags(properties);
    Node standing = nodesByPosition.get(positionKey(lon, lat));
    if (standing != null) {
      // Another node is already at this position: use that one wherever the id of this one
      // appears, and give it any tags, such as a kerb type, that only this one has.
      tags.forEach(standing.tags::putIfAbsent);
      if (id != null) {
        nodesById.put(id, standing);
      }
      return;
    }
    addNode(id, lon, lat, tags);
  }

  private void readEdge(JsonNode feature) {
    JsonNode geometry = feature.path("geometry");
    JsonNode properties = feature.path("properties");
    JsonNode positions = geometry.path("coordinates");
    if (
      !"LineString".equals(geometry.path("type").asText()) ||
      !properties.isObject() ||
      positions.size() < 2
    ) {
      skippedEdges++;
      return;
    }
    List<Coordinate> coordinates = new ArrayList<>(positions.size());
    for (JsonNode p : positions) {
      coordinates.add(new Coordinate(p.path(0).asDouble(), p.path(1).asDouble()));
    }
    Node from = endNode(properties.path("_u_id").asText(null), coordinates.getFirst());
    Node to = endNode(properties.path("_v_id").asText(null), coordinates.getLast());
    if (from == to && coordinates.size() == 2) {
      skippedEdges++;
      return;
    }
    edges.add(
      new Edge(properties.path("_id").asText(null), from, to, coordinates, tags(properties))
    );
  }

  /** The node an edge ends at, created from the edge geometry if the nodes file lacks it. */
  private Node endNode(@Nullable String id, Coordinate c) {
    Node known = id == null ? null : nodesById.get(id);
    if (known != null) {
      return known;
    }
    // If a node already stands where the edge ends, the edge joins that one
    Node standing = nodesByPosition.get(positionKey(c.x, c.y));
    if (standing != null) {
      if (id != null) {
        nodesById.put(id, standing);
      }
      return standing;
    }
    return addNode(id, c.x, c.y, new HashMap<>());
  }

  private Node addNode(@Nullable String id, double lon, double lat, Map<String, String> tags) {
    // A node without an id is named by its position
    String nodeId =
      id != null
        ? id
        : Math.round(lon * POSITION_SCALE) / POSITION_SCALE +
          ", " +
          Math.round(lat * POSITION_SCALE) / POSITION_SCALE;
    var node = new Node(nodeId, lon, lat, tags);
    nodesById.putIfAbsent(nodeId, node);
    nodesByPosition.put(positionKey(lon, lat), node);
    return node;
  }

  private static long positionKey(double lon, double lat) {
    return (
      (Math.round(lon * POSITION_SCALE) << 32) ^ (Math.round(lat * POSITION_SCALE) & 0xffffffffL)
    );
  }

  private static Map<String, String> tags(JsonNode properties) {
    Map<String, String> tags = new HashMap<>();
    properties.fields().forEachRemaining(e -> {
      JsonNode value = e.getValue();
      if (value != null && !value.isNull()) {
        tags.put(e.getKey(), value.isValueNode() ? value.asText() : value.toString());
      }
    });
    return tags;
  }

  record Node(String id, double lon, double lat, Map<String, String> tags) {
    /**
     * The kerb type of the node, or {@code null} if the node is not a kerb. A kerb barrier without
     * a type is "unknown".
     */
    @Nullable
    String kerb() {
      String kerb = tags.get("kerb");
      if (kerb != null) {
        return kerb.trim().toLowerCase(Locale.ROOT);
      }
      return "kerb".equals(tags.get("barrier")) ? "unknown" : null;
    }
  }

  /**
   * @param id          the id of the edge in the dataset, if it has one
   * @param coordinates the geometry of the edge, from the first node to the second
   */
  record Edge(
    @Nullable String id,
    Node from,
    Node to,
    List<Coordinate> coordinates,
    Map<String, String> tags
  ) {
    /** The value of a numeric property, or NaN if it is absent or not a number. */
    double number(String key) {
      String value = tags.get(key);
      if (value == null) {
        return Double.NaN;
      }
      try {
        return Double.parseDouble(value.trim());
      } catch (NumberFormatException e) {
        return Double.NaN;
      }
    }
  }
}

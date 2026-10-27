package org.opentripplanner.ext.opensidewalks;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.opentripplanner.framework.application.OTPFeature;
import org.opentripplanner.graph_builder.model.GraphBuilderModule;
import org.opentripplanner.graph_builder.module.transfer.api.RegularTransferParameters;
import org.opentripplanner.place.NearbyStopFinder;
import org.opentripplanner.place.nearbystopfinder.PatternConsideringNearbyStopFinder;
import org.opentripplanner.place.nearbystopfinder.StreetNearbyStopFinder;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.model.StreetMode;
import org.opentripplanner.street.model.edge.Edge;
import org.opentripplanner.street.model.vertex.TransitStopVertex;
import org.opentripplanner.transfer.regular.TransferRepository;
import org.opentripplanner.transfer.regular.model.PathTransfer;
import org.opentripplanner.transit.model.site.StopLocation;
import org.opentripplanner.transit.service.DefaultTransitService;
import org.opentripplanner.transit.service.TransitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the paths of transfers between stops for the travellers who can not use the paths OTP
 * found. It runs after OTP has found the transfers for the transfer requests of the build
 * configuration.
 * <p>
 * OTP keeps the paths between two stops side by side, and walks them again as the traveller of
 * each request to pick one. A traveller who can use none of them has no transfer, also when
 * there is another way. So here the stops within reach of a stop are searched for again and
 * again, each time by a less able traveller:
 * <ol>
 *   <li>The most able traveller finds every stop that anyone can transfer to.</li>
 *   <li>Each path found asks something of a traveller: the most that any of its edges does.</li>
 *   <li>A traveller just short of that, in one respect, can not walk the path, and searches for
 *   another way, or finds none.</li>
 * </ol>
 * This goes on until nothing new is found. The travellers who search are the ones that the
 * streets around the stop make a difference for, and where every path is one that all can use,
 * the first search is the last.
 * <p>
 * Transfers with a vehicle, such as a bicycle taken along, are found on the OpenSidewalks data
 * too, for each such transfer request of the build configuration, if the data has roads. The
 * profile only costs walking, so one search from each stop finds them.
 */
public class OswTransferModule implements GraphBuilderModule {

  private static final Logger LOG = LoggerFactory.getLogger(OswTransferModule.class);

  /**
   * A stop is not searched from more often than this. The number of travellers the streets make
   * a difference for can grow fast where paths are hard in several respects at once.
   */
  private static final int MAX_SEARCHES_PER_STOP = 50;

  private final Graph graph;
  private final TransitRepository transitRepository;
  private final TransferRepository transferRepository;
  private final RegularTransferParameters parameters;

  public OswTransferModule(
    Graph graph,
    TransitRepository transitRepository,
    TransferRepository transferRepository,
    RegularTransferParameters parameters
  ) {
    this.graph = graph;
    this.transitRepository = transitRepository;
    this.transferRepository = transferRepository;
    this.parameters = parameters;
  }

  @Override
  public void buildGraph() {
    var costing = OswCosting.of(graph);
    // The travellers walk like the first walking transfer request does, apart from what they
    // can manage
    var walk = parameters
      .requests()
      .stream()
      .filter(it -> it.journey().transfer().mode() == StreetMode.WALK)
      .findFirst();
    if (costing.isEmpty()) {
      return;
    }
    var finder = createNearbyStopFinder();
    if (walk.isPresent()) {
      findWalkingTransfers(costing.get(), walk.get(), finder);
    }
    if (costing.get().hasRoads()) {
      for (var request : parameters.requests()) {
        if (request.journey().transfer().mode() != StreetMode.WALK) {
          findTransfersWithAVehicle(request, finder);
        }
      }
    }
  }

  /**
   * One search from each stop on the OpenSidewalks data, as the given transfer request, for the
   * transfers with its mode.
   */
  private void findTransfersWithAVehicle(RouteRequest transferRequest, NearbyStopFinder finder) {
    var mode = transferRequest.journey().transfer().mode();
    var request = transferRequest
      .copyOf()
      .withPreferences(p ->
        p.withSystem(s -> s.withExtension(PedestrianCostParameters.onOpenSidewalks(Map.of())))
      )
      .buildDefault();
    var sites = transitRepository.getSiteRepository();
    Multimap<StopLocation, PathTransfer> added = HashMultimap.create();
    for (TransitStopVertex stopVertex : graph.getVerticesOfType(TransitStopVertex.class)) {
      var stop = sites.getRegularStop(stopVertex.getId());
      if (stop == null || stop.transfersNotAllowed()) {
        continue;
      }
      for (var nearby : finder.findNearbyStops(
        stopVertex,
        request,
        mode,
        false,
        durationLimit(mode),
        0
      )) {
        var to = sites.getStopLocation(nearby.stopId);
        if (!to.equals(stop)) {
          added.put(
            stop,
            new PathTransfer(stop, to, nearby.distance, nearby.edges, EnumSet.of(mode))
          );
        }
      }
    }
    transferRepository.addAllTransfersByStops(added);
    LOG.info("Found {} {} transfers on OpenSidewalks edges.", added.size(), mode);
  }

  private void findWalkingTransfers(
    OswCosting costing,
    RouteRequest walk,
    NearbyStopFinder finder
  ) {
    var travellers = new OswTravellers(costing.profile());
    var durationLimit = durationLimit(StreetMode.WALK);
    var sites = transitRepository.getSiteRepository();

    Multimap<StopLocation, PathTransfer> added = HashMultimap.create();
    int searches = 0;
    int stopsAtLimit = 0;
    for (TransitStopVertex stopVertex : graph.getVerticesOfType(TransitStopVertex.class)) {
      var stop = sites.getRegularStop(stopVertex.getId());
      if (stop == null || stop.transfersNotAllowed()) {
        continue;
      }
      Set<Path> paths = new HashSet<>();
      for (PathTransfer known : transferRepository.findTransfersByStop(stop)) {
        if (known.allowsMode(StreetMode.WALK) && known.getEdges() != null) {
          paths.add(new Path(known.to, known.getEdges()));
        }
      }
      Set<Path> asked = new HashSet<>();
      Set<Map<String, String>> searched = new HashSet<>();
      var queue = new ArrayDeque<Map<String, String>>();
      queue.add(travellers.mostAble());
      while (!queue.isEmpty()) {
        var traveller = queue.poll();
        if (!searched.add(traveller)) {
          continue;
        }
        if (searched.size() > MAX_SEARCHES_PER_STOP) {
          stopsAtLimit++;
          break;
        }
        searches++;
        var request = walk
          .copyOf()
          .withPreferences(p ->
            p.withSystem(s -> s.withExtension(PedestrianCostParameters.onOpenSidewalks(traveller)))
          )
          .buildDefault();
        for (var nearby : finder.findNearbyStops(
          stopVertex,
          request,
          StreetMode.WALK,
          false,
          durationLimit,
          0
        )) {
          var to = sites.getStopLocation(nearby.stopId);
          if (to.equals(stop)) {
            continue;
          }
          var path = new Path(to, nearby.edges);
          if (paths.add(path)) {
            added.put(
              stop,
              new PathTransfer(stop, to, nearby.distance, nearby.edges, EnumSet.of(StreetMode.WALK))
            );
          }
          if (asked.add(path)) {
            queue.addAll(travellers.justShortOf(traveller, attributes(nearby.edges)));
          }
        }
      }
    }
    transferRepository.addAllTransfersByStops(added);
    LOG.info(
      "Found {} more transfers on OpenSidewalks edges, for travellers who can not use the others, in {} searches.",
      added.size(),
      searches
    );
    if (stopsAtLimit > 0) {
      LOG.warn(
        "{} stops were searched from {} times without finding the transfers of every traveller.",
        stopsAtLimit,
        MAX_SEARCHES_PER_STOP
      );
    }
  }

  /** What the profile needs to know about the OpenSidewalks edges of a path. */
  private static List<OswEdgeAttributes> attributes(List<Edge> edges) {
    return edges
      .stream()
      .filter(OswStreetEdge.class::isInstance)
      .map(e -> ((OswStreetEdge) e).attributes())
      .distinct()
      .toList();
  }

  /** The stops within reach are found the way OTP finds them for its own transfers. */
  private NearbyStopFinder createNearbyStopFinder() {
    NearbyStopFinder finder = StreetNearbyStopFinder.of(null).build();
    if (OTPFeature.ConsiderPatternsForDirectTransfers.isOn()) {
      return new PatternConsideringNearbyStopFinder(
        new DefaultTransitService(transitRepository),
        finder
      );
    }
    return finder;
  }

  private Duration durationLimit(StreetMode mode) {
    var forMode = parameters.parametersForMode().get(mode);
    return forMode != null && forMode.maxDuration() != null
      ? forMode.maxDuration()
      : parameters.maxDuration();
  }

  /** A transfer is one path to a stop. Another path to the same stop is another transfer. */
  private record Path(StopLocation to, List<Edge> edges) {}
}

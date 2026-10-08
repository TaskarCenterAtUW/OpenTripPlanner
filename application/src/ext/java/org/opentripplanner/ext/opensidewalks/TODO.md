# OpenSidewalks sandbox: open work

What is not done, not decided, or not verified, as of October 2026. The user documentation is
[doc/user/sandbox/OpenSidewalks.md](../../../../../../../../doc/user/sandbox/OpenSidewalks.md), and
[README.md](README.md) describes the code.

## 1. Loading OpenStreetMap data alongside OpenSidewalks

**Status: prototyped, not merged, needs a decision on the approach.** The prototype is one commit
on the local branch `opensidewalks-with-osm`, on top of `opensidewalks-dynamic`.

### The problem

A deployment will probably need both: OpenStreetMap for driving and cycling, OpenSidewalks for
walking. Loaded together they are two walking networks that share no vertices.

- A walk can not change from one to the other.
- Which one a trip uses is up to which edge is nearer to where it starts. Next to a road that is
  often the OpenStreetMap centerline.
- On OpenStreetMap streets the pedestrian cost profile does not apply. A wheelchair traveller who
  is linked to one is routed with no incline or curb limits.
- Island pruning only runs when there is OpenStreetMap data, and then prunes OpenSidewalks edges
  too.

Measured in Seattle, 200 pairs of places and two travellers, a BBBike OpenStreetMap extract plus
TDEI dataset `54f968ac`:

|                                                | Walks found | Same as OpenSidewalks alone      |
|------------------------------------------------|-------------|----------------------------------|
| OpenSidewalks alone                            | 338         |                                  |
| Both, as the code on `opensidewalks-dynamic`   | 212         | 94 within 2% in length           |
| Both, prototype, island pruning off            | 338         | 338, to the meter and the second |
| Both, prototype, island pruning on (default)   | 363         | 338, and 25 more                 |

### What does not fix it

- **Exporting OpenSidewalks as OpenStreetMap and loading that.** OTP joins OpenStreetMap data by
  node id, so the two are still separate, and the edges would be costed by OTP's own rules
  without the profile.
- **Using the roads of an OpenSidewalks dataset for vehicles.** They are there for people on
  foot, and do not have what driving and cycling need: one-way streets, turn restrictions, access.
- **Taking the walking permission off the OpenStreetMap streets.** OTP links a trip by bicycle to
  the streets through the edges that can be walked, so cycling trips could no longer start.
- **Replacing the walkable OpenStreetMap edges with a class of our own.** Turn restrictions, the
  level information of stairs, and station platforms hold on to the edges they were made for.

### What the prototype does

People on foot walk the OpenSidewalks edges only. The other streets are for cars and for bicycles,
ridden or walked. A build setting, `openSidewalks.walkOnOtherStreets`, turns this off.

- `NotOnFoot` is set as the cost extension of every walkable street that is in the graph when the
  OpenSidewalks data is loaded. It refuses to be linked to and to be walked by people on foot.
- Outside the sandbox, in the `street` module: `StreetEdgeCostExtension` can refuse traversal and
  linking and say which extension the parts of a split edge get, and `StreetEdge` and
  `StreetEdgeBuilder` use that. About 35 lines.
- `VertexCreationService` links a trip with one's own bicycle through edges that can be ridden,
  when the feature is on. A request to "ride, or walk to transit" needs its origin on both
  networks, and OTP otherwise links bicycles through walkable edges.

### To decide and to do

- [ ] Is a change to `StreetEdge` acceptable? It is the hot path of every street search. It was
      not measured with the speed test.
- [ ] The bicycle linking changes for every graph when the feature is on, also one without
      OpenSidewalks data. Limit it to graphs that have such edges.
- [ ] Walking where there is no OpenSidewalks data. If the OpenStreetMap data covers more, people
      on foot can not walk in the rest. Applying the rule only inside the area of the dataset
      means joining the two networks along its edge, which nothing does yet.
- [ ] What OpenStreetMap has for people on foot is lost to them: squares and other areas, stairs,
      elevators, station entrances, and boarding locations from OpenStreetMap.
- [ ] Bicycle parks are linked for walking only, so they are probably not reachable from the
      OpenStreetMap streets. Not tested. The same may hold for rental stations.
- [ ] Walking a bicycle on real data was not checked. The Seattle trips by bicycle were all ridden.
- [ ] Island pruning: keep it and have places snapped away from isolated pieces of sidewalk (25
      more routes in the sample), or exempt OpenSidewalks edges to match the other engines? Today
      that is `islandPruning.islandWithoutStopsMaxSize` and `islandWithStopsMaxSize` set to 0,
      which turns it off for OpenStreetMap too.
- [ ] An edge has one extension, so this can not be used with the Data Overlay sandbox feature.
- [ ] No transit was in the test. Stops, parking and rental with both networks are untested.

## 2. Transit

**Status: built, covered by unit tests on the small synthetic dataset, not verified on real data.**

- [ ] Build a graph from a real GTFS feed and an OpenSidewalks dataset, and check trips by hand.
      So far there is a three-stop feed made up for Goldendale.
- [ ] An itinerary that rides, transfers and rides again has never been planned.
- [ ] No stop in a real feed has needed the links to more than one edge. That path only runs in
      `OswStopLinkModuleTest`.
- [ ] The transfer search is limited to 50 searches from a stop. How many it needs on a real
      network, and what it does to the time a graph takes to build, is not known.
- [ ] The link between a stop and an OpenSidewalks edge takes the time of walking it at a fixed
      1.3 m/s, because a link does not know the walking speed of the request. It also means transit
      trips take a little longer here than with stops on other streets, where links are free.
- [ ] A traveller is given the quickest stored transfer path they can use. It is the quickest
      there is for the traveller it was found for, not for every traveller who can use its edges.
- [ ] AccessMap has no transit on its public routers, so there is nothing to compare with yet.
      A stock OTP with OpenStreetMap and the same feed, or R5, could be.

## 3. Linking a place to the network

- [ ] OTP links a place to the nearest usable edge up to a kilometer away, and the walk between
      the two is not part of the trip. R5, the Walksheds service and AccessMap give up beyond
      50 m. This is the one difference that shows in every comparison: 8 of 400 trips in Seattle,
      about 100 of 296 in Goldendale. A limit would have to go into the linker of OTP, or be
      checked on the itinerary afterwards.
- [ ] Who the traveller is reaches the linker through a thread-local, set in
      `LinkingContextFactory` and read in `OswStreetEdge`. It is explicit about its scope but not
      pretty. Passing a test for edges into the linker would be cleaner and is a larger change.

## 4. Inclines

- [ ] An edge without an `incline` is walked by everyone, however steep it is. That is what the
      profile format says and what the Walksheds service does. In the dataset AccessMap's main
      Seattle region reads, a quarter of the sidewalks have none, and 17 of 149 wheelchair routes
      were more than 25% shorter in OTP than in AccessMap, which works out inclines of its own.
      Options: a setting of the profile to close or slow such edges for travellers with limits,
      or filling them in from elevation data when the graph is built. For now the documentation
      says to run the TDEI incline job first.
- [ ] That inclines explain those 17 routes was not confirmed route by route. Only a few percent
      of the path geometry could be matched back to edges of the dataset.

## 5. APIs and requests

- [ ] The parameters can be set in the GTFS GraphQL API, not in the Transmodel API.
- [ ] Their values are text in the API, since the names and types come from the profile. A query
      that lists the parameters of the profile, with their ranges, would let a client build its
      sliders.
- [ ] Values are checked when a request first meets an OpenSidewalks edge, not when the request
      is read, since the profile is part of the graph. A bad value is still reported as a bad
      request.
- [ ] OTP's wheelchair setting (`preferences.accessibility.wheelchair.enabled`) and these
      parameters are two separate things, and a trip with transit for a wheelchair user needs
      both. The user documentation now says what each decides and what to send ("Wheelchair users
      and trips with transit"). What is not decided:
    - Should one imply the other? A request with the wheelchair setting and no parameters is
      planned over curbs and grades for the default traveller, which is likely not what its
      sender meant. The server could have a set of parameter values for wheelchair requests, in
      the build or router configuration, used when a request has the setting and no values.
    - Or should a request with wheelchair-like limits and no wheelchair setting get a warning?
      There is no way to tell from the values that the traveller uses a wheelchair.
    - The Transmodel API has the wheelchair setting and not the parameters, so there a wheelchair
      trip can only be planned with the walking of the default traveller.
    - Transfers are stored for travellers by what they can manage on the edges. Whether the
      wheelchair setting is on is part of the key transfers are cached under, so that works, but
      a transfer through a station (pathways, elevators) and along a sidewalk is decided by both
      at once and has not been tested.
    - `wheelchair.enabled` also makes OTP use its own `maxSlope` and stairs settings on streets
      from other data. With OpenStreetMap loaded too (section 1) there are then two sets of
      limits for one traveller, on two networks.
- [ ] Walking a bicycle or a scooter over OpenSidewalks edges gets the cost OTP gives it, not the
      profile.

## 6. Tests and verification

- [ ] The Seattle comparisons are not tests. The sample, the answers of AccessMap and the scripts
      were in a scratch directory. A small extract of Seattle could be a second fixture next to
      Goldendale, which is what `OswRoutingComparisonTest` runs on.
- [ ] Unexplained differences that were left: OTP's path is 16 to 18 m off the path of R5 or
      AccessMap on two Goldendale routes (51 and 57), and two Seattle routes differ by 12% and 17% in length
      from AccessMap on the same data (157 m against 178 m, 454 m against 548 m).
- [ ] No workflow has run on the fork. Its owner has to enable them in the Actions tab. The full
      test suite passed locally on `22e2da358d`, on Java 27.
- [ ] The performance test of the project only runs on `dev-2.x` of the upstream repository. The
      Helsinki speed test was run locally on `d3df68b4e5` and showed no change (118.5 to 121 ms a
      search on both). The transfer cache test was not run, and nothing was measured after the
      prototype of section 1.

## 7. Before proposing this upstream

- [ ] The two pull requests are in the fork. The list of extension request contexts on the route
      request is useful without OpenSidewalks, and could be proposed first, on its own.
- [ ] `routingDefaults.pedestrianCost` is read whether or not the feature is on, because OTP
      reads the configuration files before it turns features on. `routingDefaults.dataOverlay` is
      read behind such a check and so is probably never read. Not tested. Worth reporting.
- [ ] The Goldendale fixture is a TDEI dataset, and the answers of AccessMap were fetched from its
      public site. Check that both can be published in the repository of the project.
- [ ] The contact in the user documentation, and the link to the R5 fork there.

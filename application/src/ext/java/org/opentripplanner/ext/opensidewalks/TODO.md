# OpenSidewalks sandbox: open work

What is not done, not decided, or not verified, as of October 2026. The user documentation is
[doc/user/sandbox/OpenSidewalks.md](../../../../../../../../doc/user/sandbox/OpenSidewalks.md), and
[README.md](README.md) describes the code.

## 1. Loading OpenStreetMap data alongside OpenSidewalks

**Status: built on the local branch `opensidewalks-with-osm`, not merged.** It is on top of
`opensidewalks-dynamic`, and has a change in the `street` module that has to be agreed to.

### The problem

A deployment will probably need both: OpenStreetMap for driving, cycling and ordinary walking,
OpenSidewalks for walking with limits. Loaded together they are two walking networks that share
no vertices.

- A walk can not change from one to the other.
- Left alone, which one a trip uses is up to which edge is nearer to where it starts, and on
  OpenStreetMap streets the pedestrian cost profile does not apply.

### The rule

- A request whose `pedestrianCost` values make the profile cost an edge differently than for the
  most able traveller walks the OpenSidewalks edges.
- Everyone else uses the OpenStreetMap streets as OTP has them: people on foot without values or
  with values that ask for nothing, cars, and bicycles, ridden or walked.
- OTP's wheelchair setting does not choose the network.
- A graph with OpenSidewalks data alone is walked by everyone, as before.

Measured in Seattle, 200 pairs of places, a BBBike OpenStreetMap extract plus TDEI dataset
`54f968ac`:

|                                                        | Result                                           |
|--------------------------------------------------------|--------------------------------------------------|
| Both loaded, before any of this, two travellers        | 212 of 338 walks found, 94 within 2% in length   |
| Walking, cycling, driving without values, both loaded  | 600 of 600 the same as with OpenStreetMap alone  |
| Two travellers with limits, both loaded                | all 330 walks the same as with OpenSidewalks alone |
| The same, against AccessMap on the same data           | no route missed, 19 more found (island pruning)  |

### What does not work

- **Exporting OpenSidewalks as OpenStreetMap and loading that.** OTP joins OpenStreetMap data by
  node id, so the two are still separate, and the edges would be costed by OTP's own rules
  without the profile.
- **Using the roads of an OpenSidewalks dataset for vehicles.** They are there for people on
  foot, and do not have what driving and cycling need.
- **Taking the walking permission off the OpenStreetMap streets.** OTP links a trip by bicycle to
  the streets through the edges that can be walked, so cycling trips could no longer start.
- **Replacing the walkable OpenStreetMap edges with a class of our own.** Turn restrictions, the
  level information of stairs, and station platforms hold on to the edges they were made for.
- **Everyone on foot on the OpenSidewalks edges.** Built first. It works, but there is then no
  walking where there is no OpenSidewalks data, what OpenStreetMap has for people on foot is lost
  to all of them, and bicycles have to be linked another way.

### How it is built

- `OtherStreet` is set as the cost extension of every walkable street that is in the graph when
  the OpenSidewalks data is loaded. It refuses to be linked to and to be walked by a traveller
  with limits. The OpenSidewalks edges refuse everyone else.
- Outside the sandbox, in the `street` module: `StreetEdgeCostExtension` can refuse traversal and
  linking and say which extension the parts of a split edge get, and `StreetEdge` and
  `StreetEdgeBuilder` use that. About 35 lines.
- `LinkingContextFactory` says where the place is that it is linking, for the 50 m limit of
  section 3.

### To decide and to do

- [ ] Is a change to `StreetEdge` acceptable? It is the hot path of every street search. It was
      not measured with the speed test.
- [ ] Bicycle and car parks and rental stations are only linked to the OpenStreetMap streets, so
      a traveller with limits can not use them. They need links to OpenSidewalks edges like the
      stops have.
- [ ] Where there is no OpenSidewalks data a traveller with limits has no route. Decided: no
      falling back to the OpenStreetMap streets. A client should be told why, which it is not.
- [ ] Changing a value from asking for nothing to asking for something moves the trip to the
      other network. To be handled in the user interface.
- [ ] "Asks for nothing" compares the cost function of the request with that of the most able
      traveller, layer by layer. With the Walksheds profile, a request that only sets the grades
      and curbs to their loosest still has the default street avoidance of 1, and so walks the
      OpenSidewalks edges. Only a request with no values, or with every value at its loosest,
      walks the OpenStreetMap streets. Is that what is wanted?
- [ ] `routingDefaults.pedestrianCost` in the router configuration with values that ask for
      something sends every walk to the OpenSidewalks edges.
- [ ] Island pruning: keep it and have places linked away from isolated pieces of sidewalk (19
      more routes in the sample), or exempt OpenSidewalks edges to match the other engines? Today
      that is `islandPruning.islandWithoutStopsMaxSize` and `islandWithStopsMaxSize` set to 0,
      which turns it off for OpenStreetMap too.
- [ ] An edge has one extension, so this can not be used with the Data Overlay sandbox feature.
- [ ] No transit was in the Seattle test. Stops and transfers with both networks are untested.
- [ ] Without a profile the OpenSidewalks edges are ordinary streets, and the two networks are
      both walked by everyone, with the problem this section starts with.

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

- [x] OTP linked a place to the nearest usable edge up to a kilometer away, where R5, the
      Walksheds service and AccessMap give up beyond 50 m. On `opensidewalks-with-osm` a place is
      only linked to an OpenSidewalks edge within 50 m. In Goldendale OTP then finds a route
      exactly where R5 does, and `OswRoutingComparisonTest` no longer has exceptions for it.
- [ ] The 50 m is a constant, `OswLinking.MAX_METERS_FROM_PLACE`. It could be a setting.
- [ ] The limit is for the places of a request. Stops are linked within 100 m, as OTP does.
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

- [x] The parameters can be set in both GraphQL APIs: `pedestrianCost` of the walk preferences in
      the GTFS API, and an argument of the trip query in the Transmodel API. The debug client
      lists the arguments of that query, so they can be set there without a change to it.
- [ ] Their values are text in the API, since the names and types come from the profile. In the
      Transmodel API the names are an enumeration made from the profile of the graph when the
      server starts, with the range and default of each in its description, so the debug client
      has a list to choose from. That makes the schema of a server depend on its graph: a query
      written with the name as text in the query itself, not in a variable, works without a
      profile and is refused with one. The GTFS API has a fixed schema file and still takes any
      text. Ranges a client can read as numbers, to build sliders, are still missing from both.
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

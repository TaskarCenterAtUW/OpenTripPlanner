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

- A request says which network it is on, `OPEN_SIDEWALKS` or `STREETS`, for the whole trip:
  walking, cycling and driving. If it does not say, one with `pedestrianCost` values is on
  `OPEN_SIDEWALKS` and one without on `STREETS`. `STREETS` with values is rejected.
- On `OPEN_SIDEWALKS`, cycling and driving use the roads of the dataset. A dataset without roads
  rejects a request on it that includes cycling or driving.
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

The 19 were found because island pruning removed isolated parts of the OpenSidewalks network and
places were linked past them. Pruning now leaves that network alone, so they should be gone; not
measured again.

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

- [x] Is a change to `StreetEdge` acceptable? It is the hot path of every street search.
      Decided: it is measured by the performance test of the project, which runs on `dev-2.x` of
      the upstream repository once it is merged there. See section 6.
- [x] Car parks are linked to the OpenSidewalks edges like the stops are, by `OswLinkModule`.
      Bicycle parks and rental stations are left linked to the OpenStreetMap streets only:
      decided that bicycles, and renting them, are for trips without limits.
- [x] Car parks added by a vehicle parking updater are linked to the OpenSidewalks edges as well,
      for walking and driving, in `OswLinking.linkForEveryone()`.
- [ ] Park and ride for a traveller with limits has not been planned on real data.
- [x] Station entrances were only linked to the OpenStreetMap streets, so a traveller with limits
      could not use a station with GTFS pathways: in Seattle, U District to Westlake by Link became
      a bus trip of twice the time. `OswLinkModule` links them like stops now, and the trip takes
      Link, along sidewalks with curb ramps and through the elevators of both stations.
- [x] Rental on `OPEN_SIDEWALKS`: the vehicle rental updater links stations and vehicles to the
      nearest OpenSidewalks edge as well (`OswLinking.linkForEveryone()`, in OTP's updater behind
      the feature flag). The vehicle is walked to a road and used on the roads.
- [x] Park and ride on `OPEN_SIDEWALKS`: `OswLinkModule` links car parks to the nearest road for
      cars. Car pickup needs no links: OTP switches between car and foot where a road meets an
      edge that can only be walked, which on OpenSidewalks data is where crossings meet roads.
- [x] Walking in a trip that rents or is picked up is costed with the profile. Where OTP walks
      itself (where a car drops off or may pick up, at geofencing boundaries, in an arrive-by
      rental search) its states are kept only on edges the traveller can walk, at OTP's time.
- [x] Transfers that are not walking, such as with a bicycle taken along, were found by OTP on the
      other streets only, so a trip on `OPEN_SIDEWALKS` had none. `OswTransferModule` finds them on
      the roads of the data as well, one search per stop for each such transfer request.
- [x] Bicycle parks and station centroids are linked to the OpenSidewalks edges too, by
      `OswLinkModule`, and a flex vehicle path that starts on the OpenSidewalks data is driven on
      its roads (`StreetFlexPathCalculator`, in the flex sandbox). Everything a trip can start,
      end or change at is now on both networks.
- [ ] Flex on `OPEN_SIDEWALKS` has not been tried with a GTFS-Flex feed.
- [ ] None of rental, park and ride or pickup on `OPEN_SIDEWALKS` has been planned on a server
      with real data: there is no GBFS feed for the Microsoft campus, and Seattle `05776f25` has no
      roads. Covered by unit tests on the small synthetic dataset.
- [ ] Where there is no OpenSidewalks data a traveller with limits has no route. Decided: no
      falling back to the OpenStreetMap streets. A client should be told why, which it is not.
- [x] Which network a request is on is a request-time choice, `preferences.street.network` in the
      GTFS API and `streetNetwork` in the Transmodel API. It replaced comparing the values of the
      request with those of the most able traveller, which sent a request with only grades and
      curbs at their loosest to the OpenSidewalks edges, moved a trip between networks when one
      value changed, and sent every walk there if `routingDefaults.pedestrianCost` had values.
      Roads of a dataset are used by bicycles and cars (`OswRoads`); tested on the Microsoft
      campus dataset, a fixture now. Seattle `05776f25` has no roads, so a request on it with a
      vehicle is rejected. Not tried on a running server yet.
- [ ] OpenSidewalks roads have no `oneway`, turn restrictions or `access` in the datasets seen
      (Microsoft campus in Redmond, `770f769f` in Renton), and few have a speed. Cars and
      bicycles on them would ignore one-way streets.
- [x] Island pruning leaves the OpenSidewalks network alone, as the other engines do, and keeps
      pruning the OpenStreetMap streets. `OswLinkModule` now runs after it, since pruning would
      otherwise unlink stops from the OpenSidewalks edges.
- [x] An edge has one extension, so this can not be used with the Data Overlay sandbox feature.
      Accepted as a limitation and documented in the user documentation of both features. Nothing
      enforces it: Data Overlay runs last and silently replaces the extensions set here.
- [ ] Transit with both networks was checked by hand in Seattle, on a few trips only (King County
      Metro, and Sound Transit with pathways). See section 2.
- [ ] Without a profile the OpenSidewalks edges are ordinary streets, and the two networks are
      both walked by everyone, with the problem this section starts with.

## 2. Transit

**Status: built, covered by unit tests on the small synthetic dataset, checked by hand on a few Seattle trips.**

- [x] Build a graph from a real GTFS feed and an OpenSidewalks dataset, and check trips by hand.
      Seattle, October 2026: BBBike OpenStreetMap extract, TDEI dataset `05776f25`, King County
      Metro, and Sound Transit with pathways and levels. U District to Westlake and Capitol Hill
      to Columbia City, each for a traveller without values, with the wheelchair setting, with
      limits, and with both. More trips, and a written-down sample, are still to do.
- [x] An itinerary that rides, transfers and rides again: Capitol Hill to Columbia City with
      limits rides past Columbia City to Othello, crosses to the other platform on sidewalks with
      curb ramps, and rides back. The traveller has no way from the southbound platform at
      Columbia City to the destination, which the traveller without values walks in two minutes.
      Probably the crossings of Martin Luther King Jr Way in the data; not checked one by one.
- [ ] No stop in a real feed has needed the links to more than one edge. That path only runs in
      `OswLinkModuleTest`.
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
      prototype of section 1. Decided: the change to `StreetEdge` is measured by that test after
      it is merged upstream, at https://otp-performance.leonard.io/.

## 7. Before proposing this upstream

- [ ] The two pull requests are in the fork. The list of extension request contexts on the route
      request is useful without OpenSidewalks, and could be proposed first, on its own.
- [ ] `routingDefaults.pedestrianCost` is read whether or not the feature is on, because OTP
      reads the configuration files before it turns features on. `routingDefaults.dataOverlay` is
      read behind such a check and so is probably never read. Not tested. Worth reporting.
- [ ] The Goldendale fixture is a TDEI dataset, and the answers of AccessMap were fetched from its
      public site. Check that both can be published in the repository of the project.
- [ ] The contact in the user documentation, and the link to the R5 fork there.

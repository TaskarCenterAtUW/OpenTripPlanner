# OpenSidewalks

## Contact Info

- Jeff Maki, Public Works Office

## Changelog

- Initial version (October 2026)

## Documentation

This feature builds the pedestrian street network from
[OpenSidewalks](https://github.com/OpenSidewalks/OpenSidewalks-Schema) (OSW 0.2) datasets, and can
cost it with a _pedestrian cost profile_: a description, as data, of what each kind of sidewalk,
crossing and street costs a particular traveller, and which of them the traveller can not use.

The profiles are the ones used by the OpenSidewalks support in
[R5](https://github.com/TaskarCenterAtUW/r5/tree/dev/osw-tools), and have the semantics of the
cost functions of the TDEI Walksheds service. The same profile file gives the same cost for the
same edge in OTP, R5 and Unweaver.

### Getting started

#### 1. Get a dataset

Put the dataset in the directory OTP is run on, next to the configuration files. It can be:

- a zip file containing the nodes and edges files,
- a directory containing them, or
- the edges file itself, with the nodes file beside it.

The files can be named `nodes.geojson` and `edges.geojson`, or have a prefix, as in
`goldendale.nodes.geojson` and `goldendale.edges.geojson`. Other files of the dataset, such as
points and zones, are ignored.

A small dataset to try it with is the one the tests use, in
`application/src/ext-test/resources/org/opentripplanner/ext/opensidewalks/goldendale/goldendale.osw.zip`.

Check that the edges of the dataset have an `incline`. The uphill and downhill limits of a
traveller only apply to edges that have one, see [Inclines](#inclines) below.

#### 2. Get a profile (optional)

Without a profile every edge can be walked, at the cost OTP gives any other street. That is
enough for a first try.

To cost the edges like the TDEI Walksheds service does, copy `ws-prod.json` from
`application/src/ext-test/resources/org/opentripplanner/ext/opensidewalks/` to the same directory.
Its parameters are:

| Parameter          | Meaning                                                                    | Default |
|--------------------|----------------------------------------------------------------------------|---------|
| `uphill`           | Steepest uphill grade the traveller can manage, 0.085 for 8.5%             | 0.085   |
| `downhill`         | Steepest downhill grade, as a positive number                              | 0.1     |
| `avoid_curbs`      | Only use crossings with curb ramps, and no steps                           | false   |
| `street_avoidance` | How strongly to avoid walking in streets: 0 not at all, 1 never            | 1       |
| `fan_out`          | Use streets at no extra cost, to bridge gaps between sidewalks             | false   |

#### 3. Configure OTP

Three files go in the directory:

```JSON
// otp-config.json
{
  "otpFeatures": {
    "OpenSidewalks": true
  }
}
```

```JSON
// build-config.json
{
  "openSidewalks": {
    "sources": ["goldendale.osw.zip"],
    "profile": "ws-prod.json",
    "parameters": {
      "uphill": 0.083,
      "downhill": 0.083,
      "avoid_curbs": true
    }
  }
}
```

Leave `profile` and `parameters` out to run without a profile.

```JSON
// router-config.json
{
  "routingDefaults": {
    "walk": {
      "speed": 1.3
    },
    "pedestrianCost": {
      "uphill": 0.083,
      "downhill": 0.083,
      "avoid_curbs": true
    }
  }
}
```

`pedestrianCost` is optional. It has the parameter values of the traveller that requests are
planned for, and can be changed without building the graph again. A parameter it leaves out has
the value of the build configuration, or else the default of the profile.

The walk speed is optional too. The default of OTP is 1.35 m/s. R5 and the Walksheds service walk at
1.3 m/s, so set it to that to get the durations they give.

#### 4. Build the graph and start OTP

```bash
mvn package -DskipTests
java -Xmx2G -jar otp-shaded/target/otp-shaded-*.jar --build --save /path/to/directory
java -Xmx2G -jar otp-shaded/target/otp-shaded-*.jar --load /path/to/directory
```

No OpenStreetMap or transit data is needed. Transit feeds in the directory are loaded as usual,
and their stops are linked to the OpenSidewalks edges.

The build logs what it did:

```
Costing OpenSidewalks edges with pedestrian cost profile ws-prod (profileId 017c...). A request without parameter values has {uphill=0.083, downhill=0.083, avoid_curbs=true, street_avoidance=1, fan_out=false} (runSpecId 38a4...)
Built 3290 street edges from OpenSidewalks data, 774 of which only some travellers can use. Left out 0 that no traveller can use and 0 that are not usable ways.
```

#### 5. Plan a walk

Open the GraphiQL client at <http://localhost:8080/graphiql>, or send a request:

```bash
curl -s -X POST -H 'Content-Type: application/json' http://localhost:8080/otp/gtfs/v1 -d '{
  "query": "{ planConnection(origin: {location: {coordinate: {latitude: 45.8218772, longitude: -120.822579}}}, destination: {location: {coordinate: {latitude: 45.820537, longitude: -120.8239937}}}, modes: {direct: [WALK], directOnly: true}) { edges { node { duration walkDistance } } routingErrors { code } } }"
}'
```

With the Goldendale dataset and the configuration above, this is a walk of about 254 meters that
takes about five and a half minutes.

A query can say what its traveller can manage, with the names the profile gives its parameters.
The values are text:

```graphql
{
  planConnection(
    origin: { location: { coordinate: { latitude: 45.8148243, longitude: -120.8235854 } } }
    destination: { location: { coordinate: { latitude: 45.8147042, longitude: -120.827688 } } }
    modes: { direct: [WALK], directOnly: true }
    preferences: {
      street: {
        walk: {
          pedestrianCost: [
            { name: "uphill", value: "0.083" }
            { name: "avoid_curbs", value: "true" }
          ]
        }
      }
    }
  ) {
    edges { node { duration walkDistance } }
    routingErrors { code }
  }
}
```

A parameter the query leaves out has the value of the router configuration. A value outside the
range of its parameter, or a name the profile does not have, gives a `BadRequestError`.

A `NO_DIRECT_MODE_CONNECTION` error means that there is no path the traveller of the profile can
use between the two places.

#### 6. Show what the walk goes along

`pedestrianSegments` of a leg of the GTFS GraphQL API lists the OpenSidewalks edges a walking leg
goes along, in order, with what a trip planner shows the traveller about each: its `highway` and
`footway`, for example a sidewalk, a crossing or steps, its `incline` in the direction it is walked,
whether it has `curbRamps`, its `name`, `description` and `surface`, and the `geometry` and
`distance` of the part that is walked. A client can say "cross the street" or draw an elevation
profile from these.

```graphql
legs {
  mode
  pedestrianSegments { footway highway incline curbRamps distance geometry { points } }
}
```

The parts an edge is split into, where a stop is linked to it or a trip starts or ends partway
along it, are one segment. The list is empty for transit legs, for walking on OpenStreetMap
streets, and on a graph built without a profile.

Where the walk goes through a station that has GTFS pathways, each pathway is a segment too, with
its `pathwayMode` (`WALKWAY`, `STAIRS`, `ESCALATOR`, `ELEVATOR` and so on), the sign it is
signposted with as its `name`, and the name of the station entrance it starts or ends at, if it
does, as its `description`. A ride in an elevator, from boarding to leaving it, is one segment.
Where the station has levels, `fromLevel` and `toLevel` are the names of the levels a pathway
starts and ends on, and `levelChange` is how many levels it goes up, negative if it goes down, so
that a client can say "Use the escalator: down from Surface to Mezzanine".

#### 7. Show the network on a map

With the [vector tiles API](MapboxVectorTilesApi.md) turned on as well, a layer of type
`OpenSidewalks` has the OpenSidewalks edges of the graph, with the properties the
[AccessMap](https://github.com/accessmap/accessmap-webapp) web app styles sidewalks and crossings by:

```JSON
// otp-config.json
{
  "otpFeatures": {
    "OpenSidewalks": true,
    "SandboxAPIMapboxVectorTilesApi": true
  }
}
```

```JSON
// router-config.json
{
  "vectorTiles": {
    "layers": [
      {
        "name": "transportation",
        "type": "OpenSidewalks",
        "mapper": "AccessMap",
        "minZoom": 13,
        "maxZoom": 20,
        "cacheMaxSeconds": 600
      }
    ]
  }
}
```

The tiles are at `/otp/routers/default/vectorTiles/transportation/{z}/{x}/{y}.pbf`. Each edge
is one feature, in the direction it is mapped in the dataset, with `footway`, `subclass` (the
`highway` of the edge), `incline`, `curbramps` (1 or 0), `crossing` (`marked` or `unmarked`, from
`crossing:markings`), `elevator` and `indoor` (1 or 0), `name`, `description`, `surface` and
`length`.

The map shows the network as it is in the graph, so an edge that no traveller can use in either
direction is not on it, and the layer is empty on a graph built without a profile.

### Configuration

The `openSidewalks` section of `build-config.json` has these parameters:

| Parameter    | Type       | Description                                                                                                                                              |
|--------------|------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| `sources`    | `string[]` | The datasets to load: a zip file or a directory containing `*.nodes.geojson` and `*.edges.geojson`, or an `*.edges.geojson` file.                        |
| `profile`    | `string`   | The pedestrian cost profile to cost the edges with. Optional.                                                                                            |
| `parameters` | `object`   | The parameter values a request has when it gives none itself. A parameter that is left out has the default value the profile gives it. Optional.          |

A relative path is resolved against the OTP base directory, the one holding `build-config.json`.

`routingDefaults.pedestrianCost` in `router-config.json` has the parameter values of the traveller
that requests are planned for, for example `{ "uphill": 0.083, "avoid_curbs": true }`.

### How a dataset becomes streets

- Each edge of a dataset becomes a pair of street edges, one in each direction, that can only be
  walked. Points, lines, polygons and zones are ignored, as are edges without a `highway` property
  and those with `highway=construction` or `highway=proposed`.
- The length of a street edge is the `length` property of the OSW edge, if it has one.
- Nodes at the same position, to 7 decimal places, are one node, whatever their ids. Some datasets
  rely on this, for example where steps end on a node of their own that sits on a sidewalk node.
- Transit stops are linked to these streets like to any other. OTP can be run on OpenSidewalks
  data alone, with transit data, or both.
- Small disconnected parts of the network are kept if there is no OpenStreetMap data, since island
  pruning only runs when there is.

### With OpenStreetMap data

OpenStreetMap data can be loaded in the same graph. The streets from it and the OpenSidewalks
edges are two networks that are not joined to each other, so each is for its own travellers:

- **A traveller the profile makes a difference for walks the OpenSidewalks edges.** That is a
  request whose `pedestrianCost` values make the profile cost an edge differently than for the
  most able traveller: someone with a limit on grades, who avoids curbs, or the like. The places
  of the trip are linked to OpenSidewalks edges, and the profile applies to all of the walking.
- **Everyone else uses the OpenStreetMap streets, as OTP has them.** That is people on foot
  without such values, or with values that ask for nothing, as well as cars and bicycles, ridden
  or walked.

So a deployment with both is OTP as it is for most trips, with the OpenSidewalks data as the
network for travellers with limits. The wheelchair setting of OTP does not choose the network,
see [Wheelchair users and trips with transit](#wheelchair-users-and-trips-with-transit): a
request with that setting and no `pedestrianCost` gets the wheelchair routing of OTP on the
OpenStreetMap streets.

In Seattle, with both loaded, 200 walks, 200 cycling trips and 200 driving trips without values
came out exactly as on a graph with the OpenStreetMap data alone, and the walks of travellers
with limits came out as on a graph with the OpenSidewalks data alone.

Things to know:

- A stop is linked to both networks: by OTP to the OpenStreetMap streets, and to the OpenSidewalks
  edges as described under [Transit stops and transfers](#transit-stops-and-transfers). Transfers
  are found on both as well, and a traveller is given the ones on their own network.
- Bicycle and car parks, and rental stations, are only linked to the OpenStreetMap streets. A
  traveller with limits can not walk to or from one.
- Where there is no OpenSidewalks data, a traveller with limits has no route. The trip is not
  planned on the OpenStreetMap streets instead, which would not have the limits.
- Changing a value from asking for nothing to asking for something moves the trip from one network
  to the other, so its path and its duration can change more than the value suggests. A crossing
  takes 30 seconds longer on the OpenSidewalks edges of the Walksheds profile, for one.
- Island pruning runs when there is OpenStreetMap data, and removes small disconnected parts of
  the OpenSidewalks network too. A place next to such a part is then linked to the rest of the
  network, if that is near enough, so some trips are found that would otherwise have none: 19 of
  400 in Seattle. Set `islandPruning.islandWithoutStopsMaxSize` and `islandWithStopsMaxSize` to 0
  in the build configuration to keep those parts.
- A profile is needed for this. Without one the OpenSidewalks edges are ordinary streets that
  everyone walks, on two networks that are not joined.
- This can not be used together with the Data Overlay sandbox feature. Both use the one extension
  a street edge has.

### How a profile is applied

The profile is set in the build configuration and is part of the graph. The values of its
parameters, which say what a traveller can manage, belong to each request. They come from
`preferences.street.walk.pedestrianCost` of a plan query of the GTFS GraphQL API or the
`pedestrianCost` argument of a trip query of the Transmodel API, then from
`routingDefaults.pedestrianCost` in the router configuration, then from the build configuration,
then from the defaults of the profile.

- In the Transmodel API of a server whose graph has a profile, the name of a parameter is an
  enumeration of the parameters of that profile, `PedestrianCostParameterName`, each described
  with the values it can have. A client can read it from the schema to offer the parameters.
  Without a profile, and in the GTFS API, the name is text.
- In the debug client they are under "Pedestrian Cost" in the list of arguments, where each
  parameter is added by choosing its name and giving its value.
- A value has to be in the range the profile gives the parameter: from `sliderMin` to `sliderMax`
  if the parameter has those, or else from `min` to `max`. A request with another value fails.
- A direction of an edge that the traveller of a request can not use is not walked in that
  request. An edge with an `incline` of 0.1 can be walked from its end to its start by a traveller
  with an uphill limit of 0.085, but not the other way.
- The places a trip starts and ends at are linked to the nearest edge the traveller of the request
  can use, if there is one within 50 meters.
- A direction of an edge that no traveller can use, whatever the values of the parameters in their
  ranges, is left out of the graph.
- The other edges are walked at the walking speed of the request multiplied with the speed factor
  the profile gives the edge for the traveller, plus any delay. The cost is that time multiplied
  with the walk reluctance of the request.
- The cost replaces the one OTP would calculate. The slope, stairs, walk safety and wheelchair
  accessibility preferences of the request are not applied on top of it, since the profile already
  says what the edge costs for the traveller it describes. Turning from one of these edges onto
  another costs nothing.
- Where a stop is linked to an edge, or a trip starts or ends partway along one, each part of the
  edge is costed like the whole, with the share of the delay that corresponds to its length.
- The build log has the profile id, which identifies the definition of the cost function, and the
  run spec id of a request without parameter values. They are the same ids R5 reports for the
  same profile and values.

Without a profile every edge can be walked, at the cost OTP gives any other street.

### Wheelchair users and trips with transit

OTP has a wheelchair setting of its own, `preferences.accessibility.wheelchair.enabled` of a plan
query. It and the parameters of the pedestrian cost profile are two separate things, and neither
sets the other:

| | Decided by `pedestrianCost` | Decided by `wheelchair.enabled` |
|---|---|---|
| Sidewalks, crossings and other OpenSidewalks edges: which can be used, and how fast | yes | no |
| Whether a stop can be used, and what it costs if that is not known | no | yes |
| Whether a trip, a vehicle, can be boarded | no | yes |
| Pathways, elevators and escalators of stations | no | yes |

So a trip with transit for a wheelchair user needs both in the query: the limits of the traveller
on the way to and from the stops, and the wheelchair setting for the stops and the vehicles.

```graphql
preferences: {
  accessibility: { wheelchair: { enabled: true } }
  street: {
    walk: {
      pedestrianCost: [
        { name: "uphill", value: "0.083" }
        { name: "downhill", value: "0.083" }
        { name: "avoid_curbs", value: "true" }
      ]
    }
  }
}
```

With only one of the two, a part of the trip is planned for another traveller:

- With `pedestrianCost` alone, the walking is right, but the trip may use a stop or a vehicle that
  a wheelchair can not get on.
- With `wheelchair.enabled` alone, the stops and vehicles are right, but the walking is planned for
  the traveller the server is configured with, who may not avoid curbs or steep grades. The slope
  and stairs settings of OTP's wheelchair routing have no effect on OpenSidewalks edges.

A traveller who is not a wheelchair user, but has limits, sends `pedestrianCost` alone.

### Inclines

The steepness limits of a traveller, and the slowing down on slopes, come from the `incline`
property of each edge: its grade in the direction the edge is mapped, 0.05 for 5% uphill. OTP does
not work out inclines itself, and does not use its own elevation data for these edges.

**An edge without an `incline` is walked at plain speed by every traveller, however steep it is on
the ground.** This is what the profile format says, and what the Walksheds service does, but it
means that a dataset with inclines missing sends travellers with limits over slopes they can not
manage.

So give OTP a dataset that has an incline on its edges:

- Datasets from TDEI can have inclines added from elevation data by the TDEI incline job
  (`POST /api/v1/osw/dataset-inclination/{dataset id}` of the TDEI API). Use the dataset after
  that job has run.
- To see how complete a dataset is, count the features of its edges file that have an `incline`
  among the sidewalks, crossings and footways.
- The inclines of two routing engines have to be the same for their routes to be. A service that
  works out inclines of its own, as AccessMap does for some of its regions, will route differently
  from OTP on the same dataset, see [Verification](#verification).

### Transit stops and transfers

Stops are linked to the streets, and the paths of the transfers between them are found, when the
graph is built. No traveller is known then, so both are done for all of them.

- A stop is linked to every edge within 100 meters that is the nearest one some traveller can use:
  the nearest edge at all, and each edge beyond it that is needed by a traveller who can use none
  of the nearer ones, up to the nearest edge that everyone can use. A traveller walks to and from
  a stop along the nearest of those they can use.
- The link between a stop and an OpenSidewalks edge takes the time of walking the distance between
  them at 1.3 m/s. The links OTP makes between stops and other streets take no time.
- The paths of transfers are found by OTP for the requests in `transferRequests` of
  `build-config.json`, which is one walking request unless it is set. Then the stops within reach
  of each stop are searched for again by less and less able travellers, to find the paths of the
  travellers who can not use the ones found so far:

    1. The most able traveller finds every stop that anyone can transfer to.
    2. Each path found asks something of a traveller: the most that any of its edges does.
    3. A traveller just short of that, in one respect, can not walk the path, and searches for
       another way.

  This goes on until nothing new is found, so the travellers who search are the ones that the
  streets around the stop make a difference for. Where every path is one that all can use, the
  first search is the last. A stop is searched from at most 50 times.
- When a trip is planned, each of the paths between two stops is walked again as the traveller of
  the request. A path with an edge that traveller can not use is dropped, and the quickest of the
  others is the transfer.

### Profile format

```JSON
{
  "schemaVersion": 1,
  "name": "example",
  "parameters": {
    "uphill":      {"type": "number",  "default": 0.085, "min": 0, "max": 1},
    "downhill":    {"type": "number",  "default": 0.1,   "min": 0, "max": 1},
    "avoid_curbs": {"type": "boolean", "default": true}
  },
  "layers": [
    {"name": "crossings", "match": {"highway": "footway", "footway": "crossing"},
     "delaySeconds": 30, "requireCurbRamps": "$avoid_curbs"},
    {"name": "footways", "match": {"highway": "footway"},
     "inclineSpeed": {"maxUphill": "$uphill", "maxDownhill": "$downhill"}},
    {"name": "steps", "match": {"highway": "steps"}, "speedFactor": 0.4,
     "blocked": "$avoid_curbs"}
  ],
  "otherEdges": "impassable"
}
```

- Each edge belongs to the **first layer whose `match` it satisfies**: each key must equal the
  value, be one of a list of values, or be present (`"*"`). Edges matching no layer follow
  `otherEdges`, which is `impassable` or `walk`.
- `inclineSpeed`: Tobler's hiking function, with the speed falling to a fifth (`divisor`) of the
  walking speed at the limits. Edges longer than 3 meters (`minLengthForLimits`) that are steeper
  than the limits are impassable. An edge with a missing or non-numeric `incline` is walked at the
  plain speed of the layer, with no limit applied.
- `delaySeconds` is added to the time it takes to walk the edge.
- `requireCurbRamps`: the edge needs curb ramps. An edge has them unless a kerb stands in the way
  at either end, meaning a kerb node that is not `kerb=lowered` or `kerb=flush`, including one with
  no `kerb` value. An end with no kerb node is no obstacle. An explicit `curbramps` property of
  the edge takes precedence.
- `speedFactor` multiplies the speed for a layer.
- `blocked` makes the layer impassable.
- `avoidance` makes a layer cost more the more the traveller wants to avoid it:
  `{"amount": "$street_avoidance", "k": 3}` multiplies the whole cost of the layer, delay included,
  by `exp(k × amount)`, and makes it impassable when the amount is 1 or more. An optional
  `"unless": "$fan_out"` switches the avoidance off when true.
- Any setting can be a literal or a `"$parameter"` reference. Unknown or misspelled settings are
  rejected, and so are settings of the wrong JSON type.

### Verification

The walks OTP plans over OpenSidewalks data have been compared with those of three other routing
engines that use the same cost function: the TDEI Walksheds service, R5, and AccessMap. The
comparisons were made in October 2026.

**Goldendale, WA.** 74 pairs of places under the four traveller profiles of the TDEI quality
reports, on a dataset of 1,645 edges. This comparison is a test,
`OswRoutingComparisonTest`, which plans the walks with the traveller set per request and fails if
they move away from the saved answers of R5 and AccessMap.

- OTP finds a route for every pair R5 and AccessMap do. Of the 170 routes, 169 are within 2% of
  the length R5 has, and all are within 5% of its duration. The one that differs is a route where
  OTP and AccessMap take a path that is 50 m shorter than the one R5 takes.
- OTP walks the same path as R5 or AccessMap on all 170 routes, to within 20 m.

**Seattle, WA.** 200 pairs of places sampled from the 40,222 of a quality reports run, as a manual
wheelchair user (grades up to 8.3%, curb ramps required) and as an unconstrained pedestrian. OTP
planned the 400 walks on one graph of 320,232 street edges in 3.5 seconds.

Against AccessMap on the same dataset with the same inclines (its "Seattle, WA (1.8)" region):

|                                         | Manual wheelchair | Unconstrained pedestrian |
|-----------------------------------------|-------------------|--------------------------|
| Both find a route                       | 148               | 182                      |
| Neither does                            | 47                | 15                       |
| Only OTP does                           | 5                 | 3                        |
| Only AccessMap does                     | 0                 | 0                        |
| Lengths within 2%, of routes both find  | 135 (91%)         | 168 (92%)                |
| Lengths within 5%                       | 147 (99%)         | 177 (97%)                |
| Durations within 5%                     | 132 (89%)         | 172 (95%)                |

The routes only OTP finds are all between places that AccessMap does not link to the network,
because they are more than 50 m from it. OTP linked a place up to a kilometer away when this was
measured. It now leaves a place farther than 50 m from the OpenSidewalks edges unlinked too.

Against AccessMap's main Seattle region, which reads another dataset and works out inclines of its
own instead of using the ones in the data, the agreement is lower: 73% of the wheelchair routes
and 83% of the pedestrian routes are within 2% in length, and 17 of the 149 wheelchair routes are
more than 25% apart, nearly all because AccessMap walks farther. A quarter of the sidewalks of
that dataset have no `incline`, so OTP lets a wheelchair user over them where AccessMap, with an
incline of its own for each, closes the steep ones. The cause was not confirmed route by route,
but the large differences are gone on the dataset where both use the same inclines.

No transit was compared. None of the routes AccessMap returned used a bus or a train.

### Limitations

- The profile applies to walking only, and not to walking a bicycle or scooter.
- Every traveller who can transfer between two stops has a path to do so, and it is the quickest
  path for the traveller it was found for. How fast an edge is walked depends on the limits of the
  traveller too, so another traveller who can use the same edges may have a slightly quicker path
  that was not found.
- A place is linked to the nearest OpenSidewalks edge the traveller can use within 50 meters, and
  the walk between the place and the edge is not part of the trip. A place farther than that from
  such an edge has no route, as in R5, the Walksheds service and AccessMap.
- A profile should not make any edge faster than the walking speed, with a `speedFactor` above 1.
  Street searches may then miss the best path.

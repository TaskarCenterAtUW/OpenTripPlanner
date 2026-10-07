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

### Configuration

The `openSidewalks` section of `build-config.json` has these parameters:

| Parameter    | Type       | Description                                                                                                                                              |
|--------------|------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| `sources`    | `string[]` | The datasets to load: a zip file or a directory containing `*.nodes.geojson` and `*.edges.geojson`, or an `*.edges.geojson` file.                        |
| `profile`    | `string`   | The pedestrian cost profile to cost the edges with. Optional.                                                                                            |
| `parameters` | `object`   | The parameter values a request has when it gives none itself. A parameter that is left out has the default value the profile gives it. Optional.          |
| `findTransfersForAllTravellers` | `boolean` | Find the paths of transfers between stops for the travellers at the ends of the parameter ranges too. Defaults to `true`. See [below](#transit-stops-and-transfers). |

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
- OpenSidewalks streets are not connected to streets from OpenStreetMap data. If both are loaded,
  a walk can not change between the two, so leave out the OpenStreetMap data, or make sure it does
  not cover the same area.
- Small disconnected parts of the network are kept if there is no OpenStreetMap data, since island
  pruning only runs when there is.

### How a profile is applied

The profile is set in the build configuration and is part of the graph. The values of its
parameters, which say what a traveller can manage, belong to each request. They come from
`preferences.street.walk.pedestrianCost` of a plan query of the GTFS GraphQL API, then from
`routingDefaults.pedestrianCost` in the router configuration, then from the build configuration,
then from the defaults of the profile.

- A value has to be in the range the profile gives the parameter: from `sliderMin` to `sliderMax`
  if the parameter has those, or else from `min` to `max`. A request with another value fails.
- A direction of an edge that the traveller of a request can not use is not walked in that
  request. An edge with an `incline` of 0.1 can be walked from its end to its start by a traveller
  with an uphill limit of 0.085, but not the other way.
- The places a trip starts and ends at are linked to the nearest edge the traveller of the request
  can use.
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

### Transit stops and transfers

Stops are linked to the streets, and the paths of the transfers between them are found, when the
graph is built. No traveller is known then, so both are done for all of them.

- A stop is linked to every edge within 100 meters that is the nearest one some traveller can use:
  the nearest edge at all, and each edge beyond it that is needed by a traveller who can use none
  of the nearer ones, up to the nearest edge that everyone can use. A traveller walks to and from
  a stop along the nearest of those they can use.
- The link between a stop and an OpenSidewalks edge takes the time of walking the distance between
  them at 1.3 m/s. The links OTP makes between stops and other streets take no time.
- The paths of transfers are found for the requests in `transferRequests` of `build-config.json`,
  which is one walking request unless it is set, and for the travellers at the ends of the ranges
  of the parameters that decide whether an edge can be used. With the two limits, two switches and
  street avoidance of the Walksheds profile those are 32 travellers, and finding transfers takes
  33 times as long as for one. Set `findTransfersForAllTravellers` to `false` to find them only
  for the `transferRequests`, and list a request there for each kind of traveller that should have
  a path of their own:

    ```JSON
    // build-config.json
    {
      "openSidewalks": {
        "sources": ["goldendale.osw.zip"],
        "profile": "ws-prod.json",
        "findTransfersForAllTravellers": false
      },
      "transferRequests": [
        { "modes": "WALK" },
        { "modes": "WALK", "pedestrianCost": { "uphill": 0.04, "downhill": 0.04, "avoid_curbs": true } }
      ]
    }
    ```

  When a trip is planned, each of the paths between two stops is walked again as the traveller of
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

### Limitations

- The profile applies to walking only, and not to walking a bicycle or scooter.
- The parameter values of a request can be set in the GTFS GraphQL API, but not in the Transmodel
  API.
- A traveller whose values are between the ones transfers were found for is given the quickest
  of those paths that they can use, which may not be the quickest path there is for them.
- A place is linked to the nearest edge the traveller can use within a kilometer, also when that
  is far from the place. R5 and AccessMap give up beyond 50 meters. The walk between the place and
  the edge is not part of the trip.
- A profile should not make any edge faster than the walking speed, with a `speedFactor` above 1.
  Street searches may then miss the best path.

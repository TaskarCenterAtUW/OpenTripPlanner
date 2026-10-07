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

### Configuration

Enable the feature in `otp-config.json`:

```JSON
// otp-config.json
{
  "otpFeatures": {
    "OpenSidewalks": true
  }
}
```

Then list the datasets, and optionally a profile, in `build-config.json`:

```JSON
// build-config.json
{
  "openSidewalks": {
    "sources": ["seattle.osw.zip"],
    "profile": "ws-prod.json",
    "parameters": {
      "uphill": 0.083,
      "downhill": 0.1,
      "avoid_curbs": true,
      "street_avoidance": 1
    }
  }
}
```

| Parameter    | Type       | Description                                                                                                                                              |
|--------------|------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| `sources`    | `string[]` | The datasets to load: a zip file or a directory containing `*.nodes.geojson` and `*.edges.geojson`, or an `*.edges.geojson` file.                        |
| `profile`    | `string`   | The pedestrian cost profile to cost the edges with. Optional.                                                                                            |
| `parameters` | `object`   | The values of the parameters of the profile. A parameter that is left out has the default value the profile gives it. Optional.                           |

A relative path is resolved against the OTP base directory, the one holding `build-config.json`.

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

The profile and its parameter values are set in the build configuration, and are evaluated once
for each direction of each edge while the graph is built. All trips planned with the graph are
costed with it. To use other parameter values the graph has to be built again.

- A direction of an edge that the profile makes impassable is left out of the graph. An edge with
  an `incline` of 0.1 and an uphill limit of 0.085 can then be walked from its end to its start,
  but not the other way. Nothing is linked to an edge that is left out, so a trip never starts or
  ends on a sidewalk the traveller can not use.
- The other edges are walked at the walking speed of the request multiplied with the speed factor
  the profile gives the edge, plus any delay. The cost is that time multiplied with the walk
  reluctance of the request.
- The cost replaces the one OTP would calculate. The slope, stairs, walk safety and wheelchair
  accessibility preferences of the request are not applied on top of it, since the profile already
  says what the edge costs for the traveller it describes. Turning from one of these edges onto
  another costs nothing.
- Where a stop is linked to an edge, or a trip starts or ends partway along one, each part of the
  edge is costed like the whole, with the share of the delay that corresponds to its length.
- The build log has the profile id and the run spec id, which identify the definition of the cost
  function and the parameter values. They are the same ids R5 reports for the same profile.

Without a profile every edge can be walked, at the cost OTP gives any other street.

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
- The profile is the same for every request. It can not be chosen, and its parameters can not be
  set, in a request.
- A place is linked to the nearest edge the traveller can use, also when that is far from the
  place. R5 gives up beyond 50 meters. The walk between the place and the edge is not part of the
  trip.
- A profile should not make any edge faster than the walking speed, with a `speedFactor` above 1.
  Street searches may then miss the best path.

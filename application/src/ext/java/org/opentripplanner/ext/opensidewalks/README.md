# OpenSidewalks

This sandbox feature builds the pedestrian street network from
[OpenSidewalks](https://github.com/OpenSidewalks/OpenSidewalks-Schema) (OSW 0.2) datasets, and can
cost it with a pedestrian cost profile.

How to set it up, what it does to a dataset, the profile format and the limitations are in the
user documentation,
[doc/user/sandbox/OpenSidewalks.md](../../../../../../../../doc/user/sandbox/OpenSidewalks.md).
This page is for those working on the code.

## How the code is organised

| Class                                         | What it does                                                                                      |
|-----------------------------------------------|---------------------------------------------------------------------------------------------------|
| `config/OpenSidewalksConfig`                  | The `openSidewalks` section of `build-config.json`, and `pedestrianCost` of the routing defaults. |
| `api/PedestrianCostParametersMapper`          | Maps `pedestrianCost` of the walk preferences of a GTFS GraphQL plan query to the request.        |
| `OswDataset`                                  | Reads the nodes and edges of a dataset.                                                           |
| `OswModule`                                   | The graph builder module: builds the street edges.                                                |
| `OswStopLinkModule`                           | A graph builder module that links stops to the edges that travellers who can not use all need.    |
| `PedestrianCostProfile`, `PedestrianCostSpec` | The profile format, and the cost function for one set of parameter values.                        |
| `PedestrianCostParameters`                    | The parameter values of a request. Carried by the route request to the street search and linking. |
| `OswCosting`                                  | The profile of a graph, shared by its edges. Turns the values of a request into a cost function.  |
| `OswEdgeAttributes`, `CurbRamps`              | What the profile needs to know about an edge: its layer, incline, length and curb ramps.          |
| `OswStreetEdge`, `TemporaryOswStreetEdge`     | Street edges that are walked at the cost the profile gives them, and the parts they split into.   |
| `OswWalkCost`                                 | The walking time and cost of those edges for the traveller of a request.                          |
| `OswLinking`                                  | Which edges a place can be linked to: those the traveller of the request can use.                 |
| `CanonicalJson`                               | The profile id and run spec id, which are the same as the ones R5 reports.                        |

## How a request reaches an edge

`PedestrianCostParameters` is an extension request context: OTP carries it for the feature without
knowing what it is. A route request has a list of such contexts, in `SystemPreferences.extensions`,
and passes them on along two paths:

- `StreetSearchRequestMapper` gives them to every street search of the request, where
  `OswWalkCost` finds the parameters by their type. They are also part of the key transfers are
  cached under.
- `LinkingContextRequestMapper` copies them to the linking request, and `LinkingContextFactory`
  tells `OswLinking` who the traveller is while it links the places of the request.

Another sandbox feature that needs something from the request in its edges can add a context of
its own type to the same list.

## Tests

The tests are in `application/src/ext-test/java/org/opentripplanner/ext/opensidewalks/`:

```bash
mvn test -pl application -Dtest='PedestrianCostProfileTest,OswModuleTest,OswRoutingComparisonTest'
```

`OswRoutingComparisonTest` compares the walks OTP plans with saved answers from R5 and AccessMap
for the same places. When it fails after a change, either the change is a mistake, or it is a new
difference to the other engines, which should be understood and then listed in the test.

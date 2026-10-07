# OpenSidewalks

This sandbox feature builds the pedestrian street network from
[OpenSidewalks](https://github.com/OpenSidewalks/OpenSidewalks-Schema) (OSW 0.2) datasets, and can
cost it with a pedestrian cost profile.

How to set it up, what it does to a dataset, the profile format and the limitations are in the
user documentation,
[doc/user/sandbox/OpenSidewalks.md](../../../../../../../../doc/user/sandbox/OpenSidewalks.md).
This page is for those working on the code.

## How the code is organised

| Class                                           | What it does                                                                               |
|-------------------------------------------------|--------------------------------------------------------------------------------------------|
| `config/OpenSidewalksConfig`                    | The `openSidewalks` section of `build-config.json`.                                        |
| `OswDataset`                                    | Reads the nodes and edges of a dataset.                                                    |
| `OswModule`                                     | The graph builder module: builds the street edges, and evaluates the profile for each.     |
| `PedestrianCostProfile`, `PedestrianCostSpec`   | The profile format, and the cost function for one set of parameter values.                 |
| `CurbRamps`                                     | Whether an edge has curb ramps, from its kerb nodes.                                       |
| `OswStreetEdge`, `TemporaryOswStreetEdge`       | Street edges that are walked at the cost the profile gave them, and the parts they split into. |
| `OswWalkCost`                                   | The walking time and cost of those edges.                                                  |
| `CanonicalJson`                                 | The profile id and run spec id, which are the same as the ones R5 reports.                 |

## Tests

The tests are in `application/src/ext-test/java/org/opentripplanner/ext/opensidewalks/`:

```bash
mvn test -pl application -Dtest='PedestrianCostProfileTest,OswModuleTest,OswRoutingComparisonTest'
```

`OswRoutingComparisonTest` compares the walks OTP plans with saved answers from R5 and AccessMap
for the same places. When it fails after a change, either the change is a mistake, or it is a new
difference to the other engines, which should be understood and then listed in the test.

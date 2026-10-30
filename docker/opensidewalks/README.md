# OpenTripPlanner with OpenSidewalks and AccessMap

One image with OTP, its OpenSidewalks sandbox feature, and the
[AccessMap web app](https://github.com/TaskarCenterAtUW/AccessMapWebAppOTP). The data is named when
the container starts: OpenSidewalks datasets by their TDEI ids, and GTFS feeds by their TDEI ids or
URLs.

## Build

From the root of this repository, with BuildKit:

```bash
docker build -f docker/opensidewalks/Dockerfile \
  --build-context accessmap=https://github.com/TaskarCenterAtUW/AccessMapWebAppOTP.git#main \
  -t otp-accessmap .
```

To build with a local checkout of the web app, give its path as the `accessmap` context.

## Run

```bash
docker run -p 8080:8080 -v otp-data:/var/opentripplanner \
  -e TDEI_OSW_DATASET_IDS=05776f25-f0f3-461c-ac34-4fa88a00936c \
  -e TDEI_GTFS_PATHWAYS_DATASET_IDS="id-of-a-dataset id-of-another" \
  -e GTFS_URLS="https://example.org/agency-a.zip" \
  -e TDEI_USERNAME -e TDEI_PASSWORD -e MAPTILER_KEY \
  otp-accessmap
```

The app is at <http://localhost:8080>. The first start downloads the data and builds the graph, and
trips can be planned when that has finished. The container is healthy from then on. With the volume,
later starts load the graph that was built.

| Variable                         | Required | What it is                                                                    |
|----------------------------------|----------|-------------------------------------------------------------------------------|
| `TDEI_OSW_DATASET_IDS`           | yes      | The TDEI ids of OpenSidewalks datasets, separated by spaces or commas.        |
| `TDEI_AUTH_TOKEN`                | one of   | A TDEI token. It has to last for the download.                                |
| `TDEI_USERNAME`, `TDEI_PASSWORD` | these    | A TDEI account to get a token with.                                           |
| `MAPTILER_KEY`                   | yes      | For the base map and the search for places. It is in the page, so restrict it to your domains. |
| `TDEI_GTFS_PATHWAYS_DATASET_IDS` | no       | The TDEI ids of GTFS-Pathways datasets, separated by spaces or commas.        |
| `TDEI_GTFS_FLEX_DATASET_IDS`     | no       | The TDEI ids of GTFS-Flex datasets. Having any turns on OTP's flex routing.   |
| `GTFS_URLS`                      | no       | The URLs of GTFS feeds, separated by spaces or commas.                        |
| `REGION_NAMES`                   | no       | The names of the regions in the app, one for each dataset, separated by commas. By default the name in the metadata of each dataset, or else `Area 1` and so on. |
| `REBUILD_GRAPH`                  | no       | `1` downloads the data and builds the graph again.                            |
| `TDEI_BASE_URL`                  | no       | The TDEI API, by default `https://api.tdei.us`.                               |
| `JAVA_OPTS`                      | no       | Options for Java, by default `-XX:MaxRAMPercentage=75`.                       |

- Without any feed there is no transit.
- The boundary of a region in the app is the `dataset_area` in the metadata of its dataset, or else
  a rectangle around its edges.
- Several OpenSidewalks datasets are for separate areas, each a region in the app. They are not
  joined, so a walk can not go from one to another, even where they touch, and where they overlap
  there are two networks. Only transit can connect them.
- A GTFS-Pathways dataset has to be a whole feed. TDEI checks only the `stops`, `pathways` and
  `levels` files, so a dataset can have just those, but OTP can not load that or join it to a feed
  from elsewhere, and the container stops with an error. For stations with pathways and service, use
  one feed with both.
- A feed has to have trips in the period from a week ago to six months ahead, or the graph build
  stops with an error.
- A graph is reused while the dataset ids, the list of URLs and the image stay the same. A feed that
  has changed at the same URL needs `REBUILD_GRAPH=1`.
- Anyone who can reach the container can see the sidewalks of the dataset and plan trips on it,
  whether or not they have access to it on TDEI.
- Only the GTFS GraphQL API and the vector tiles of OTP are reachable, on port 8080.
- The dataset should have an `incline` on its edges, see `doc/user/sandbox/OpenSidewalks.md`.

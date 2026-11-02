#!/usr/bin/env python3
"""
Starts OTP and the AccessMap web app on the data the environment names. See README.md.

The first start downloads the data and builds the graph, in /var/opentripplanner. Later starts with
the same data load that graph.
"""
import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

BASE_DIR = Path("/var/opentripplanner")
UI_DIR = Path("/tmp/accessmap-ui")
OTP_PORT = "8090"
DATASET_ID = re.compile(r"^[A-Za-z0-9_-]{1,100}$")
COORDINATE = re.compile(rb"\[\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)")
READ_SIZE = 1 << 20

# The kinds of GTFS dataset of TDEI: the variable with their ids, and the API they are fetched from
TDEI_FEED_KINDS = {
    "TDEI_GTFS_PATHWAYS_DATASET_IDS": "gtfs-pathways",
    "TDEI_GTFS_FLEX_DATASET_IDS": "gtfs-flex",
}
ROUTER_CONFIG = {
    "routingDefaults": {"walk": {"speed": 1.3}},
    "vectorTiles": {
        "layers": [
            {
                "name": "transportation",
                "type": "OpenSidewalks",
                "mapper": "AccessMap",
                "minZoom": 13,
                "maxZoom": 20,
                "cacheMaxSeconds": 600,
            }
        ]
    },
}


def fail(message):
    print(f"ERROR: {message}", file=sys.stderr)
    sys.exit(1)


def log(message):
    print(f"[start] {message}")


def tdei_token(base_url):
    token = os.environ.get("TDEI_AUTH_TOKEN", "").strip()
    if token:
        return token
    username = os.environ.get("TDEI_USERNAME")
    password = os.environ.get("TDEI_PASSWORD")
    if not (username and password):
        fail("set TDEI_AUTH_TOKEN, or TDEI_USERNAME and TDEI_PASSWORD")
    request = urllib.request.Request(
        f"{base_url}/api/v1/authenticate",
        data=json.dumps({"username": username, "password": password}).encode(),
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)["access_token"]
    except urllib.error.HTTPError as e:
        fail(f"TDEI refused the username and password: HTTP {e.code}")
    except (urllib.error.URLError, KeyError, ValueError) as e:
        fail(f"could not get a token from TDEI: {e}")


def download(url, target, what, headers=None):
    """Download to a file that only gets its name when it is complete."""
    log(f"downloading {what}")
    partial = target.with_name(target.name + ".part")
    request = urllib.request.Request(url, headers={"User-Agent": "otp-accessmap"})
    # TDEI redirects a download to signed storage, which must not be sent the token
    for name, value in (headers or {}).items():
        request.add_unredirected_header(name, value)
    try:
        with urllib.request.urlopen(request, timeout=600) as response, open(partial, "wb") as out:
            shutil.copyfileobj(response, out, READ_SIZE)
    except urllib.error.HTTPError as e:
        fail(f"could not download {what}: HTTP {e.code}")
    except urllib.error.URLError as e:
        fail(f"could not download {what}: {e.reason}")
    partial.rename(target)


def unzip(archive, target):
    """Unzip, and the zips inside it too, refusing a path that leaves the target."""
    root = target.resolve()
    try:
        with zipfile.ZipFile(archive) as z:
            for member in z.infolist():
                if not (target / member.filename).resolve().is_relative_to(root):
                    fail(f"unsafe path in {archive.name}: {member.filename}")
            z.extractall(target)
    except zipfile.BadZipFile:
        fail(f"{archive.name} is not a zip file")


def unzip_all(archive, directory):
    """Unzip into a new directory, and the zips inside it too."""
    shutil.rmtree(directory, ignore_errors=True)
    directory.mkdir()
    unzip(archive, directory)
    archive.unlink()
    for inner in list(directory.rglob("*.zip")):
        unzip(inner, inner.parent)
        inner.unlink()


def fetch_osw(dataset_id, directory, base_url, token):
    """Fetch the dataset from TDEI and unzip it into the directory."""
    archive = BASE_DIR / "osw-download.zip"
    download(
        f"{base_url}/api/v1/osw/{dataset_id}?format=osw&file_version=latest",
        archive,
        f"OpenSidewalks dataset {dataset_id} from TDEI",
        {"Authorization": f"Bearer {token}"},
    )
    unzip_all(archive, directory)


def fetch_tdei_feed(kind, dataset_id, target, base_url, token):
    """Fetch a GTFS dataset from TDEI, as a zip with the files of the feed at its root, as OTP needs."""
    archive = BASE_DIR / "feed-download.zip"
    download(
        f"{base_url}/api/v1/{kind}/{dataset_id}",
        archive,
        f"{kind} dataset {dataset_id} from TDEI",
        {"Authorization": f"Bearer {token}"},
    )
    directory = BASE_DIR / "feed-download"
    unzip_all(archive, directory)
    stops = sorted(directory.rglob("stops.txt"))
    if len(stops) != 1:
        fail(f"expected one stops.txt in {kind} dataset {dataset_id}, found {len(stops)}")
    missing = [f for f in ("agency.txt", "trips.txt") if not (stops[0].parent / f).exists()]
    if missing:
        fail(
            f"{kind} dataset {dataset_id} is not a whole GTFS feed, it has no {' or '.join(missing)}. "
            "OTP can not load it, or join it to a feed from elsewhere."
        )
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as z:
        for file in stops[0].parent.iterdir():
            if file.is_file():
                z.write(file, file.name)
    shutil.rmtree(directory)


def find_edges(directory):
    edges = sorted(directory.rglob("*edges*.geojson"))
    if len(edges) != 1:
        fail(f"expected one edges file in the dataset, found {[e.name for e in edges]}")
    if not list(edges[0].parent.glob("*nodes*.geojson")):
        fail(f"the dataset has no nodes file beside {edges[0].name}")
    return edges[0]


def bounds(edges):
    """The bounds of the coordinates of a file that can be too large to parse into memory."""
    west, south, east, north = 180.0, 90.0, -180.0, -90.0
    with open(edges, "rb") as f:
        rest = b""
        while chunk := f.read(READ_SIZE):
            text = rest + chunk
            end = text.rfind(b"]")
            rest = text[end + 1 :] if end >= 0 else text
            for lon, lat in COORDINATE.findall(text[: end + 1]):
                lon, lat = float(lon), float(lat)
                west, south, east, north = min(west, lon), min(south, lat), max(east, lon), max(north, lat)
    if west > east:
        fail(f"no coordinates in {edges.name}")
    return west, south, east, north


def is_polygon(polygon):
    return (
        isinstance(polygon, list)
        and len(polygon) > 0
        and all(
            isinstance(ring, list)
            and len(ring) >= 4
            and all(
                isinstance(p, list) and len(p) >= 2 and all(isinstance(c, (int, float)) for c in p[:2])
                for p in ring
            )
            for ring in polygon
        )
    )


def area_polygons(area):
    """The polygons of a GeoJSON area, which are not usable at all if any of them is not."""
    if not isinstance(area, dict):
        return []
    kind = area.get("type")
    if kind == "FeatureCollection":
        features = area.get("features")
        return [p for f in features for p in area_polygons(f)] if isinstance(features, list) else []
    if kind == "Feature":
        return area_polygons(area.get("geometry"))
    polygons = {"Polygon": [area.get("coordinates")], "MultiPolygon": area.get("coordinates")}.get(kind)
    if isinstance(polygons, list) and polygons and all(is_polygon(p) for p in polygons):
        return polygons
    return []


def dataset_polygons(directory):
    """The polygons of the area a dataset has in its metadata, if it has one."""
    for metadata in sorted(directory.rglob("*metadata.json")):
        try:
            polygons = area_polygons(json.loads(metadata.read_text())["dataset_detail"]["dataset_area"])
        except (ValueError, KeyError, TypeError):
            continue
        if polygons:
            return polygons
    return None


def region(number, name, edges, polygons):
    """A region of the web app: the area in the metadata of a dataset, or else around its edges."""
    west, south, east, north = bounds(edges)
    # On the sidewalks, since the middle of an area of several parts can be in none of them
    lon, lat = (west + east) / 2, (south + north) / 2
    margin = 0.005
    west, south, east, north = west - margin, south - margin, east + margin, north + margin
    if polygons:
        for x, y, *_ in (p for polygon in polygons for p in polygon[0]):
            west, south, east, north = min(west, x), min(south, y), max(east, x), max(north, y)
    else:
        polygons = [[[[west, south], [east, south], [east, north], [west, north], [west, south]]]]
    return {
        "type": "Feature",
        "geometry": {"type": "MultiPolygon", "coordinates": polygons},
        "properties": {
            "id": f"dataset-{number}",
            "key": f"dataset-{number}",
            "name": name,
            "bounds": [west, south, east, north],
            "lon": lon,
            "lat": lat,
            "zoom": 14.5,
        },
    }


def dataset_name(directory):
    """The name a dataset has in its metadata, if it has one."""
    for metadata in sorted(directory.rglob("*metadata.json")):
        try:
            name = json.loads(metadata.read_text())["dataset_detail"]["name"]
        except (ValueError, KeyError, TypeError):
            continue
        if isinstance(name, str) and name.strip():
            return name.strip()
    return None


def regions(dataset_ids, all_edges):
    """The regions the web app offers, one for each dataset, which it starts in the first of."""
    names = [n.strip() for n in os.environ.get("REGION_NAMES", "").split(",") if n.strip()]
    if names and len(names) != len(all_edges):
        fail(f"REGION_NAMES has {len(names)} names for {len(all_edges)} OpenSidewalks datasets")
    names = names or [
        dataset_name(BASE_DIR / "osw" / dataset_id) or f"Area {n}"
        for n, dataset_id in enumerate(dataset_ids, 1)
    ]
    features = [
        region(n, name, edges, dataset_polygons(BASE_DIR / "osw" / dataset_id))
        for n, (name, edges, dataset_id) in enumerate(zip(names, all_edges, dataset_ids), 1)
    ]
    return {"type": "FeatureCollection", "features": features}


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2))


def otp_config(tdei_feeds):
    features = {"OpenSidewalks": True, "SandboxAPIMapboxVectorTilesApi": True}
    if any(kind == "gtfs-flex" for kind, _ in tdei_feeds):
        features["FlexRouting"] = True
    return {"otpFeatures": features}


def prepare_graph(dataset_ids, feeds, tdei_feeds):
    """Download the data and build the graph, unless the graph of this data is already there."""
    osw_dir = BASE_DIR / "osw"
    graph = BASE_DIR / "graph.obj"
    stamp = BASE_DIR / "graph.key"
    key = hashlib.sha256(
        json.dumps([dataset_ids, feeds, tdei_feeds, jar_stamp()]).encode()
    ).hexdigest()
    rebuild = os.environ.get("REBUILD_GRAPH", "").lower() in ("1", "true", "yes")
    current = graph.exists() and stamp.exists() and stamp.read_text() == key

    write_json(BASE_DIR / "otp-config.json", otp_config(tdei_feeds))
    write_json(BASE_DIR / "router-config.json", ROUTER_CONFIG)

    if current and not rebuild:
        log("the graph of this data is already built")
        return [find_edges(osw_dir / d) for d in dataset_ids], None

    graph.unlink(missing_ok=True)
    stamp.unlink(missing_ok=True)
    for old in BASE_DIR.glob("*gtfs*.zip"):
        old.unlink()
    base_url = os.environ.get("TDEI_BASE_URL", "https://api.tdei.us").rstrip("/")
    token = tdei_token(base_url)
    shutil.rmtree(osw_dir, ignore_errors=True)
    osw_dir.mkdir()
    for dataset_id in dataset_ids:
        fetch_osw(dataset_id, osw_dir / dataset_id, base_url, token)
    for kind, feed_id in tdei_feeds:
        fetch_tdei_feed(kind, feed_id, BASE_DIR / f"tdei-{feed_id}.gtfs.zip", base_url, token)
    for number, url in enumerate(feeds, 1):
        download(url, BASE_DIR / f"feed-{number}.gtfs.zip", f"GTFS feed {number} of {len(feeds)}")

    all_edges = [find_edges(osw_dir / d) for d in dataset_ids]
    shutil.copy("/app/ws-prod.json", BASE_DIR / "ws-prod.json")
    write_json(
        BASE_DIR / "build-config.json",
        {
            "transitServiceStart": "-P1W",
            "transitServiceEnd": "P6M",
            "openSidewalks": {
                "sources": [str(e.relative_to(BASE_DIR)) for e in all_edges],
                "profile": "ws-prod.json",
            },
        },
    )
    return all_edges, key


def jar_stamp():
    jar = Path("/app/otp.jar").stat()
    return [jar.st_size, jar.st_mtime]


def prepare_ui(dataset_ids, all_edges, maptiler_key):
    shutil.rmtree(UI_DIR, ignore_errors=True)
    shutil.copytree("/app/ui", UI_DIR)
    write_json(
        UI_DIR / "config.json",
        {"maptilerKey": maptiler_key, "regions": regions(dataset_ids, all_edges)},
    )


def java(*arguments):
    return ["java", *os.environ.get("JAVA_OPTS", "").split(), "-jar", "/app/otp.jar", *arguments]


def env_list(name):
    return [item for item in re.split(r"[\s,]+", os.environ.get(name, "").strip()) if item]


def main():
    dataset_ids = env_list("TDEI_OSW_DATASET_IDS")
    if not dataset_ids:
        fail("set TDEI_OSW_DATASET_IDS to the ids of TDEI OpenSidewalks datasets")
    for dataset_id in dataset_ids:
        if not DATASET_ID.match(dataset_id):
            fail(f"TDEI_OSW_DATASET_IDS has something that is not a dataset id: {dataset_id}")
    tdei_feeds = []
    for name, kind in TDEI_FEED_KINDS.items():
        for feed_id in env_list(name):
            if not DATASET_ID.match(feed_id):
                fail(f"{name} has something that is not a dataset id: {feed_id}")
            tdei_feeds.append((kind, feed_id))
    feeds = env_list("GTFS_URLS")
    for feed in feeds:
        if urllib.parse.urlparse(feed).scheme not in ("http", "https"):
            fail(f"GTFS_URLS has to be http or https URLs, and has {feed}")
    maptiler_key = os.environ.get("MAPTILER_KEY", "").strip()
    if not maptiler_key:
        fail("set MAPTILER_KEY, for the base map and the search for places")

    all_edges, build_key = prepare_graph(dataset_ids, feeds, tdei_feeds)
    prepare_ui(dataset_ids, all_edges, maptiler_key)

    # Started before the graph build, so the page can be seen while that goes on
    nginx = subprocess.Popen(["nginx", "-c", "/app/nginx.conf", "-g", "daemon off;"])
    running = [nginx]

    def stop(*_):
        for process in running:
            if process.poll() is None:
                process.terminate()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)

    if build_key:
        log("building the graph")
        builder = subprocess.Popen(java("--build", "--save", str(BASE_DIR)))
        running.append(builder)
        if builder.wait() != 0:
            stop()
            fail(f"the graph build failed with exit code {builder.returncode}")
        (BASE_DIR / "graph.key").write_text(build_key)

    log("starting OTP")
    otp = subprocess.Popen(java("--load", "--port", OTP_PORT, str(BASE_DIR)))
    running.append(otp)
    code = otp.wait()
    stop()
    nginx.wait()
    sys.exit(code)


if __name__ == "__main__":
    main()

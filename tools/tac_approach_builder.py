#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
TunnelAC 접근도로 오프라인 계산기 (PC용)

앱에서 내보낸 .tac 파일의 터널(100m 이상)에 대해, OSM 한국 추출 파일(.osm.pbf)로
접근도로를 계산해 .tac 파일을 새로 만든다. 서버(Overpass) 없이 앱과 같은 알고리즘으로 계산한다.
만들어진 .tac 은 폰의 설정 > 터널 데이터 > "데이터 가져오기"로 적용한다.

사용법:
  pip install osmium
  python tac_approach_builder.py --download                       # (선택) 한국 OSM 파일 받기
  python tac_approach_builder.py --tac 내보낸파일.tac --pbf south-korea-latest.osm.pbf --out 결과.tac
  (접근도로와 함께, 터널·접근도로 곁을 지나는 인접도로(옆도로)도 계산한다. 끄려면 --no-adjacent)

데이터 출처: OpenStreetMap contributors (ODbL)
"""
import argparse
import gzip
import json
import math
import os
import sys
import time
import urllib.request

sys.setrecursionlimit(20000)

PBF_URL = "https://download.geofabrik.de/asia/south-korea-latest.osm.pbf"
ROAD_CLASSES = {
    "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
    "living_street", "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
}
ROAD_RADIUS_M = 550.0      # 앱의 ROAD_RADIUS_M 과 같다(입구 주변 도로 선택 범위)
MIN_TUNNEL_M = 100.0       # 앱의 APPROACH_MIN_TUNNEL_M
MAIN_M = 500.0             # ApproachBuilder.MAIN_M
BRANCH_M = 100.0           # ApproachBuilder.BRANCH_M
MAX_PATHS_PER_END = 40     # ApproachBuilder.MAX_PATHS_PER_END
CELL_DEG = 0.005
ADJ_MAX_M = 40.0           # 인접도로: 터널선·접근도로선에서 이 거리 이내를 지나는 도로
ADJ_EXCLUDE_M = 2.0        # 이 거리 이내(= 접근도로·터널과 같은 도로)는 인접도로에서 제외
ADJ_DENSE_M = 25.0         # 도로를 이 간격(m)으로 촘촘히 나눠 거리를 잰다
ADJ_MAX_RUNS = 30          # 터널당 최대 인접도로 선 조각 수(가까운 것 우선)
EARTH_R = 6371000.0


# ---------------------------------------------------------------- 지리 계산 (앱 GeoMath 와 동일)
def dist_m(a, b):
    d_lat = math.radians(b[0] - a[0])
    d_lon = math.radians(b[1] - a[1])
    la1, la2 = math.radians(a[0]), math.radians(b[0])
    h = math.sin(d_lat / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin(d_lon / 2) ** 2
    return EARTH_R * 2 * math.atan2(math.sqrt(h), math.sqrt(1 - h))


def bearing(a, b):
    la1, la2 = math.radians(a[0]), math.radians(b[0])
    d_lon = math.radians(b[1] - a[1])
    y = math.sin(d_lon) * math.cos(la2)
    x = math.cos(la1) * math.sin(la2) - math.sin(la1) * math.cos(la2) * math.cos(d_lon)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def angle_diff(a, b):
    d = abs(a - b) % 360.0
    return 360.0 - d if d > 180.0 else d


def polyline_len(pts):
    return sum(dist_m(pts[i], pts[i + 1]) for i in range(len(pts) - 1))


def oneway_of(tags):
    """앱 onewayOf 와 동일: 1=정방향, -1=역방향, 0=양방향."""
    v = (tags.get("oneway") or "").lower()
    if v in ("yes", "true", "1"):
        return 1
    if v in ("-1", "reverse"):
        return -1
    if v in ("no", "false", "0"):
        return 0
    hw = tags.get("highway") or ""
    jn = tags.get("junction") or ""
    if hw in ("motorway", "motorway_link") or jn in ("roundabout", "circular"):
        return 1
    return 0


# ---------------------------------------------------------------- 접근도로 계산 (앱 ApproachBuilder 포팅)
class Road:
    __slots__ = ("nodes", "pts", "oneway")

    def __init__(self, nodes, pts, oneway):
        self.nodes, self.pts, self.oneway = nodes, pts, oneway


class RoadIndex:
    def __init__(self, roads):
        self.roads = roads
        self.by_node = {}
        for ri, r in enumerate(roads):
            for pi, n in enumerate(r.nodes):
                self.by_node.setdefault(n, []).append((ri, pi))


class Tracer:
    def __init__(self, ix, out):
        self.ix, self.out = ix, out

    def candidates(self, n, exclude):
        res = []
        for ri, pi in self.ix.by_node.get(n, ()):
            if ri == exclude:
                continue
            road = self.ix.roads[ri]
            last = len(road.nodes) - 1
            if pi > 0 and road.oneway >= 0:
                res.append((ri, pi, -1))
            if pi < last and road.oneway <= 0:
                res.append((ri, pi, 1))
        return res

    def arriving_bearing(self, c):
        ri, pi, d = c
        p = self.ix.roads[ri].pts
        return bearing(p[pi + d], p[pi])

    def emit(self, suf):
        if len(suf) >= 2 and len(self.out) < MAX_PATHS_PER_END:
            self.out.append(suf)

    def expand(self, n, from_road, suf, rem, branch, vis, fallback):
        if len(self.out) >= MAX_PATHS_PER_END:
            return
        cands = self.candidates(n, from_road)
        if not cands:
            self.emit(suf)
            return
        depart = bearing(suf[0], suf[1]) if len(suf) >= 2 else fallback
        cands.sort(key=lambda c: angle_diff(self.arriving_bearing(c), depart))
        self.walk(cands[0], suf, rem, branch, vis)
        if not branch:
            for c in cands[1:]:
                self.walk(c, suf, min(rem, BRANCH_M), True, vis)

    def walk(self, c, suf_in, rem_in, branch, vis_in):
        ri, pi, d = c
        road = self.ix.roads[ri]
        p = road.pts
        suf, rem, k = suf_in, rem_in, pi
        vis = set(vis_in)
        while len(self.out) < MAX_PATHS_PER_END:
            nk = k + d
            if nk < 0 or nk >= len(p):
                self.emit(suf)
                return
            rem -= dist_m(p[k], p[nk])
            suf = [p[nk]] + suf
            k = nk
            node = road.nodes[k]
            if rem <= 0.0:
                self.emit(suf)
                return
            if node in vis:
                self.emit(suf)
                return
            vis.add(node)
            at_end = (k + d < 0 or k + d >= len(p))
            if at_end:
                self.expand(node, ri, suf, rem, branch, vis, 0.0)
                return
            if not branch:
                for side in self.candidates(node, ri):
                    self.walk(side, suf, min(rem, BRANCH_M), True, vis)


def build_approaches(tunnel, ix):
    """tunnel = dict(pts=[(lat,lon)..], nodes=[id..], oneway=int) -> [(endIdx, pts)]"""
    pts, nodes = tunnel["pts"], tunnel["nodes"]
    if len(pts) < 2 or len(nodes) != len(pts):
        return []
    result = []
    for end_idx in (0, 1):
        if tunnel["oneway"] == 1 and end_idx == 1:
            continue
        if tunnel["oneway"] == -1 and end_idx == 0:
            continue
        end_node = nodes[0] if end_idx == 0 else nodes[-1]
        end_pt = pts[0] if end_idx == 0 else pts[-1]
        inner = pts[1] if end_idx == 0 else pts[-2]
        paths = []
        Tracer(ix, paths).expand(end_node, -1, [end_pt], MAIN_M, False, {end_node}, bearing(end_pt, inner))
        for p in paths:
            if len(p) >= 2:
                result.append((end_idx, p))
    return result


# ---------------------------------------------------------------- 인접도로 계산
def densify(pts, step_m):
    """원래 꼭짓점을 유지하면서 구간마다 step_m 이하 간격으로 점을 끼워 넣는다."""
    out = [pts[0]]
    for i in range(1, len(pts)):
        a, b = pts[i - 1], pts[i]
        d = dist_m(a, b)
        n = int(d // step_m)
        for k in range(1, n + 1):
            t = k * step_m / d
            if t < 1.0:
                out.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
        out.append(b)
    return out


def build_road_grid(roads):
    """도로 way 가 지나는 격자 칸 -> way 번호 목록."""
    grid = {}
    for ri, road in enumerate(roads):
        for p in densify(road.pts, 100.0):
            grid.setdefault(cell_of(p[0], p[1]), set()).add(ri)
    return grid


def build_adjacent(tunnel_pts, approach_paths, roads, road_grid):
    """터널선·접근도로선 곁(ADJ_MAX_M 이내)을 지나는 일반 도로 조각들. 접근도로·터널과 같은 도로는 제외."""
    lines = [tunnel_pts] + approach_paths
    lat0, lon0 = tunnel_pts[0]
    cl = math.cos(math.radians(lat0))

    def P(p):
        return ((p[1] - lon0) * 111320.0 * cl, (p[0] - lat0) * 111320.0)

    G = 100.0
    grid = {}
    seen = set()
    for line in lines:
        xy = [P(p) for p in line]
        for i in range(len(xy) - 1):
            a, b = xy[i], xy[i + 1]
            key = (round(a[0], 1), round(a[1], 1), round(b[0], 1), round(b[1], 1))
            if key in seen:
                continue
            seen.add(key)
            for cx in range(int((min(a[0], b[0]) - ADJ_MAX_M) // G), int((max(a[0], b[0]) + ADJ_MAX_M) // G) + 1):
                for cy in range(int((min(a[1], b[1]) - ADJ_MAX_M) // G), int((max(a[1], b[1]) + ADJ_MAX_M) // G) + 1):
                    grid.setdefault((cx, cy), []).append((a, b))

    def vdist(p):
        x, y = P(p)
        segs = grid.get((int(x // G), int(y // G)))
        if not segs:
            return None
        best = None
        for (a, b) in segs:
            abx, aby = b[0] - a[0], b[1] - a[1]
            ln = abx * abx + aby * aby
            t = 0.0 if ln == 0 else max(0.0, min(1.0, ((x - a[0]) * abx + (y - a[1]) * aby) / ln))
            dx, dy = x - (a[0] + t * abx), y - (a[1] + t * aby)
            d = math.sqrt(dx * dx + dy * dy)
            if best is None or d < best:
                best = d
        return best

    all_pts = [p for l in lines for p in l]
    margin = 0.0007
    r0, r1 = math.floor((min(p[0] for p in all_pts) - margin) / CELL_DEG), math.floor((max(p[0] for p in all_pts) + margin) / CELL_DEG)
    c0, c1 = math.floor((min(p[1] for p in all_pts) - margin) / CELL_DEG), math.floor((max(p[1] for p in all_pts) + margin) / CELL_DEG)
    cands = set()
    for rr in range(r0, r1 + 1):
        for cc in range(c0, c1 + 1):
            cands |= road_grid.get((rr, cc), set())

    runs = []
    for ri in cands:
        run, run_min = [], 1e9

        def flush():
            nonlocal run, run_min
            if len(run) >= 2:
                runs.append((run_min, run))
            run, run_min = [], 1e9

        for p in densify(roads[ri].pts, ADJ_DENSE_M):
            d = vdist(p)
            if d is not None and ADJ_EXCLUDE_M < d <= ADJ_MAX_M:
                run.append(p)
                run_min = min(run_min, d)
            else:
                flush()
        flush()
    runs.sort(key=lambda x: x[0])
    return [pts for _, pts in runs[:ADJ_MAX_RUNS]]


def fmt_pts6(pts):
    return ";".join("%.6f,%.6f" % (p[0], p[1]) for p in pts)


# ---------------------------------------------------------------- OSM 읽기
def load_osmium():
    try:
        import osmium  # noqa
        return osmium
    except ImportError:
        sys.exit("osmium 모듈이 없습니다. 먼저 `pip install osmium` 을 실행해 주세요.")


def cell_of(lat, lon):
    return (math.floor(lat / CELL_DEG), math.floor(lon / CELL_DEG))


def mark_cells(entrances, bodies=()):
    cells = set()
    # 인접도로용: 터널 몸통(점들)과 주변 한 칸까지
    for pts in bodies:
        for lat, lon in densify(pts, 100.0):
            r0, c0 = cell_of(lat, lon)
            for dr in (-1, 0, 1):
                for dc in (-1, 0, 1):
                    cells.add((r0 + dr, c0 + dc))
    for lat, lon in entrances:
        d_lat = ROAD_RADIUS_M / 111320.0
        d_lon = ROAD_RADIUS_M / (111320.0 * max(math.cos(math.radians(lat)), 0.1))
        r0, r1 = math.floor((lat - d_lat) / CELL_DEG), math.floor((lat + d_lat) / CELL_DEG)
        c0, c1 = math.floor((lon - d_lon) / CELL_DEG), math.floor((lon + d_lon) / CELL_DEG)
        for r in range(r0, r1 + 1):
            for c in range(c0, c1 + 1):
                cells.add((r, c))
    return cells


def read_tunnel_nodes(osmium, pbf, wanted, idx):
    """1차 읽기: 대상 터널 way 의 노드 id 와 일방통행 태그."""
    found = {}

    class H(osmium.SimpleHandler):
        def way(self, w):
            if w.id in wanted:
                found[w.id] = ([n.ref for n in w.nodes], oneway_of({t.k: t.v for t in w.tags}))

    H().apply_file(pbf, locations=False)
    return found


def read_roads(osmium, pbf, cells, idx):
    """2차 읽기: 터널 입구 주변(격자)에 걸친 일반 도로(터널 구간 제외)."""
    roads = []
    stats = {"scanned": 0}

    class H(osmium.SimpleHandler):
        def way(self, w):
            hw = w.tags.get("highway")
            if hw is None or hw not in ROAD_CLASSES:
                return
            tun = w.tags.get("tunnel")
            if tun and tun != "no":
                return  # 터널 구간은 접근도로에서 제외(앱 RoadIndex 와 동일)
            stats["scanned"] += 1
            pts, ids, hit = [], [], False
            try:
                for n in w.nodes:
                    lat, lon = n.lat, n.lon
                    pts.append((lat, lon))
                    ids.append(n.ref)
                    if not hit and cell_of(lat, lon) in cells:
                        hit = True
            except Exception:
                return  # 좌표가 없는 노드가 있는 도로는 건너뜀
            if hit and len(pts) >= 2:
                roads.append(Road(ids, pts, oneway_of({t.k: t.v for t in w.tags})))

    H().apply_file(pbf, locations=True, idx=idx)
    return roads, stats["scanned"]


# ---------------------------------------------------------------- 메인
def parse_pts(s):
    out = []
    for part in s.split(";"):
        if part.strip():
            a, b = part.split(",")
            out.append((float(a), float(b)))
    return out


def fmt_pts(pts):
    return ";".join("%s,%s" % (p[0], p[1]) for p in pts)


def download(dest):
    print("다운로드:", PBF_URL)
    req = urllib.request.Request(PBF_URL, headers={"User-Agent": "TunnelAC-offline-builder/1.0"})
    with urllib.request.urlopen(req) as r, open(dest, "wb") as f:
        total = int(r.headers.get("Content-Length") or 0)
        got = 0
        while True:
            buf = r.read(1 << 20)
            if not buf:
                break
            f.write(buf)
            got += len(buf)
            if total:
                print("\r  %d / %d MB" % (got >> 20, total >> 20), end="", flush=True)
    print("\n저장:", dest)


def main():
    ap = argparse.ArgumentParser(description="TunnelAC 접근도로 오프라인 계산기")
    ap.add_argument("--download", action="store_true", help="한국 OSM 파일(south-korea-latest.osm.pbf)을 받는다")
    ap.add_argument("--tac", help="앱에서 내보낸 .tac 파일")
    ap.add_argument("--pbf", default="south-korea-latest.osm.pbf", help="OSM 추출 파일(.osm.pbf 또는 .osm)")
    ap.add_argument("--out", help="결과 .tac 파일")
    ap.add_argument("--no-adjacent", action="store_true", help="인접도로 계산을 건너뛴다(접근도로만 계산)")
    ap.add_argument("--idx", default="flex_mem", help="노드 위치 색인(메모리가 부족하면 sparse_file_array,임시파일경로)")
    a = ap.parse_args()

    if a.download:
        download(a.pbf)
        if not a.tac:
            return
    if not (a.tac and a.out):
        ap.error("--tac 와 --out 이 필요합니다.")
    if not os.path.exists(a.pbf):
        sys.exit("OSM 파일이 없습니다: %s  (먼저 --download 로 받으세요)" % a.pbf)
    osmium = load_osmium()
    t0 = time.time()

    print("[1/4] .tac 읽는 중...")
    with gzip.open(a.tac, "rb") as f:
        data = json.loads(f.read().decode("utf-8"))
    if data.get("format") != "tunnelac-data":
        sys.exit("터널 데이터 파일(.tac)이 아닙니다.")
    targets = {}
    for t in data["tunnels"]:
        if t.get("len", 0) >= MIN_TUNNEL_M:
            targets[t["id"]] = parse_pts(t["pts"])
    print("  터널 %d개, 접근도로 대상(100m 이상) %d개, 기존 접근도로 %d개" % (len(data["tunnels"]), len(targets), len(data["approaches"])))

    print("[2/4] 터널 way 노드 정보 읽는 중(1차)...")
    tn = read_tunnel_nodes(osmium, a.pbf, set(targets), a.idx)
    tunnels, bad = [], 0
    for tid, pts in targets.items():
        info = tn.get(tid)
        if info is None or len(info[0]) != len(pts):
            bad += 1  # OSM에서 사라졌거나 모양이 바뀐 터널: 기존 접근도로를 그대로 둔다
            continue
        tunnels.append({"id": tid, "pts": pts, "nodes": info[0], "oneway": info[1]})
    print("  계산 대상 %d개 (OSM 파일에서 못 찾았거나 모양이 달라 제외 %d개)  [%.0f초]" % (len(tunnels), bad, time.time() - t0))

    entrances = []
    for t in tunnels:
        if t["oneway"] != -1:
            entrances.append(t["pts"][0])
        if t["oneway"] != 1:
            entrances.append(t["pts"][-1])
    cells = mark_cells(entrances, [] if a.no_adjacent else [t["pts"] for t in tunnels])

    print("[3/4] 입구 주변 도로 읽는 중(2차, 몇 분 걸릴 수 있음)...")
    roads, scanned = read_roads(osmium, a.pbf, cells, a.idx)
    print("  도로 way %d개 중 입구 주변 %d개 선택  [%.0f초]" % (scanned, len(roads), time.time() - t0))
    ix = RoadIndex(roads)

    print("[4/4] 접근도로 계산 중...")
    new_rows, done_ids, adj_rows = [], [], []
    road_grid = None if a.no_adjacent else build_road_grid(roads)
    cnt = {0: 0, 1: 0, 2: 0}
    for i, t in enumerate(tunnels):
        res = build_approaches(t, ix)
        for end_idx, pts in res:
            new_rows.append({"t": t["id"], "e": end_idx, "pts": fmt_pts(pts)})
        cnt[len({e for e, _ in res})] += 1
        done_ids.append(t["id"])
        if road_grid is not None:
            for pts in build_adjacent(t["pts"], [p for _, p in res], roads, road_grid):
                adj_rows.append({"t": t["id"], "pts": fmt_pts6(pts)})
        if (i + 1) % 1000 == 0:
            print("  %d/%d" % (i + 1, len(tunnels)))
    print("  접근도로가 있는 입구 수별 터널: 0곳=%d, 1곳=%d, 2곳=%d" % (cnt[0], cnt[1], cnt[2]))

    # 계산한 터널은 새 결과로 교체하고, 계산하지 못한 터널(OSM에서 못 찾음)은 기존 것을 유지한다.
    recomputed = {t["id"] for t in tunnels}
    kept = [r for r in data["approaches"] if r["t"] not in recomputed]
    data["approaches"] = kept + new_rows
    data["approachCount"] = len(data["approaches"])
    data["createdMs"] = int(time.time() * 1000)
    data["source"] = "OpenStreetMap contributors (ODbL)"
    if road_grid is not None:
        data["adjacent"] = adj_rows
        print("  인접도로 선 조각 %d개" % len(adj_rows))
    data["doneIds"] = done_ids  # 앱이 '접근도로 보완' 대상에서 뺄 터널(계산을 마친 터널)
    with gzip.open(a.out, "wb") as f:
        f.write(json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
    print("완료: %s  (접근도로 %d개 입구선, %.0f초)" % (a.out, len(data["approaches"]), time.time() - t0))
    print("폰에서 설정 > 터널 데이터 > 데이터 가져오기 로 이 파일을 선택하세요.")


if __name__ == "__main__":
    main()

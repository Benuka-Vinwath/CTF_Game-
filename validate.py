"""
Python validation of the C++ solution logic.
Tests on the given example:
  4 generators: (-10,1,C=0,W=100), (10,1,C=0,W=100), (-10,-1,C=10,W=1), (10,-1,C=10,W=1)
  2 queries:    (-10,5), (10,5)
Expected hash:  25   (adjacencies 1-2, 1-3, 2-4, 3-4)
Expected cost:  10.00
"""

import math
from collections import deque

EPS = 1e-9
INF = float('inf')
MOD = 10**9 + 7

# ─────────────────────────────────────────────
# Bowyer-Watson Delaunay Triangulation
# ─────────────────────────────────────────────

def circumcircle(ax, ay, bx, by, cx, cy):
    """Returns (ccx, ccy, r2) of circumcircle through (ax,ay),(bx,by),(cx,cy)."""
    dx = bx - ax; dy = by - ay
    ex = cx - ax; ey = cy - ay
    D = 2.0 * (dx * ey - dy * ex)
    if abs(D) < 1e-10:
        return 0.0, 0.0, 1e30   # degenerate
    ux = (ey * (dx*dx + dy*dy) - dy * (ex*ex + ey*ey)) / D
    uy = (dx * (ex*ex + ey*ey) - ex * (dx*dx + dy*dy)) / D
    return ax + ux, ay + uy, ux*ux + uy*uy

def in_circumcircle(px, py, ccx, ccy, r2):
    return (px - ccx)**2 + (py - ccy)**2 < r2 - EPS

def bowyer_watson(points):
    """
    points: list of (x, y, id)  id is 1-indexed
    Returns set of frozensets {u, v} for Delaunay edges (1-indexed ids).
    """
    n = len(points)
    # points extended with super-triangle
    pts = list(points)   # (x, y, id)

    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    mnx, mxx = min(xs), max(xs)
    mny, mxy = min(ys), max(ys)
    cx0 = (mnx + mxx) / 2; cy0 = (mny + mxy) / 2
    R = max(mxx - mnx, mxy - mny) + 1
    R *= 20

    S0 = n;     pts.append((cx0,        cy0 + 2*R, -1))
    S1 = n + 1; pts.append((cx0 - 2*R, cy0 - R,   -1))
    S2 = n + 2; pts.append((cx0 + 2*R, cy0 - R,   -1))

    def make_tri(a, b, c):
        ccx, ccy, r2 = circumcircle(pts[a][0], pts[a][1],
                                     pts[b][0], pts[b][1],
                                     pts[c][0], pts[c][1])
        return (a, b, c, ccx, ccy, r2)

    triangles = [make_tri(S0, S1, S2)]

    for i in range(n):
        px, py = pts[i][0], pts[i][1]

        # Find bad triangles
        bad = [j for j, t in enumerate(triangles)
               if in_circumcircle(px, py, t[3], t[4], t[5])]

        # Count edges among bad triangles
        from collections import Counter
        edge_cnt = Counter()
        for j in bad:
            a, b, c = triangles[j][0], triangles[j][1], triangles[j][2]
            edge_cnt[tuple(sorted((a, b)))] += 1
            edge_cnt[tuple(sorted((b, c)))] += 1
            edge_cnt[tuple(sorted((a, c)))] += 1

        # Remove bad triangles
        bad_set = set(bad)
        triangles = [t for j, t in enumerate(triangles) if j not in bad_set]

        # Add new triangles
        for (ea, eb), cnt in edge_cnt.items():
            if cnt == 1:
                triangles.append(make_tri(ea, eb, i))

    # Extract edges between real points
    edges = set()
    for t in triangles:
        ia = pts[t[0]][2]; ib = pts[t[1]][2]; ic = pts[t[2]][2]
        if ia > 0 and ib > 0: edges.add((min(ia,ib), max(ia,ib)))
        if ib > 0 and ic > 0: edges.add((min(ib,ic), max(ib,ic)))
        if ia > 0 and ic > 0: edges.add((min(ia,ic), max(ia,ic)))
    return edges

# ─────────────────────────────────────────────
# SPFA Min-Cost Max-Flow
# ─────────────────────────────────────────────

class MCMF:
    def __init__(self, n):
        self.n = n
        self.graph = [[] for _ in range(n)]
        self.edges = []   # (to, cap, flow, cost)

    def add_edge(self, u, v, cap, cost):
        self.graph[u].append(len(self.edges))
        self.edges.append([v, cap, 0, cost])
        self.graph[v].append(len(self.edges))
        self.edges.append([u, 0, 0, -cost])

    def run(self, s, t, need):
        total_flow, total_cost = 0, 0.0
        while total_flow < need:
            dist = [INF] * self.n
            in_q = [False] * self.n
            prev = [-1] * self.n
            dist[s] = 0.0
            q = deque([s])
            in_q[s] = True
            while q:
                u = q.popleft()
                in_q[u] = False
                for eid in self.graph[u]:
                    to, cap, flow, cost = self.edges[eid]
                    if cap > flow and dist[to] > dist[u] + cost + EPS:
                        dist[to] = dist[u] + cost
                        prev[to] = eid
                        if not in_q[to]:
                            if q and dist[to] < dist[q[0]]:
                                q.appendleft(to)
                            else:
                                q.append(to)
                            in_q[to] = True
            if dist[t] >= INF / 2:
                break
            # bottleneck
            aug = need - total_flow
            v = t
            while v != s:
                eid = prev[v]
                aug = min(aug, self.edges[eid][1] - self.edges[eid][2])
                v = self.edges[eid ^ 1][0]
            # augment
            v = t
            while v != s:
                eid = prev[v]
                self.edges[eid][2]     += aug
                self.edges[eid ^ 1][2] -= aug
                v = self.edges[eid ^ 1][0]
            total_flow += aug
            total_cost += aug * dist[t]
        return total_flow, total_cost

# ─────────────────────────────────────────────
# MAIN TEST
# ─────────────────────────────────────────────

def solve(M, generators, N, queries):
    """
    generators: list of (x, y, C, W)  1-indexed positions
    queries:    list of (X, Y)
    """
    gx = [0] + [g[0] for g in generators]
    gy = [0] + [g[1] for g in generators]
    C  = [0] + [g[2] for g in generators]
    W  = [0] + [g[3] for g in generators]

    # Phase 1: Delaunay
    pts = [(gx[i], gy[i], i) for i in range(1, M+1)]
    adj = bowyer_watson(pts)

    H = 0
    for (u, v) in sorted(adj):
        H = (H + u * v) % MOD
    print(f"Hash H = {H}")
    print(f"Adjacent pairs: {sorted(adj)}")

    # Phase 2: assign queries to nearest generator
    supply = [0] * (M+1)
    for (X, Y) in queries:
        best, bd = 1, INF
        for i in range(1, M+1):
            d = (X - gx[i])**2 + (Y - gy[i])**2
            if d < bd:
                bd = d; best = i
        supply[best] += 1
    print(f"Supply: {supply[1:]}")

    # Build MCMF
    # Nodes: 0=source, 1..M=generators, M+1=sink
    fn = M + 2
    src, snk = 0, M + 1
    mcmf = MCMF(fn)
    for i in range(1, M+1):
        mcmf.add_edge(src, i, supply[i], 0.0)
    for i in range(1, M+1):
        mcmf.add_edge(i, snk, C[i], float(W[i]))
    for (u, v) in adj:
        dx, dy = gx[u]-gx[v], gy[u]-gy[v]
        dist = math.sqrt(dx*dx + dy*dy)
        mult = 2.0 if gy[u] * gy[v] < 0 else 1.0
        rc = dist * mult
        mcmf.add_edge(u, v, N, rc)
        mcmf.add_edge(v, u, N, rc)

    f, cost = mcmf.run(src, snk, N)
    if f < N:
        print("Result: -1.00")
    else:
        print(f"Result: {cost:.2f}")

# ──── Run the example ────
print("=" * 50)
print("EXAMPLE TEST")
print("=" * 50)
M = 4
generators = [(-10, 1, 0, 100), (10, 1, 0, 100), (-10, -1, 10, 1), (10, -1, 10, 1)]
N = 2
queries = [(-10, 5), (10, 5)]
solve(M, generators, N, queries)

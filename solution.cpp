#include <iostream>
#include <vector>
#include <cmath>
#include <algorithm>
#include <queue>
#include <set>
#include <iomanip>

using namespace std;

typedef long long ll;
typedef double ld;

const int MOD = 1000000007;
const ld EPS = 1e-9;
const ld INF_LD = 1e18;

struct Point {
    ld x, y;
    int id; // 1-indexed, or -1 for super-triangle
};

struct Triangle {
    int a, b, c; // indices in pts array
    ld cx, cy, r2;
    bool dead;
};

// Circumcircle calculation
void get_circumcircle(const Point& p1, const Point& p2, const Point& p3, ld& cx, ld& cy, ld& r2) {
    ld ax = p2.x - p1.x, ay = p2.y - p1.y;
    ld bx = p3.x - p1.x, by = p3.y - p1.y;
    ld D = 2.0 * (ax * by - ay * bx);
    if (fabs(D) < 1e-11) {
        cx = 0; cy = 0; r2 = 1e30;
        return;
    }
    ld ux = (by * (ax * ax + ay * ay) - ay * (bx * bx + by * by)) / D;
    ld uy = (ax * (bx * bx + by * by) - bx * (ax * ax + ay * ay)) / D;
    cx = p1.x + ux;
    cy = p1.y + uy;
    r2 = ux * ux + uy * uy;
}

// Check if point p is in circumcircle of t
inline bool in_circle(ld px, ld py, const Triangle& t) {
    ld dx = px - t.cx;
    ld dy = py - t.cy;
    return (dx * dx + dy * dy) < t.r2 - EPS;
}

struct Edge {
    int u, v;
    Edge(int _u = 0, int _v = 0) {
        if (_u < _v) { u = _u; v = _v; }
        else { u = _v; v = _u; }
    }
    bool operator<(const Edge& o) const {
        if (u != o.u) return u < o.u;
        return v < o.v;
    }
    bool operator==(const Edge& o) const {
        return u == o.u && v == o.v;
    }
};

// Fast Delaunay Triangulation using Bowyer-Watson
vector<pair<int, int>> get_voronoi_adjacencies(int M, const vector<Point>& pts_real) {
    int total_pts = M + 3;
    vector<Point> pts = pts_real;

    ld min_x = pts[0].x, max_x = pts[0].x;
    ld min_y = pts[0].y, max_y = pts[0].y;
    for (int i = 0; i < M; i++) {
        min_x = min(min_x, pts[i].x); max_x = max(max_x, pts[i].x);
        min_y = min(min_y, pts[i].y); max_y = max(max_y, pts[i].y);
    }

    ld cx0 = (min_x + max_x) * 0.5;
    ld cy0 = (min_y + max_y) * 0.5;
    ld dmax = max(max_x - min_x, max_y - min_y) + 1.0;
    ld R = dmax * 50.0;

    int S0 = M, S1 = M + 1, S2 = M + 2;
    pts.push_back({cx0, cy0 + 2.0 * R, -1});
    pts.push_back({cx0 - 2.0 * R, cy0 - R, -1});
    pts.push_back({cx0 + 2.0 * R, cy0 - R, -1});

    vector<Triangle> tris;
    tris.reserve(M * 8);

    Triangle super_tri;
    super_tri.a = S0; super_tri.b = S1; super_tri.c = S2;
    super_tri.dead = false;
    get_circumcircle(pts[S0], pts[S1], pts[S2], super_tri.cx, super_tri.cy, super_tri.r2);
    tris.push_back(super_tri);

    vector<Edge> polygon;
    vector<int> bad_idx;

    for (int i = 0; i < M; i++) {
        ld px = pts[i].x, py = pts[i].y;
        bad_idx.clear();
        for (int j = 0; j < (int)tris.size(); j++) {
            if (!tris[j].dead && in_circle(px, py, tris[j])) {
                bad_idx.push_back(j);
                tris[j].dead = true;
            }
        }

        polygon.clear();
        for (int idx : bad_idx) {
            const Triangle& t = tris[idx];
            polygon.push_back(Edge(t.a, t.b));
            polygon.push_back(Edge(t.b, t.c));
            polygon.push_back(Edge(t.c, t.a));
        }

        sort(polygon.begin(), polygon.end());

        // Boundary edges appear exactly once
        for (int k = 0; k < (int)polygon.size(); ) {
            int count = 1;
            while (k + count < (int)polygon.size() && polygon[k] == polygon[k + count]) {
                count++;
            }
            if (count == 1) {
                Triangle nt;
                nt.a = polygon[k].u;
                nt.b = polygon[k].v;
                nt.c = i;
                nt.dead = false;
                get_circumcircle(pts[nt.a], pts[nt.b], pts[nt.c], nt.cx, nt.cy, nt.r2);
                tris.push_back(nt);
            }
            k += count;
        }
    }

    // Now inspect all real triangles (where all 3 vertices < M)
    // To handle Voronoi adjacency, each edge between real vertices belongs to:
    // - 1 real triangle -> Voronoi ray of infinite length -> non-zero boundary -> ADJACENT
    // - 2 real triangles -> Voronoi segment connecting circumcenters C1 and C2.
    //   If dist(C1, C2) > 1e-7 -> non-zero boundary -> ADJACENT
    //   If dist(C1, C2) <= 1e-7 -> zero-length boundary -> NOT ADJACENT
    struct EdgeTri {
        int u, v;
        ld cx, cy;
    };
    vector<EdgeTri> edge_tris;
    for (int i = 0; i < (int)tris.size(); i++) {
        if (tris[i].dead) continue;
        if (tris[i].a < M && tris[i].b < M && tris[i].c < M) {
            edge_tris.push_back({min(tris[i].a, tris[i].b), max(tris[i].a, tris[i].b), tris[i].cx, tris[i].cy});
            edge_tris.push_back({min(tris[i].b, tris[i].c), max(tris[i].b, tris[i].c), tris[i].cx, tris[i].cy});
            edge_tris.push_back({min(tris[i].a, tris[i].c), max(tris[i].a, tris[i].c), tris[i].cx, tris[i].cy});
        }
    }

    sort(edge_tris.begin(), edge_tris.end(), [](const EdgeTri& e1, const EdgeTri& e2) {
        if (e1.u != e2.u) return e1.u < e2.u;
        return e1.v < e2.v;
    });

    vector<pair<int, int>> adj;
    for (int i = 0; i < (int)edge_tris.size(); ) {
        int j = i;
        while (j < (int)edge_tris.size() && edge_tris[j].u == edge_tris[i].u && edge_tris[j].v == edge_tris[i].v) {
            j++;
        }
        int count = j - i;
        int u = edge_tris[i].u;
        int v = edge_tris[i].v;

        if (count == 1) {
            // Convex hull edge -> unbounded Voronoi ray -> non-zero length
            adj.push_back({pts[u].id, pts[v].id});
        } else if (count == 2) {
            ld dx = edge_tris[i].cx - edge_tris[i + 1].cx;
            ld dy = edge_tris[i].cy - edge_tris[i + 1].cy;
            ld d = sqrt(dx * dx + dy * dy);
            if (d > 1e-7) {
                adj.push_back({pts[u].id, pts[v].id});
            }
        }
        i = j;
    }

    for (auto& p : adj) {
        if (p.first > p.second) swap(p.first, p.second);
    }
    sort(adj.begin(), adj.end());
    adj.erase(unique(adj.begin(), adj.end()), adj.end());

    return adj;
}

// Min-Cost Max-Flow (Successive Shortest Paths with SPFA)
struct FlowEdge {
    int to, cap, flow;
    ld cost;
};

vector<FlowEdge> fedges;
vector<vector<int>> fg;
int fn;

void add_arc(int u, int v, int cap, ld cost) {
    fg[u].push_back((int)fedges.size());
    fedges.push_back({v, cap, 0, cost});
    fg[v].push_back((int)fedges.size());
    fedges.push_back({u, 0, 0, -cost});
}

pair<int, ld> mcmf(int s, int t, int need) {
    int total_flow = 0;
    ld total_cost = 0.0;

    while (total_flow < need) {
        vector<ld> dist(fn, INF_LD);
        vector<bool> in_q(fn, false);
        vector<int> prev(fn, -1);

        dist[s] = 0.0;
        deque<int> q;
        q.push_back(s);
        in_q[s] = true;

        while (!q.empty()) {
            int u = q.front();
            q.pop_front();
            in_q[u] = false;

            for (int eid : fg[u]) {
                const FlowEdge& e = fedges[eid];
                if (e.cap > e.flow && dist[e.to] > dist[u] + e.cost + EPS) {
                    dist[e.to] = dist[u] + e.cost;
                    prev[e.to] = eid;
                    if (!in_q[e.to]) {
                        if (!q.empty() && dist[e.to] < dist[q.front()]) {
                            q.push_front(e.to);
                        } else {
                            q.push_back(e.to);
                        }
                        in_q[e.to] = true;
                    }
                }
            }
        }

        if (dist[t] >= INF_LD * 0.5) break;

        int aug = need - total_flow;
        for (int v = t; v != s; ) {
            int eid = prev[v];
            aug = min(aug, fedges[eid].cap - fedges[eid].flow);
            v = fedges[eid ^ 1].to;
        }

        for (int v = t; v != s; ) {
            int eid = prev[v];
            fedges[eid].flow += aug;
            fedges[eid ^ 1].flow -= aug;
            v = fedges[eid ^ 1].to;
        }

        total_flow += aug;
        total_cost += (ld)aug * dist[t];
    }

    return {total_flow, total_cost};
}

int main() {
    ios_base::sync_with_stdio(false);
    cin.tie(NULL);

    int M;
    if (!(cin >> M)) return 0;

    vector<Point> pts_real(M);
    vector<ld> gx(M + 1), gy(M + 1);
    vector<int> C(M + 1), W(M + 1);

    for (int i = 1; i <= M; i++) {
        cin >> gx[i] >> gy[i] >> C[i] >> W[i];
        pts_real[i - 1] = {gx[i], gy[i], i};
    }

    // Phase 1: Delaunay triangulation -> Voronoi adjacency -> hash
    vector<pair<int, int>> adj = get_voronoi_adjacencies(M, pts_real);

    ll H = 0;
    for (const auto& p : adj) {
        H = (H + (ll)p.first * (ll)p.second) % MOD;
    }

    cout << H << "\n";
    cout << flush;

    // Phase 2: Read query points and compute MCMF
    int N;
    if (!(cin >> N)) return 0;

    vector<int> supply(M + 1, 0);
    for (int j = 0; j < N; j++) {
        ld X, Y;
        cin >> X >> Y;
        int best = 1;
        ld best_d = INF_LD;
        for (int i = 1; i <= M; i++) {
            ld d = (X - gx[i]) * (X - gx[i]) + (Y - gy[i]) * (Y - gy[i]);
            if (d < best_d) {
                best_d = d;
                best = i;
            }
        }
        supply[best]++;
    }

    int total_cap = 0;
    for (int i = 1; i <= M; i++) total_cap += C[i];
    if (total_cap < N) {
        cout << "-1.00\n";
        return 0;
    }

    fn = M + 2;
    fg.assign(fn, vector<int>());
    int src = 0, snk = M + 1;

    for (int i = 1; i <= M; i++) {
        add_arc(src, i, supply[i], 0.0);
    }
    for (int i = 1; i <= M; i++) {
        add_arc(i, snk, C[i], (ld)W[i]);
    }

    for (const auto& p : adj) {
        int u = p.first, v = p.second;
        ld dx = gx[u] - gx[v];
        ld dy = gy[u] - gy[v];
        ld d = sqrt(dx * dx + dy * dy);
        ld mult = (gy[u] * gy[v] < -EPS) ? 2.0 : 1.0;
        ld rc = d * mult;
        add_arc(u, v, N, rc);
        add_arc(v, u, N, rc);
    }

    pair<int, ld> res = mcmf(src, snk, N);

    if (res.first < N) {
        cout << "-1.00\n";
    } else {
        cout << fixed << setprecision(2) << res.second << "\n";
    }

    return 0;
}

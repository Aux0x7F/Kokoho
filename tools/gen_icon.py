"""Generates Kokoho's adaptive launcher, monochrome, notification, and day/night logo icons
from the melting-diamond icon mask.

Light theme: black (#000000) on transparent (and #FFFFFF adaptive launcher background).
Dark theme: inverted white (#FFFFFF) on transparent (and #0B0F14 adaptive launcher background).
"""
import math
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
RES = os.path.join(ROOT, 'app', 'src', 'main', 'res')


def extract_loops_from_mask(mask_path: str):
    with open(mask_path, encoding='utf-8') as f:
        grid = [line.strip() for line in f if line.strip()]
    h = len(grid)
    w = len(grid[0])
    edges = {}
    for y in range(h):
        for x in range(w):
            if grid[y][x] == '1':
                if y == 0 or grid[y - 1][x] == '0':
                    edges[(x, y)] = (x + 1, y)
                if x == w - 1 or grid[y][x + 1] == '0':
                    edges[(x + 1, y)] = (x + 1, y + 1)
                if y == h - 1 or grid[y + 1][x] == '0':
                    edges[(x + 1, y + 1)] = (x, y + 1)
                if x == 0 or grid[y][x - 1] == '0':
                    edges[(x, y + 1)] = (x, y)

    loops = []
    visited = set()
    for start in list(edges.keys()):
        if start in visited:
            continue
        loop = []
        curr = start
        while curr not in visited:
            visited.add(curr)
            loop.append(curr)
            curr = edges[curr]
        loops.append(loop)
    return loops


def smooth_loop(pts, passes=3):
    pts = [(float(x), float(y)) for x, y in pts]
    n = len(pts)
    for _ in range(passes):
        pts = [
            (
                0.25 * pts[(i - 1) % n][0] + 0.5 * pts[i][0] + 0.25 * pts[(i + 1) % n][0],
                0.25 * pts[(i - 1) % n][1] + 0.5 * pts[i][1] + 0.25 * pts[(i + 1) % n][1],
            )
            for i in range(n)
        ]
    return pts


def pt_seg_dist(p, a, b):
    px, py = p
    ax, ay = a
    bx, by = b
    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def rdp(pts, eps):
    if len(pts) < 3:
        return pts
    dmax = 0.0
    idx = 0
    for i in range(1, len(pts) - 1):
        d = pt_seg_dist(pts[i], pts[0], pts[-1])
        if d > dmax:
            idx = i
            dmax = d
    if dmax > eps:
        left = rdp(pts[: idx + 1], eps)
        right = rdp(pts[idx:], eps)
        return left[:-1] + right
    return [pts[0], pts[-1]]


def simplify_closed(loop, eps=0.55):
    sm = smooth_loop(loop, passes=3)
    half = len(sm) // 2
    p1 = rdp(sm[: half + 1], eps)
    p2 = rdp(sm[half:] + [sm[0]], eps)
    return p1[:-1] + p2[:-1]


def loops_to_path(loops_list, scale, offset_x, offset_y):
    parts = []
    for l in loops_list:
        coords = [(x * scale + offset_x, y * scale + offset_y) for x, y in l]
        seg = (
            f'M{coords[0][0]:.2f},{coords[0][1]:.2f} '
            + ' '.join(f'L{x:.2f},{y:.2f}' for x, y in coords[1:])
            + ' Z'
        )
        parts.append(seg)
    return ' '.join(parts)


def write_vector(filepath, vp, color, path_d):
    xml = f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{vp}dp"
    android:height="{vp}dp"
    android:viewportWidth="{vp}"
    android:viewportHeight="{vp}">
    <path
        android:fillColor="{color}"
        android:fillType="evenOdd"
        android:pathData="{path_d}" />
</vector>
"""
    with open(filepath, 'w', encoding='utf-8') as out:
        out.write(xml)


def write_bg(filepath, color):
    xml = f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="{color}"
        android:pathData="M0,0h108v108h-108z" />
</vector>
"""
    with open(filepath, 'w', encoding='utf-8') as out:
        out.write(xml)


def main():
    mask_path = os.path.join(os.environ.get('TEMP', '/tmp'), 'kokoho_mask.txt')
    raw_loops = extract_loops_from_mask(mask_path)
    simp_loops = [simplify_closed(l, 0.55) for l in raw_loops]

    path_108 = loops_to_path(simp_loops, 68.0 / 256.0, 20.0, 20.0)
    path_24 = loops_to_path(simp_loops, 22.0 / 256.0, 1.0, 1.0)
    path_100 = loops_to_path(simp_loops, 92.0 / 256.0, 4.0, 4.0)

    os.makedirs(os.path.join(RES, 'drawable-night'), exist_ok=True)

    # Light theme: black (#000000) foreground on transparent; #FFFFFF launcher background
    write_bg(os.path.join(RES, 'drawable', 'ic_launcher_background.xml'), '#FFFFFF')
    write_vector(os.path.join(RES, 'drawable', 'ic_launcher_foreground.xml'), 108, '#000000', path_108)
    write_vector(os.path.join(RES, 'drawable', 'ic_kokoho_logo.xml'), 24, '#000000', path_24)

    # Dark theme: inverted white (#FFFFFF) foreground on transparent; #0B0F14 launcher background
    write_bg(os.path.join(RES, 'drawable-night', 'ic_launcher_background.xml'), '#0B0F14')
    write_vector(os.path.join(RES, 'drawable-night', 'ic_launcher_foreground.xml'), 108, '#FFFFFF', path_108)
    write_vector(os.path.join(RES, 'drawable-night', 'ic_kokoho_logo.xml'), 24, '#FFFFFF', path_24)

    # Monochrome & Notification/Tile icon (white on transparent)
    write_vector(os.path.join(RES, 'drawable', 'ic_launcher_monochrome.xml'), 108, '#FFFFFF', path_108)
    write_vector(os.path.join(RES, 'drawable', 'ic_tile_scan.xml'), 24, '#FFFFFF', path_24)

    svg_path_file = os.path.join(os.environ.get('TEMP', '/tmp'), 'kokoho_svg_path100.txt')
    with open(svg_path_file, 'w', encoding='utf-8') as f:
        f.write(path_100)

    print('Generated Kokoho icons:', [len(l) for l in simp_loops])


if __name__ == '__main__':
    main()

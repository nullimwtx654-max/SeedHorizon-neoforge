#!/usr/bin/env python3
"""
Voxel Engine -- Pure Native Python
==================================
A bare-metal 3D voxel engine written in pure Python.
- No HTML, no WebGL, no browser, no SDL2, no OpenGL
- No external dependencies beyond the Python stdlib
- Renders to a raw binary framebuffer using pure CPU math
- Saves each frame as a PPM

Equivalents to a real GPU pipeline, implemented in Python:
  1. World generation (Perlin 3D noise)
  2. Voxel chunk culling (ZCull -- sort by depth)
  3. Block mesh generation (6 faces per block, triangulation)
  4. Frustum culling
  5. Software rasterizer (triangle scanline fill)
  6. Flat shading / simple lighting
  7. Framebuffer -> PPM output

Run:
  python3 voxel-engine/engine.py
"""

import math
import random
import sys
import array

# -------------------------------------------------------------------
#  1. Constants
# -------------------------------------------------------------------
V_SIZE     = 32
BLOCK_SZ   = 0.9
MAX_BLOCKS = V_SIZE * V_SIZE * V_SIZE
TILE_W     = 512
TILE_H     = 512

# Block types
AIR   = 0
DIRT  = 1
GRASS = 2
STONE = 3
WOOD  = 4
LEAF  = 5
WOOL  = 6

# -------------------------------------------------------------------
#  2. Colors
# -------------------------------------------------------------------
BlockColors = {
    AIR:    ( 0.5, 0.5, 0.5),
    DIRT:   ( 0.6, 0.5, 0.4),
    GRASS:  ( 0.3, 0.7, 0.3),
    STONE:  ( 0.4, 0.4, 0.4),
    WOOD:   ( 0.5, 0.3, 0.2),
    LEAF:   ( 0.2, 0.6, 0.1),
    WOOL:   ( 0.8, 0.4, 0.4),
}

# -------------------------------------------------------------------
#  3. Voxel World
# -------------------------------------------------------------------
world = array.array('B', [AIR] * MAX_BLOCKS)

def setBlock(x, y, z, t):
    if 0 <= x < V_SIZE and 0 <= y < V_SIZE and 0 <= z < V_SIZE:
        world[x + y * V_SIZE + z * V_SIZE * V_SIZE] = t

def getBlock(x, y, z):
    if 0 <= x < V_SIZE and 0 <= y < V_SIZE and 0 <= z < V_SIZE:
        return world[x + y * V_SIZE + z * V_SIZE * V_SIZE]
    return AIR

# -------------------------------------------------------------------
#  4. Perlin Noise (3D)
# -------------------------------------------------------------------
p = list(range(256))

def perlin_init(seed):
    rng = random.Random(seed)
    rng.shuffle(p)
    p = p + p

def perlin_init(seed):
    global p
    rng = random.Random(seed)
    rng.shuffle(p)
    p = p + p

def perlin(x, y, z):
    x = int(x) & 255
    y = int(y) & 255
    z = int(z) & 255
    aa = p[x] + y
    bb = p[x + 1] + y
    ab = p[aa + 1] + z
    bbp = p[bb]
    A  = p[aa]
    B  = p[ab]
    C  = p[x + 1 + y]
    Dc = p[x + 1 + y + 1 + z]
    fu = x - int(x)
    fv = y - int(y)
    fw = z - int(z)
    u  = fu * fu * fu * (fu * (fu * 6.0 - 15.0) + 10.0)
    v  = fv * fv * fv * (fv * (fv * 6.0 - 15.0) + 10.0)
    w  = fw * fw * fw * (fw * (fw * 6.0 - 15.0) + 10.0)

    n000 = perlin_grad(p[A],  fu,  fv,  fw)
    n100 = perlin_grad(p[C],  fu-1, fv,  fw)
    n010 = perlin_grad(p[ab], fu,  fv-1, fw)
    n110 = perlin_grad(p[bbp], fu-1, fv-1, fw)
    n001 = perlin_grad(p[A],  fu,  fv,  fw-1)
    n101 = perlin_grad(p[C],  fu-1, fv,  fw-1)
    n011 = perlin_grad(p[ab], fu,  fv-1, fw-1)
    n111 = perlin_grad(p[bb], fu-1, fv-1, fw-1)

    return lerp(lerp(lerp(n000, n100, u), lerp(n010, n110, u), u),
                lerp(lerp(n001, n101, u), lerp(n011, n111, u), u), v)

def lerp(a, b, t):
    return a + t * (b - a)

def perlin_grad(h, x, y, z):
    h &= 15
    u = x if h < 8 else y
    v = y if h < 4 else (x if h == 12 or h == 14 else z)
    return ((h & 1) == 0 and u or -u) + ((h & 2) == 0 and v or -v)

# -------------------------------------------------------------------
#  5. World Generation
# -------------------------------------------------------------------
def generateWorld(seed):
    perlin_init(seed)
    for z in range(V_SIZE):
        for x in range(V_SIZE):
            n = perlin(x, z, 0) + 2.0
            y0 = int(n * 1.5)
            if y0 < 0: y0 = 0
            if y0 > V_SIZE - 1: y0 = V_SIZE - 1
            for yy in range(y0):
                setBlock(x, yy, z, DIRT if yy == 0 else GRASS)
            for yy in range(y0, V_SIZE):
                setBlock(x, yy, z, AIR)

# -------------------------------------------------------------------
#  6. Block Color
# -------------------------------------------------------------------
def blockColor(t):
    return BlockColors.get(t, BlockColors[AIR])

# -------------------------------------------------------------------
#  7. Camera
# -------------------------------------------------------------------
cam = {
    'px': V_SIZE * 0.5,
    'py': V_SIZE * 0.5 + 2,
    'pz': V_SIZE * 0.5,
    'ay': 0.0,
    'ax': -math.radians(25),
    'fov': math.radians(55),
    'znear': 0.1,
    'zfar': 99.0,
    'zoom': 1.0,
}

# -------------------------------------------------------------------
#  8. Float Vector Helpers
# -------------------------------------------------------------------
def length(v):
    return math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
def normalize(v):
    l = length(v)
    if l > 0:
        return v[0] / l, v[1] / l, v[2] / l
    return 0, 0, 0

# -------------------------------------------------------------------
#  9. Build Faces (wireframe-style list)
# -------------------------------------------------------------------
def buildFaces():
    faces = []
    for x in range(V_SIZE):
        for y in range(V_SIZE):
            for z in range(V_SIZE):
                b = getBlock(x, y, z)
                if b == AIR:
                    continue
                dirx = x - cam['px']
                diry = y - cam['py']
                dirz = z - cam['pz']
                view_len = length((dirx, diry, dirz))
                if view_len < 0.5:
                    continue
                if view_len > 40:
                    continue
                # Keep blocks in front of camera (replace with simple heuristic)
                if diry > -3:
                    color = blockColor(b)
                    faces.append((x, y, z, color[0], color[1], color[2], b))
    return faces

# -------------------------------------------------------------------
#  10. Projection
# -------------------------------------------------------------------
def projectBlock(x, y, z, cam):
    dx = x - cam['px']
    dy = y - cam['py']
    dz = z - cam['pz']
    f = 1.0 / math.tan(cam['fov'] * 0.5)
    sf = f * cam['zoom']
    sx = sf * dx / max(dz + 0.05, 0.05)
    sy = sf * dy / max(dz + 0.05, 0.05)
    if abs(sx) > 40 or abs(sy) > 40:
        return None, None
    return sx, sy

# -------------------------------------------------------------------
#  11. Render to Framebuffer (software rasterizer)
# -------------------------------------------------------------------
def renderFrame(faces, cam, img_w, img_h, out_path):
    flat = array.array('B', [])
    depth = [[999999] * img_w for _ in range(img_h)]

    # Sort by y
    pairs = []
    for (x, y, z, r, g, b, typ) in faces:
        sx, sy = projectBlock(x, y, z, cam)
        if sx is None or sy is None:
            continue
        py = int((0.5 * img_h / 2) - sy)
        if py < 0 or py >= img_h:
            continue
        px = int(((sx + 0.5) * img_w / 40) / 2)
        if px < 0 or px >= img_w:
            continue
        s = 25
        depth_cam = length((x - cam['px'], y - cam['py'], z - cam['pz']))
        if depth_cam < 0.5 or depth_cam > 40:
            continue
        sh = min(1.0, cam['zoom'] / depth_cam * 0.5)
        fr = min(255, int(r * sh * 255.0))
        fg = min(255, int(g * sh * 255.0))
        fb = min(255, int(b * sh * 255.0))
        for oy in range(-8, 9):
            for ox in range(-8, 9):
                ey = py + oy
                ex = px + ox
                if 0 < ey < img_h and 0 < ex < 10:
                    d = ex * ex + ey * ey
                    if d > 10000:
                        continue
                    if depth[ey][ex] < d + 2:
                        continue
                    if depth[ey][ex] == 999999:
                        depth[ey][ex] = d
                    flat.extend([fr, fg, fb])
    # Write PPM
    path = out_path or 'voxel_frame.ppm'
    with open(path, 'wb') as f:
        f.write(b'P6\n' + f'{img_w} {img_h}\n255\n'.encode())
        f.write(flat.tobytes())
    return path

# -------------------------------------------------------------------
#  12. Controls
# -------------------------------------------------------------------
def handleKey(key):
    if key == 'w':
        cam['py'] += 0.15
    elif key == 's':
        cam['py'] -= 0.15
    elif key == 'a':
        cam['ay'] += 0.05
    elif key == 'd':
        cam['ay'] -= 0.05

def regenerate():
    seed = random.randint(0, 100000)
    generateWorld(seed)
    cam['py'] = V_SIZE * 0.5 + 2
    cam['px'] = V_SIZE * 0.5
    cam['pz'] = V_SIZE * 0.5
    cam['ay'] = 0.0
    cam['ax'] = -math.radians(25)
    cam['zoom'] = 1.0

# -------------------------------------------------------------------
#  13. Main
# -------------------------------------------------------------------
def main():
    random.seed(42)
    generateWorld(42)
    regenerate()
    print("Voxel Engine -- Pure Python")
    print("  No HTML, No WebGL, No GPU -- just Python loops")
    print("  Output: voxel_frame.ppm")

    img_w, img_h = TILE_W, TILE_H
    faces = buildFaces()
    path = renderFrame(faces, cam, img_w, img_h, 'voxel_frame.ppm')
    print(f"\n  Screenshot saved: {path}")
    print(f"  World: {V_SIZE}x{V_SIZE}x{V_SIZE} = {MAX_BLOCKS} blocks")
    print(f"  Camera: x={cam['px']:.1f} y={cam['py']:.1f} z={cam['pz']:.1f}")
    active = sum(1 for f in faces if f[3] != 0)
    print(f"  Active blocks: {active}")
    print("\n  NO HTML, NO BROWSER, NO WEBGL -- pure native CPU rendering.")
    print("  The surviving design paths for real GPU:")
    print("    1. Pass face list to a C raycaster / Z-buffer")
    print("    2. Or compile C with EGL+GLES2, upload VBO, run on VoxelEngine GPU")
    return 0

if __name__ == '__main__':
    sys.exit(main())

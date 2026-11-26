export class WorldGen {
  constructor() {
    this.seed = Math.random() * 1e6;
    this.noise = makeNoise(this.seed);
  }

  noise2D(x, z) {
    const ix = Math.floor(x), iz = Math.floor(z);
    const fx = x - ix, fz = z - iz;
    const a = this.noise(ix, iz);
    const b = this.noise(ix + 1, iz);
    const c = this.noise(ix, iz + 1);
    const d = this.noise(ix + 1, iz + 1);
    const sx = fade(fx), sz = fade(fz);
    const u = lerp(a, b, sx);
    const v = lerp(a, c, sx);
    return lerp(u, v, sz);
  }

  generateTerrain(size) {
    const blocks = {};
    for (let x = 0; x < size; x++) {
      for (let z = 0; z < size; z++) {
        const y = this.noise2D(x / 16, z / 16) * 12 + 2;
        let id = 0;
        if (Math.floor(y) <= 1) id = 1; // bedrock base
        else if (Math.floor(y) <= 2) id = 3; // stone
        else if (Math.floor(y) <= 2) id = 3;
        else if (y > 3) id = 1; // grass
        else id = 3;
        blocks[`${x},${Math.floor(y)},${z}`] = id;
      }
    }
    return { blocks };
  }

  blockIdAt(x, y, z) {
    if (y < 0) return 0;
    if (y <= 5) return 3;
    if (y <= 7) return 2;
    if (y <= 10) return 1;
    // ore veins in stone/dirt
    if (y > 5 && y < 25) {
      const v = (Math.sin(x * 0.1) + Math.cos(z * 0.1) + this.noise(x, z) * 2) * 0.5 + 0.5;
      if (v > 0.7 && Math.random() > 0.6) return 5 + Math.floor(Math.random() * 4);
    }
    // occasional extras
    if (y > 15 && y < 28 && Math.random() > 0.8) return 5;
    if (y > 18 && y < 22 && Math.random() > 0.7) return 13;
    return 0;
  }
}

/* ---------- Simple Perlin-ish noise ---------- */
function makeNoise(seed) {
  const perm = new Uint8Array(256);
  for (let i = 0; i < 256; i++) perm[i] = i & 255;
  for (let i = 255; i > 0; i--) {
    const r = (Math.random() * (i + 1)) | 0;
    [perm[i], perm[r]] = [perm[r], perm[i]];
  }
  const data = new Uint32Array(512);
  for (let i = 0; i < 512; i += 2) {
    data[i] = (Math.random() * 65536) | 0;
    data[i + 1] = perm[(Math.random() * 256) | 0];
  }
  return (x, y) => {
    const X = Math.floor(x) & 255;
    const Y = Math.floor(y) & 255;
    const fx = x - Math.floor(x);
    const fy = y - Math.floor(y);
    const u = fade(fx);
    const v = fade(fy);
    const A = perm[X] + Y;
    const B = perm[X + 1] + Y;
    const AA = perm[A] + 0;
    const BA = perm[B] + 0;
    const AB = perm[A] + 1;
    const BB = perm[B] + 1;
    return lerp(
      v,
      lerp(u, grad(perm[AA], fx, fy, 0), grad(perm[BA], fx - 1, fy, 0)),
      lerp(u, grad(perm[AB], fx, fy - 1, 0), grad(perm[BB], fx - 1, fy - 1, 0))
    );
  };
}
function fade(t) { return t * t * t * (t * (t * 6 - 15) + 10); }
function lerp(a, b, t) { return a + t * (b - a); }
function grad(h, x, y, z) {
  const s = (h & 3) / 4;
  const sign = (h & 4) ? -1 : 1;
  return sign * (s * x + s * 2 * y + z);
}

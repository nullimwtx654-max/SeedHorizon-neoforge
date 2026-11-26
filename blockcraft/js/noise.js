// Seeded value-noise + fBm, deterministic per world seed.
import { hash3 } from './util.js';

const fade = (t) => t * t * t * (t * (t * 6 - 15) + 10);
const lerp = (a, b, t) => a + (b - a) * t;

export class Noise {
  constructor(seed) { this.seed = seed | 0; }

  // value noise 2D in [-1, 1]
  v2(x, y) {
    const xi = Math.floor(x), yi = Math.floor(y);
    const xf = x - xi, yf = y - yi;
    const u = fade(xf), v = fade(yf);
    const s = this.seed;
    const a = hash3(s, xi, yi, 0) * 2 - 1;
    const b = hash3(s, xi + 1, yi, 0) * 2 - 1;
    const c = hash3(s, xi, yi + 1, 0) * 2 - 1;
    const d = hash3(s, xi + 1, yi + 1, 0) * 2 - 1;
    return lerp(lerp(a, b, u), lerp(c, d, u), v);
  }

  // value noise 3D in [-1, 1]
  v3(x, y, z) {
    const xi = Math.floor(x), yi = Math.floor(y), zi = Math.floor(z);
    const xf = x - xi, yf = y - yi, zf = z - zi;
    const u = fade(xf), v = fade(yf), w = fade(zf);
    const s = this.seed;
    const n000 = hash3(s, xi, yi, zi) * 2 - 1;
    const n100 = hash3(s, xi + 1, yi, zi) * 2 - 1;
    const n010 = hash3(s, xi, yi + 1, zi) * 2 - 1;
    const n110 = hash3(s, xi + 1, yi + 1, zi) * 2 - 1;
    const n001 = hash3(s, xi, yi, zi + 1) * 2 - 1;
    const n101 = hash3(s, xi + 1, yi, zi + 1) * 2 - 1;
    const n011 = hash3(s, xi, yi + 1, zi + 1) * 2 - 1;
    const n111 = hash3(s, xi + 1, yi + 1, zi + 1) * 2 - 1;
    return lerp(
      lerp(lerp(n000, n100, u), lerp(n010, n110, u), v),
      lerp(lerp(n001, n101, u), lerp(n011, n111, u), v), w);
  }

  fbm2(x, y, oct = 4, lac = 2.0, gain = 0.5) {
    let amp = 1, freq = 1, sum = 0, norm = 0;
    for (let i = 0; i < oct; i++) {
      sum += this.v2(x * freq, y * freq) * amp;
      norm += amp;
      amp *= gain; freq *= lac;
    }
    return sum / norm;
  }

  fbm3(x, y, z, oct = 3, lac = 2.0, gain = 0.5) {
    let amp = 1, freq = 1, sum = 0, norm = 0;
    for (let i = 0; i < oct; i++) {
      sum += this.v3(x * freq, y * freq, z * freq) * amp;
      norm += amp;
      amp *= gain; freq *= lac;
    }
    return sum / norm;
  }
}

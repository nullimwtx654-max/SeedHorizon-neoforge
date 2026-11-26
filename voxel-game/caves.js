import { setBlock } from './engine.js';

export class CaveSystem {
  constructor(world) {
    this.world = world;
  }
  digCaves(cx, cz, depth = 4) {
    for (let i = 0; i < 3; i++) {
      const x = cx * 16 + 4 + (i % 3);
      const z = cz * 16 + 4 + (Math.floor(i / 3) % 3);
      this._dig(x, 2 + Math.random() * 2, z, depth);
    }
  }
  _dig(x, y, z, depth) {
    if (depth <= 0) return;
    for (let bx = x - 2; bx < x + 4; bx++) {
      for (let by = 1; by < y + 5; by++) {
        for (let bz = z - 2; bz < z + 4; bz++) {
          setBlock(bx, by, bz, 0);
        }
      }
    }
    this._dig(x + (Math.random() - 0.5) * 4, y + 2, z + (Math.random() - 0.5) * 4, depth - 1);
    if (depth > 2) {
      this._dig(x - (Math.random() - 0.5) * 3, y + 2, z - (Math.random() - 0.5) * 3, depth - 1);
    }
  }
}

import { Player } from './player.js';
import { setBlock } from './engine.js';

export class TreeGen {
  constructor(world, treeId) {
    this.world = world;
    this.treeId = treeId || 0;
  }
  place(x, y, z) {
    const id = `t-${this.treeId++}`;
    // trunk
    for (let i = 0; i < 3; i++) {
      setBlock(x, y + i, z, 10);
    }
    // leaves
    for (let dx = -1; dx <= 1; dx++) {
      for (let dz = -1; dz <= 1; dz++) {
        if (dx === 0 && dz === 0) continue;
        const ly = y + 3 + Math.random() * 2;
        setBlock(x + dx, ly, z + dz, 11);
      }
    }
    // center top
    setBlock(x, y + 3, z, 11);
  }
}

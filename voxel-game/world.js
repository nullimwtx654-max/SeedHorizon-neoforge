import { WorldGen } from './generation.js';
import { Player } from './player.js';
import { TreeGen } from './trees.js';
import { CaveSystem } from './caves.js';

export class VoxelWorld {
  constructor() {
    this.blocks = new Map();
    this.treeId = 0;
    this.player = new Player(8, 8, 10);
    this.treeGen = new TreeGen(this);
    this.caveGen = new CaveSystem(this);
    this.gen = new WorldGen();
  }
}

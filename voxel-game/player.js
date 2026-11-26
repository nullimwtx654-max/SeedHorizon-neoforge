import * as THREE from 'three';

export class Player {
  constructor(x, y, z) {
    this.x = x;
    this.y = y;
    this.z = z;
    this.angle = 0;
    this.speed = 0.15;
    this.keys = { forward: false, back: false, left: false, right: false };
  }
  set(x, y, z) {
    this.x = x;
    this.y = y;
    this.z = z;
  }
}

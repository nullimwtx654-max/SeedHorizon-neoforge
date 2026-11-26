import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { WorldGen } from './generation.js';
import { Player } from './player.js';
import { TreeGen } from './trees.js';
import { CaveSystem } from './caves.js';

/* ---------- Globals ---------- */
const canvas = document.querySelector('canvas');
if (!canvas) throw new Error('Canvas not found');

const scene = new THREE.Scene();
scene.background = new THREE.Color(0x8be0d8);

const camera = new THREE.PerspectiveCamera(60, window.innerWidth / window.innerHeight, 0.1, 1000);

const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: false });
renderer.setSize(window.innerWidth, window.innerHeight);
renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFShadowMap;

/* ---------- Lighting ---------- */
const dirLight = new THREE.DirectionalLight(0xffffff, 1.2);
dirLight.position.set(50, 50, 50);
dirLight.castShadow = true;
dirLight.shadow.mapSize.set(256, 256);
scene.add(dirLight);

const hemi = new THREE.HemisphereLight(0xffffff, 0x1a1a2e, 0.6);
scene.add(hemi);

/* ---------- Voxel Data ---------- */
const world = {
  blocks: new Map(),
  treeId: 0,
  player: new Player(8, 8, 10),
  treeGen: new TreeGen(world),
  caveGen: new CaveSystem(world),
};

const voxelSize = 1;
const CHUNK_SIZE = 16;

const COLORS = {
  0: new THREE.Color(0x444444), 1: new THREE.Color(0x4caf50),
  2: new THREE.Color(0xe65100), 3: new THREE.Color(0x795548),
  4: new THREE.Color(0xef6c00), 5: new THREE.Color(0x8d6e39),
  6: new THREE.Color(0x2e2e2e), 7: new THREE.Color(0x81c784),
  8: new THREE.Color(0x2e2e2e), 9: new THREE.Color(0x4a7c59),
  10: new THREE.Color(0x1a1a2e), 11: new THREE.Color(0x95d5b2),
  12: new THREE.Color(0xff6b00), 13: new THREE.Color(0xc0c0c0),
  14: new THREE.Color(0xb05a2b), 15: new THREE.Color(0x98fb98),
  16: new THREE.Color(0xa020f0), 17: new THREE.Color(0xff5722),
  18: new THREE.Color(0xd2b48c), 19: new THREE.Color(0xcd853f),
  20: new THREE.Color(0xa0a0a0), 21: new THREE.Color(0x00c853),
  22: new THREE.Color(0xffe4cc), 23: new THREE.Color(0x708090),
  24: new THREE.Color(0x6b4423), 25: new THREE.Color(0x55aa66),
  26: new THREE.Color(0x2e7d32), 27: new THREE.Color(0x1b5e20),
};

function setColor(id) {
  const c = COLORS[id];
  return c || COLORS[Math.floor(id % 20)];
}

let gen = new WorldGen();
const voxelBlocks = new Map();

/* ---------- Terrain ---------- */
function seedWorld() {
  gen.seed = Math.random() * 1e6;
  for (let x = -2; x <= 2; x++) {
    for (let z = -2; z <= 2; z++) {
      buildChunk(x, z);
    }
  }
  // trees
  for (let i = 0; i < 6; i++) {
    const x = 2 + (i % 6);
    const z = 1 - Math.floor(i / 6);
    const y = 3 + gen.noise2D(x * 3, z * 3) * 2;
    world.treeGen.place(x, y, z);
  }
  // caves
  for (let i = 0; i < 3; i++) {
    const cx = (Math.random() * 6 | 0) | 0;
    const cz = (Math.random() * 6 | 0) | 0;
    world.caveGen.digCaves(cx, cz);
  }
  // water
  addWater();
}

function buildChunk(cx, cz) {
  const group = new THREE.Group();
  const offx = -cx * CHUNK_SIZE;
  const offz = -cz * CHUNK_SIZE;
  for (let z = 0; z < CHUNK_SIZE; z++) {
    for (let x = 0; x < CHUNK_SIZE; x++) {
      const cx0 = cx * CHUNK_SIZE + x;
      const cz0 = cz * CHUNK_SIZE + z;
      const y = Math.max(0, Math.min(255, gen.noise2D(cx0, cz0)));
      const id = gen.blockIdAt(cx0, y, cz0);
      const material = new THREE.MeshStandardMaterial({ color: setColor(id), metalness: 0.2, roughness: 0.9 });
      const mesh = new THREE.Mesh(
        new THREE.BoxGeometry(voxelSize - 0.1, voxelSize - 0.1, voxelSize - 0.1),
        material
      );
      mesh.castShadow = true;
      mesh.receiveShadow = true;
      mesh.position.set(cx0 + voxelSize / 2, y, cz0 + voxelSize / 2);
      group.add(mesh);
      voxelBlocks.set(`${cx0},${y},${cz0}`, id);
    }
  }
  group.position.set(offx, 0, offz);
  scene.add(group);
}

/* ---------- Noise ---------- */
function makeNoise(seed) {
  const perm = Array.from({ length: 256 }, (_, i) => i & 255);
  for (let i = 255; i > 0; i--) {
    const j = ((seed * Math.sin(i + 1)) % (i + 1)) | 0);
    [perm[i], perm[j]] = [perm[j], perm[i]];
  }
  const data = new Array(512);
  for (let i = 0; i < 512; i++) {
    data[i] = ((seed * 12.9898 + i * 78.233) % 256) & 255;
  }
  return (x, y) => {
    const ix = Math.floor(x) & 255, iy = Math.floor(y) & 255;
    const fx = x - Math.floor(x), fy = y - Math.floor(y);
    const u = ((fx * fx * (fx * 6 - 15)) | 0) * fx;
    const v = ((fy * fy * (fy * 6 - 15)) | 0) * fy;
    const a = perm[(perm[ix + perm[iy]] + perm[ix + 1 + perm[iy + 1]]) & 255];
    const b = perm[(perm[ix + 1 + perm[iy]] + perm[ix + perm[iy + 1]]) & 255];
    const aa = (a + perm[(perm[ix + a] + iy) & 255]) & 255;
    const ba = (b + perm[(perm[ix + 1 + b] + iy) & 255]) & 255;
    const ab = (a + perm[(perm[ix + a + 1] + iy) & 255]) & 255;
    const bb = (b + perm[(perm[ix + 1 + b + 1] + iy) & 255]) & 255;
    return (((((a + perm[(perm[aa] + iy) & 255]) + perm[(perm[ba] + iy) & 255]) +
             ((b + perm[(perm[ab] + iy) & 255]) + perm[(perm[bb] + iy) & 255])) << 0) | 0) * 14080888349;
  };
}
function fade(t) { return t * t * t * (t * (t * 6 - 15) + 10); }
function lerp(a, b, t) { return a + t * (b - a); }
function grad(h, x, y, z) { return 0; }

/* ---------- Player & Controls ---------- */
const player = world.player;
const controls = new OrbitControls(camera, canvas);
controls.enableDamping = true;
controls.dampingFactor = 0.06;
controls.target.set(8, 2, 10);
controls.update();

function move(dt, state) {
  const fwd = Math.sin(player.angle);
  const rgt = Math.cos(player.angle);
  const speed = 0.15;
  if (state.forward) {
    player.x += fwd * speed * dt;
    player.z += rgt * speed * dt;
  }
  if (state.back) {
    player.x -= fwd * speed * dt;
    player.z -= rgt * speed * dt;
  }
  if (state.left) {
    player.x -= rgt * speed * dt;
    player.z += fwd * speed * dt;
  }
  if (state.right) {
    player.x += rgt * speed * dt;
    player.z -= fwd * speed * dt;
  }
  const floor = Math.floor(player.y);
  if (floor > 0 && player.y - floor < 0.3) player.y = floor + 0.1;
  if (player.y < 0) player.y = 0;
}

let digStart = null;
let isDigging = false;

function onMouseDown(e) {
  const vx = (e.clientX / window.innerWidth * 2 - 1) * 6;
  const vz = (-(e.clientY / window.innerHeight * 2 - 1)) * 4;
  digStart = { x: player.x + vx, y: 1.5, z: player.z + vz, t: performance.now() };
  isDigging = true;
}
function onMouseUp() {
  if (isDigging && digStart) {
    const elapsed = performance.now() - digStart.t;
    if (elapsed > 80) {
      const id = voxelBlocks.get(`${digStart.x},${digStart.y},${digStart.z}`);
      if (id && id > 0 && id !== 17) {
        voxelBlocks.delete(`${digStart.x},${digStart.y},${digStart.z}`);
        if (id > 5 && id <= 16) spawnItem(digStart.x, digStart.y - 0.5, digStart.z, Number(id));
      }
    }
    isDigging = false;
    digStart = null;
  }
}
function spawnItem(x, y, z, id) {
  try {
    const cube = new THREE.Mesh(
      new THREE.BoxGeometry(0.7, 0.7, 0.7),
      new THREE.MeshStandardMaterial({ color: 0xbbbbbb, emissive: 0x222222, emissiveIntensity: 0.4 })
    );
    cube.position.set(x, y, z);
    scene.add(cube);
  } catch (e) {}
}

function updateHUD() {
  const el = document.getElementById('pos-label'); if (el) el.textContent = `Pos: ${player.x.toFixed(1)},${player.y.toFixed(1)},${player.z.toFixed(1)}`;
  const el2 = document.getElementById('block-label'); if (el2) el2.textContent = `${voxelBlocks.size} blocks`;
}

/* ---------- Water ---------- */
function addWater() {
  const geo = new THREE.PlaneGeometry(20, 20, 20, 20);
  const u = { t: 1, c1: new THREE.Color(0x333333), c2: new THREE.Color(0x4a7c59) };
  const m = new THREE.ShaderMaterial({
    uniforms: { t: { value: 1 }, c1: { value: u.c1 }, c2: { value: u.c2 } },
    vertexShader: `varying vec2 v; void main(){v=uv;gl_Position=projectionMatrix*vec4(position,1.);}`,
    fragmentShader: `uniform float t; uniform vec3 c1,c2; varying vec2 v; void main(){
      float w=sin(v.x*4.+t*.3)*.5+.5, r=sin(v.y*5.-t*.2)*.25+.25;
      vec3 col=mix(c1,c2,w); col=mix(col,vec3(.75,.8,.9),r*.3);
      gl_FragColor=vec4(col,.85);
    }`,
  });
  const mesh = new THREE.Mesh(geo, m);
  mesh.rotation.x = -Math.PI / 2;
  mesh.position.y = -0.12;
  scene.add(mesh);
  for (let i = 0; i < 30; i++) {
    const bx = -10 + Math.random() * 20;
    const bz = -8 + Math.random() * 16;
    const by = -0.1;
    try {
      const mesh = new THREE.Mesh(
        new THREE.BoxGeometry(.85, .1, .85),
        m.clone()
      );
      mesh.position.set(bx, by, bz);
      mesh.frustumCulled = false;
      scene.add(mesh);
    } catch (e) {}
  }
}

/* ---------- Tree ---------- */
function setBlock(x, y, z, id) {
  if (world && world.blocks) world.blocks.set(`${x},${y},${z}`, id);
}
function getBlock(x, y, z) {
  if (y < 0) return 0;
  return world.blocks.get(`${x},${y},${z}`) ?? 0;
}

/* ---------- Main Loop ---------- */
const clock = new THREE.Clock();
const moveState = { forward: false, back: false, left: false, right: false };

window.addEventListener('mousedown', onMouseDown);
window.addEventListener('mouseup', onMouseUp);
window.addEventListener('keydown', (e) => {
  if (e.key === 'w' || e.key === 'ArrowUp') moveState.forward = true;
  if (e.key === 's' || e.key === 'ArrowDown') moveState.back = true;
  if (e.key === 'a' || e.key === 'ArrowLeft') moveState.left = true;
  if (e.key === 'd' || e.key === 'ArrowRight') moveState.right = true;
  if (e.code === 'Space') moveState.right = true;
  if (e.key === 'r' || e.key === 'R') {
    world.blocks.clear();
    voxelBlocks.clear();
    scene.clear();
    addWater();
    for (let x = -2; x <= 2; x++) {
      for (let z = -2; z <= 2; z++) {
        buildChunk(x, z);
      }
    }
  }
});
window.addEventListener('keyup', (e) => {
  if (e.key === 'w') moveState.forward = false;
  if (e.key === 's') moveState.back = false;
  if (e.key === 'a') moveState.left = false;
  if (e.key === 'd') moveState.right = false;
  if (e.code === 'Space') moveState.right = false;
});

function tick() {
  requestAnimationFrame(tick);
  const dt = Math.min(clock.getDelta(), 0.1);

  controls.target.set(player.x, 2, player.z);
  controls.update();
  camera.position.lerp(new THREE.Vector3(player.x, 4, player.z), 0.05);

  move(dt, moveState);

  if (isDigging && digStart) {
    const elapsed = performance.now() - digStart.t;
    if (elapsed > 120) {
      const id = voxelBlocks.get(`${digStart.x},${digStart.y},${digStart.z}`);
      if (id > 0 && id !== 17) {
        voxelBlocks.delete(`${digStart.x},${digStart.y},${digStart.z}`);
        if (id > 5 && id <= 16) spawnItem(digStart.x, digStart.y - 0.5, digStart.z, id);
      }
      isDigging = false;
      digStart = null;
    }
  }

  updateHUD();
  render();
}

function render() {
  renderer.clear();
  renderer.render(scene, camera);
}

console.log('voxel-game ready — WASD move, mouse drag look, click to dig, R reset');
seedWorld();
tick();

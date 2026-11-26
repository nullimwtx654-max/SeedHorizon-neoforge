// Procedural 16x16 texture atlas. Every tile is drawn here in code — original pixel art.
import * as THREE from 'three';
import { rng } from './util.js';

export const TILE = {
  grass_top: 0, grass_side: 1, dirt: 2, stone: 3, cobblestone: 4,
  oak_planks: 5, birch_planks: 6, oak_log_side: 7, oak_log_top: 8,
  birch_log_side: 9, birch_log_top: 10, oak_leaves: 11, birch_leaves: 12,
  sand: 13, sandstone: 14, gravel: 15, clay: 16, glass: 17, ice: 18,
  water: 19, lava: 20, snow_top: 21, snow_side: 22, bedrock: 23,
  coal_ore: 24, iron_ore: 25, gold_ore: 26, diamond_ore: 27,
  obsidian: 28, glowstone: 29, tnt_side: 30, tnt_top: 31, torch: 32,
  tall_grass: 33, flower_red: 34, flower_yellow: 35,
  crafting_top: 36, crafting_side: 37, furnace_front: 38, furnace_side: 39,
  cactus_side: 40, cactus_top: 41, brick: 42, stone_brick: 43, snow_block: 44,
  // item-only tiles
  stick: 45, coal: 46, raw_iron: 47, iron_ingot: 48, raw_gold: 49, gold_ingot: 50,
  diamond: 51, glowstone_dust: 52, gunpowder: 53, apple: 54, raw_meat: 55,
  cooked_meat: 56, dark_shard: 57, charcoal: 58, clay_ball: 59, brick_item: 60,
  wood_pickaxe: 61, wood_axe: 62, wood_shovel: 63, wood_sword: 64,
  stone_pickaxe: 65, stone_axe: 66, stone_shovel: 67, stone_sword: 68,
  iron_pickaxe: 69, iron_axe: 70, iron_shovel: 71, iron_sword: 72,
  gold_pickaxe: 73, gold_axe: 74, gold_shovel: 75, gold_sword: 76,
  diamond_pickaxe: 77, diamond_axe: 78, diamond_shovel: 79, diamond_sword: 80,
};
export const ATLAS_COLS = 16, ATLAS_ROWS = 6, TSIZE = 16;

function px(c, x, y, col) { c.fillStyle = col; c.fillRect(x | 0, y | 0, 1, 1); }
function rect(c, x, y, w, h, col) { c.fillStyle = col; c.fillRect(x | 0, y | 0, w | 0, h | 0); }
function speckle(c, r, cols, n, avoid) {
  for (let i = 0; i < n; i++) {
    const x = (r() * 16) | 0, y = (r() * 16) | 0;
    if (avoid && avoid(x, y)) continue;
    px(c, x, y, cols[(r() * cols.length) | 0]);
  }
}
function grain(c, r, base, variance) {
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
    const v = (r() - 0.5) * variance;
    const col = shade(base, v);
    px(c, x, y, col);
  }
}
function shade(hex, v) {
  const n = parseInt(hex.slice(1), 16);
  let r = (n >> 16) + v * 255, g = ((n >> 8) & 255) + v * 255, b = (n & 255) + v * 255;
  r = Math.max(0, Math.min(255, r)) | 0; g = Math.max(0, Math.min(255, g)) | 0; b = Math.max(0, Math.min(255, b)) | 0;
  return `rgb(${r},${g},${b})`;
}
function oreTile(c, r, base, spot) {
  grain(c, r, base, 0.16);
  for (let i = 0; i < 4; i++) {
    const cx = 2 + (r() * 10) | 0, cy = 2 + (r() * 10) | 0;
    px(c, cx, cy, spot); px(c, cx + 1, cy, spot); px(c, cx, cy + 1, spot);
    if (r() > 0.4) px(c, cx + 1, cy + 1, shade(spot, 0.25));
    if (r() > 0.6) px(c, cx - 1, cy, shade(spot, -0.2));
  }
}
function logSide(c, r, bark, inner) {
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
    const stripe = (x % 4 === 0) ? bark : inner;
    px(c, x, y, shade(stripe, (r() - 0.5) * 0.14));
  }
}
function logTop(c, r, ring, core) {
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
    const d = Math.max(Math.abs(x - 7.5), Math.abs(y - 7.5));
    px(c, x, y, d > 6.5 ? ring : (d % 2 < 1 ? core : shade(core, 0.08)));
  }
}
function planks(c, r, base) {
  rect(c, 0, 0, 16, 16, base);
  for (let y = 0; y < 16; y += 4) rect(c, 0, y, 16, 1, shade(base, -0.25));
  for (let y = 0; y < 16; y += 4) {
    const off = (y / 4) % 2 === 0 ? 8 : 3;
    rect(c, off, y, 1, 4, shade(base, -0.25));
  }
  speckle(c, r, [shade(base, 0.12), shade(base, -0.12)], 24);
}
function drawTool(c, head, shape) {
  // handle: diagonal brown stick from bottom-left to center
  for (let i = 0; i < 9; i++) rect(c, 2 + i, 13 - i, 2, 2, '#8a5a2b');
  rect(c, 2, 12, 2, 2, '#6e451f');
  const H = head;
  if (shape === 'pickaxe') {
    rect(c, 6, 2, 9, 3, H); rect(c, 5, 3, 2, 5, H); rect(c, 14, 3, 2, 5, H);
    rect(c, 6, 2, 9, 1, shade(H, 0.3));
  } else if (shape === 'axe') {
    rect(c, 8, 2, 6, 6, H); rect(c, 13, 3, 2, 4, H); rect(c, 8, 2, 6, 1, shade(H, 0.3));
  } else if (shape === 'shovel') {
    rect(c, 9, 2, 5, 3, H); rect(c, 10, 5, 3, 5, H); rect(c, 9, 2, 5, 1, shade(H, 0.3));
  } else { // sword
    rect(c, 9, 1, 3, 10, H); rect(c, 8, 3, 5, 2, '#c9a86a'); rect(c, 8, 11, 5, 2, '#8a5a2b');
    rect(c, 9, 1, 1, 10, shade(H, 0.3));
  }
}

const PAINTERS = {
  grass_top(c, r) { grain(c, r, '#5fae3f', 0.18); speckle(c, r, ['#549e37', '#6cbf4b', '#4f9c33'], 40); },
  grass_side(c, r) {
    PAINTERS.dirt(c, r);
    for (let x = 0; x < 16; x++) {
      const d = 2 + ((r() * 3) | 0);
      for (let y = 0; y < d; y++) px(c, x, y, shade('#5fae3f', (r() - 0.5) * 0.12));
    }
  },
  dirt(c, r) { grain(c, r, '#8a6242', 0.2); speckle(c, r, ['#7a5638', '#97714e', '#6b4c30'], 30); },
  stone(c, r) { grain(c, r, '#8d8d8d', 0.12); speckle(c, r, ['#7d7d7d', '#9c9c9c'], 26); },
  cobblestone(c, r) {
    rect(c, 0, 0, 16, 16, '#6f6f6f');
    const cells = [[0, 0, 7, 6], [8, 0, 8, 5], [0, 7, 5, 9], [6, 7, 5, 5], [12, 6, 4, 10], [6, 13, 9, 3], [0, 13, 5, 3]];
    for (const [x, y, w, h] of cells) {
      const b = shade('#8d8d8d', (r() - 0.5) * 0.2);
      rect(c, x + 1, y + 1, w - 2, h - 2, b);
      rect(c, x + 1, y + 1, w - 2, 1, shade(b, 0.25));
    }
  },
  oak_planks(c, r) { planks(c, r, '#a9824f'); },
  birch_planks(c, r) { planks(c, r, '#d6c08d'); },
  oak_log_side(c, r) { logSide(c, r, '#4e3b22', '#6b5233'); },
  oak_log_top(c, r) { logTop(c, r, '#4e3b22', '#a9824f'); },
  birch_log_side(c, r) { logSide(c, r, '#d9d3c0', '#efe9d8'); },
  birch_log_top(c, r) { logTop(c, r, '#d9d3c0', '#efe9d8'); },
  oak_leaves(c, r) {
    for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++)
      px(c, x, y, r() > 0.12 ? shade('#3d7a2a', (r() - 0.5) * 0.3) : 'rgba(0,0,0,0)');
  },
  birch_leaves(c, r) {
    for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++)
      px(c, x, y, r() > 0.12 ? shade('#5da244', (r() - 0.5) * 0.3) : 'rgba(0,0,0,0)');
  },
  sand(c, r) { grain(c, r, '#e3d9a8', 0.12); speckle(c, r, ['#d8cd96', '#efe6b8'], 26); },
  sandstone(c, r) {
    grain(c, r, '#d9cf9f', 0.1);
    rect(c, 0, 5, 16, 1, shade('#d9cf9f', -0.2)); rect(c, 0, 11, 16, 1, shade('#d9cf9f', -0.2));
    rect(c, 0, 0, 16, 1, shade('#d9cf9f', 0.15)); rect(c, 0, 15, 16, 1, shade('#d9cf9f', -0.15));
  },
  gravel(c, r) {
    rect(c, 0, 0, 16, 16, '#8a8378');
    speckle(c, r, ['#6f6a61', '#9d968a', '#7d786e', '#b0a99b'], 90);
  },
  clay(c, r) { grain(c, r, '#9aa0b5', 0.1); speckle(c, r, ['#8b91a8', '#aab0c5'], 24); },
  glass(c, r) {
    rect(c, 0, 0, 16, 16, 'rgba(200,225,240,0.14)');
    rect(c, 0, 0, 16, 1, 'rgba(230,245,255,0.85)'); rect(c, 0, 15, 16, 1, 'rgba(230,245,255,0.85)');
    rect(c, 0, 0, 1, 16, 'rgba(230,245,255,0.85)'); rect(c, 15, 0, 1, 16, 'rgba(230,245,255,0.85)');
    for (let i = 0; i < 5; i++) px(c, 3 + i, 13 - i, 'rgba(255,255,255,0.5)');
    for (let i = 0; i < 3; i++) px(c, 9 + i, 6 - i, 'rgba(255,255,255,0.4)');
  },
  ice(c, r) {
    rect(c, 0, 0, 16, 16, 'rgba(170,215,245,0.55)');
    for (let i = 0; i < 16; i += 4) { px(c, i, 3, 'rgba(255,255,255,0.5)'); px(c, 15 - i, 11, 'rgba(255,255,255,0.4)'); }
  },
  water(c, r) {
    for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
      const w = Math.sin((x + y * 2) * 0.8) * 0.5 + Math.sin(y * 1.3) * 0.5;
      px(c, x, y, shade('#2f6fd0', w * 0.14 + (r() - 0.5) * 0.06));
    }
  },
  lava(c, r) {
    for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
      const v = (r() - 0.5) * 0.3 + Math.sin(x * 0.7 + y * 0.9) * 0.15;
      px(c, x, y, v > 0.1 ? '#ffd23e' : (v > -0.05 ? '#ff8c1a' : '#e04e00'));
    }
  },
  snow_top(c, r) { grain(c, r, '#f2f7fa', 0.05); },
  snow_side(c, r) {
    PAINTERS.dirt(c, r);
    rect(c, 0, 0, 16, 4, '#f2f7fa');
    for (let x = 0; x < 16; x++) if (r() > 0.3) px(c, x, 4, '#e6eef4');
  },
  bedrock(c, r) {
    rect(c, 0, 0, 16, 16, '#3a3a3a');
    speckle(c, r, ['#1c1c1c', '#555', '#262626'], 110);
  },
  coal_ore(c, r) { oreTile(c, r, '#8d8d8d', '#1c1c1c'); },
  iron_ore(c, r) { oreTile(c, r, '#8d8d8d', '#d8a47c'); },
  gold_ore(c, r) { oreTile(c, r, '#8d8d8d', '#f7d54d'); },
  diamond_ore(c, r) { oreTile(c, r, '#8d8d8d', '#5ff2e0'); },
  obsidian(c, r) {
    rect(c, 0, 0, 16, 16, '#14101f');
    speckle(c, r, ['#241a3a', '#0a0812', '#3a2a5a'], 40);
  },
  glowstone(c, r) {
    rect(c, 0, 0, 16, 16, '#e8b45a');
    speckle(c, r, ['#ffd98a', '#c98f3a', '#fff3c4'], 70);
  },
  tnt_side(c, r) {
    rect(c, 0, 0, 16, 16, '#c94f2e');
    rect(c, 0, 6, 16, 4, '#e8e0d0');
    for (let x = 0; x < 16; x += 4) px(c, x, 7, '#333'); for (let x = 1; x < 16; x += 4) px(c, x, 8, '#333');
    speckle(c, r, ['#b23e22', '#e06a3f'], 20);
  },
  tnt_top(c, r) { PAINTERS.tnt_side(c, r); rect(c, 6, 6, 4, 4, '#333'); },
  torch(c, r) {
    rect(c, 0, 0, 16, 16, 'rgba(0,0,0,0)');
    rect(c, 6, 6, 4, 9, '#8a5a2b');
    rect(c, 5, 2, 6, 4, '#ffd23e'); px(c, 7, 3, '#fff3c4'); px(c, 8, 4, '#ff9d1a');
  },
  tall_grass(c, r) {
    rect(c, 0, 0, 16, 16, 'rgba(0,0,0,0)');
    for (let i = 0; i < 7; i++) {
      const x = 1 + (r() * 13) | 0, h = 8 + (r() * 8) | 0;
      for (let y = 16 - h; y < 16; y++) px(c, x, y, shade('#4f9c33', (r() - 0.5) * 0.2));
    }
  },
  flower_red(c, r) {
    rect(c, 0, 0, 16, 16, 'rgba(0,0,0,0)');
    rect(c, 7, 6, 2, 10, '#3d7a2a');
    px(c, 6, 9, '#4f9c33'); px(c, 9, 11, '#4f9c33');
    rect(c, 5, 2, 6, 5, '#d43a2a'); px(c, 6, 3, '#f26a55'); px(c, 7, 2, '#f26a55');
    px(c, 7, 4, '#7a1f14');
  },
  flower_yellow(c, r) {
    rect(c, 0, 0, 16, 16, 'rgba(0,0,0,0)');
    rect(c, 7, 6, 2, 10, '#3d7a2a');
    px(c, 9, 10, '#4f9c33');
    rect(c, 5, 2, 6, 5, '#f2d13c'); px(c, 6, 3, '#fff08a'); px(c, 7, 4, '#c9a416');
  },
  crafting_top(c, r) {
    rect(c, 0, 0, 16, 16, '#a9824f');
    rect(c, 3, 3, 10, 10, '#8a6a3c');
    rect(c, 4, 4, 8, 8, '#a9824f');
    rect(c, 6, 6, 4, 4, '#8a6a3c');
    speckle(c, r, [shade('#a9824f', 0.1), shade('#a9824f', -0.1)], 20);
  },
  crafting_side(c, r) {
    PAINTERS.oak_planks(c, r);
    rect(c, 3, 3, 10, 10, 'rgba(60,40,20,0.55)');
    rect(c, 4, 4, 8, 8, 'rgba(120,90,50,0.8)');
  },
  furnace_front(c, r) {
    PAINTERS.stone_brick(c, r);
    rect(c, 4, 4, 8, 8, '#1a1a1a');
    rect(c, 5, 8, 6, 3, '#ff8c1a'); px(c, 6, 7, '#ffd23e'); px(c, 9, 6, '#ffd23e');
  },
  furnace_side(c, r) { PAINTERS.stone_brick(c, r); },
  cactus_side(c, r) {
    rect(c, 0, 0, 16, 16, '#3f8f3a');
    for (let x = 2; x < 16; x += 5) for (let y = 0; y < 16; y++) px(c, x, y, '#2f6f2b');
    speckle(c, r, ['#57ab4f', '#2f6f2b'], 30);
  },
  cactus_top(c, r) {
    rect(c, 0, 0, 16, 16, '#57ab4f');
    for (let x = 2; x < 16; x += 5) for (let y = 0; y < 16; y++) px(c, x, y, '#3f8f3a');
    px(c, 7, 7, '#2f6f2b'); px(c, 8, 8, '#2f6f2b');
  },
  brick(c, r) {
    rect(c, 0, 0, 16, 16, '#9c4a38');
    for (let y = 0; y < 16; y += 4) rect(c, 0, y, 16, 1, '#d8cfc0');
    for (let y = 0; y < 16; y += 4) {
      const off = (y / 4) % 2 === 0 ? 8 : 0;
      rect(c, off, y, 1, 4, '#d8cfc0'); rect(c, off + 12 > 15 ? 15 : off + 12, y, 1, 4, '#d8cfc0');
    }
  },
  stone_brick(c, r) {
    rect(c, 0, 0, 16, 16, '#8d8d8d');
    rect(c, 0, 0, 16, 1, '#a5a5a5'); rect(c, 0, 7, 16, 1, '#6f6f6f'); rect(c, 0, 15, 16, 1, '#6f6f6f');
    rect(c, 7, 1, 1, 6, '#6f6f6f'); rect(c, 3, 8, 1, 7, '#6f6f6f'); rect(c, 11, 8, 1, 7, '#6f6f6f');
    speckle(c, r, ['#7d7d7d', '#9c9c9c'], 20);
  },
  snow_block(c, r) { grain(c, r, '#eef5f9', 0.06); speckle(c, r, ['#dfeaf1'], 14); },
  // ---- items ----
  stick(c, r) { rect(c, 0, 0, 16, 16, 'rgba(0,0,0,0)'); for (let i = 0; i < 11; i++) rect(c, 2 + i, 13 - i, 2, 2, '#8a5a2b'); },
  coal(c, r) { rect(c, 0, 0, 16, 16, 'rgba(0,0,0,0)'); rect(c, 4, 5, 8, 7, '#1c1c1c'); px(c, 5, 6, '#3a3a3a'); px(c, 10, 9, '#000'); px(c, 7, 10, '#444'); },
  raw_iron(c, r) { rect(c, 0, 0, 16, 16, 'rgba(0,0,0,0)'); rect(c, 4, 6, 8, 6, '#d8a47c'); px(c, 5, 7, '#f0c9a8'); px(c, 10, 10, '#b08050'); },
  iron_ingot(c, r) { rect(c, 0, 0, 16, 16, 'rgba(0,0,0,0)'); rect(c, 3, 5, 10, 6, '#e8e8e8'); rect(c, 3, 5, 10, 2, '#ffffff'); rect(c, 3, 10, 10, 1, '#b0b0b0'); },
  raw_gold(c, r) { rect(c, 0, 0, 16, 16, 'rgba(0,0,0,0)'); rect(c, 4, 6, 8, 6, '#e8b93c'); px(c, 5, 7, '#ffe98a'); px(c, 10, 10, '#b8860b'); },
  gold_ingot(c, r) { rect(c, 0, 0, 16, 16, 'rgba(0,0,0,0)'); rect(c, 3, 5, 10, 6, '#f7d54d'); rect(c, 3, 5, 10, 2, '#fff0a0'); rect(c, 3, 10, 10, 1, '#c9a416'); },
  diamond(c, r) {
    rect(c, 0, 0, 16, 16, 'rgba(0,0,0,0)');
    const pts = [[7, 3], [9, 3], [11, 6], [8, 12], [6, 12], [3, 6]];
    c.fillStyle = '#5ff2e0';
    c.beginPath(); c.moveTo(pts[0][0], pts[0][1]);
    for (let i = 1; i < pts.length; i++) c.lineTo(pts[i][0], pts[i][1]);
    c.closePath(); c.fill();
    px(c, 7, 4, '#c4fff7'); px(c, 5, 7, '#2ec4b6');
  },
  glowstone_dust(c, r) { rect(c, 0, 0, 16, 16, 'rgba(0,0,0,0)'); for (let i = 0; i < 9; i++) px(c, 3 + (r() * 10) | 0, 3 + (r() * 10) | 0, r() > 0.5 ? '#ffd98a' : '#e8b45a'); },
  gunpowder(c, r) { rect(c, 0, 0, 16, 16, 'rgba(0,0,0,0)'); for (let i = 0; i < 26; i++) px(c, 3 + (r() * 10) | 0, 4 + (r() * 9) | 0, r() > 0.5 ? '#777' : '#999'); },
  apple(c, r) {
    rect(c, 0, 0, 16, 16, 'rgba(0,0,0,0)');
    c.fillStyle = '#d43a2a'; c.beginPath(); c.arc(8, 9, 5, 0, 7); c.fill();
    px(c, 6, 7, '#f26a55'); px(c, 7, 6, '#f26a55');
    rect(c, 7, 2, 2, 3, '#6e451f'); px(c, 9, 3, '#4f9c33');
  },
  raw_meat(c, r) {
    rect(c, 0, 0, 16, 16, 'rgba(0,0,0,0)');
    c.fillStyle = '#e08a8a'; c.beginPath(); c.ellipse(8, 9, 5, 4, 0.4, 0, 7); c.fill();
    px(c, 6, 8, '#c05a5a'); px(c, 10, 10, '#f0b0b0');
  },
  cooked_meat(c, r) {
    rect(c, 0, 0, 16, 16, 'rgba(0,0,0,0)');
    c.fillStyle = '#8a4a26'; c.beginPath(); c.ellipse(8, 9, 5, 4, 0.4, 0, 7); c.fill();
    px(c, 6, 8, '#6e3a1c'); px(c, 10, 10, '#a86438');
  },
  dark_shard(c, r) {
    rect(c, 0, 0, 16, 16, 'rgba(0,0,0,0)');
    c.fillStyle = '#7a4fd0'; c.beginPath();
    c.moveTo(8, 2); c.lineTo(12, 8); c.lineTo(8, 14); c.lineTo(4, 8); c.closePath(); c.fill();
    px(c, 7, 5, '#b79aff');
  },
  charcoal(c, r) { rect(c, 0, 0, 16, 16, 'rgba(0,0,0,0)'); rect(c, 5, 4, 7, 8, '#151515'); px(c, 6, 6, '#2e2e2e'); px(c, 9, 9, '#050505'); },
  clay_ball(c, r) { rect(c, 0, 0, 16, 16, 'rgba(0,0,0,0)'); c.fillStyle = '#9aa0b5'; c.beginPath(); c.arc(8, 8, 5, 0, 7); c.fill(); px(c, 6, 6, '#c0c6d8'); },
  brick_item(c, r) { rect(c, 0, 0, 16, 16, 'rgba(0,0,0,0)'); rect(c, 3, 5, 10, 6, '#9c4a38'); rect(c, 3, 5, 10, 1, '#c06a54'); rect(c, 7, 5, 1, 6, '#d8cfc0'); },
};

for (const [tier, base] of [['wood', '#b08a4f'], ['stone', '#9a9a9a'], ['iron', '#e8e8e8'], ['gold', '#f7d54d'], ['diamond', '#5ff2e0']]) {
  PAINTERS[`${tier}_pickaxe`] = (c, r) => drawTool(c, base, 'pickaxe');
  PAINTERS[`${tier}_axe`] = (c, r) => drawTool(c, base, 'axe');
  PAINTERS[`${tier}_shovel`] = (c, r) => drawTool(c, base, 'shovel');
  PAINTERS[`${tier}_sword`] = (c, r) => drawTool(c, base, 'sword');
}

export function buildAtlas() {
  const canvas = document.createElement('canvas');
  canvas.width = ATLAS_COLS * TSIZE;
  canvas.height = ATLAS_ROWS * TSIZE;
  const c = canvas.getContext('2d');
  c.clearRect(0, 0, canvas.width, canvas.height);
  const entries = Object.entries(TILE);
  for (const [name, i] of entries) {
    const col = i % ATLAS_COLS, row = (i / ATLAS_COLS) | 0;
    const sub = document.createElement('canvas');
    sub.width = sub.height = TSIZE;
    const sc = sub.getContext('2d');
    const painter = PAINTERS[name];
    if (painter) painter(sc, rng(1234 + i * 77));
    c.drawImage(sub, col * TSIZE, row * TSIZE);
  }
  const texture = new THREE.CanvasTexture(canvas);
  texture.magFilter = THREE.NearestFilter;
  texture.minFilter = THREE.NearestFilter;
  texture.generateMipmaps = false;
  texture.colorSpace = THREE.SRGBColorSpace;

  const eps = 0.35 / (ATLAS_COLS * TSIZE);
  const epsY = 0.35 / (ATLAS_ROWS * TSIZE);
  function uv(i) {
    const col = i % ATLAS_COLS, row = (i / ATLAS_COLS) | 0;
    const u0 = col / ATLAS_COLS + eps, u1 = (col + 1) / ATLAS_COLS - eps;
    const vTop = 1 - row / ATLAS_ROWS - epsY, vBot = 1 - (row + 1) / ATLAS_ROWS + epsY;
    return { u0, v0: vBot, u1, v1: vTop };
  }
  function tileCanvas(i) {
    const col = i % ATLAS_COLS, row = (i / ATLAS_COLS) | 0;
    const cv = document.createElement('canvas');
    cv.width = cv.height = TSIZE;
    cv.getContext('2d').drawImage(canvas, col * TSIZE, row * TSIZE, TSIZE, TSIZE, 0, 0, TSIZE, TSIZE);
    return cv;
  }
  return { canvas, texture, uv, tileCanvas };
}

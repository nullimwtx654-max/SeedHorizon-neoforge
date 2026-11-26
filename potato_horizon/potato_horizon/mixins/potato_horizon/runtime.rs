// PotatoHorizon (potato_horizon) — Ultra-lightweight 6-to-16 chunk LOD core for Horizon.
//
// Designed for low-end / "potato" PCs that can only run ~6 real chunks smoothly.
// Extends visible terrain out to 16 chunks (configurable 8..=32) using:
//   * 2-tier LOD ring (Tier 1: 2x2 cols for d <= 11, Tier 2: 4x4 cols for d <= 16)
//   * Compact height-span columns (max 3 vertical spans: terrain, water, canopy)
//   * 2D greedy top-face meshing + merged cliff skirts + strict cave culling
//   * Hard per-tick sample and per-frame microsecond/mesh budgets (zero-alloc scratch)
//   * Zero-dependency compact binary region disk cache (.plod format)
//
// horizon-mixins rules: no inner doc comments, no inner attributes, no external `mod x;`, no `include!`.

use std::collections::{HashMap, HashSet};
use std::io::{self, Read, Write};
use std::time::Instant;

pub const ID: &str = "potato_horizon";
pub const DISPLAY_NAME: &str = "PotatoHorizon LOD";
pub const VERSION: &str = "0.1.0";

pub const PLOD_MAGIC: [u8; 4] = *b"PLOD";
pub const PLOD_FORMAT_VERSION: u16 = 1;
pub const REGION_SIZE_CHUNKS: i32 = 32;
pub const MAX_SPANS_PER_COLUMN: usize = 3;

// Packed flag bit layout inside ColumnSpan::packed_flags:
//   bits 4..=7 : sky light (0..=15)
//   bit 0      : is_water
//   bit 1      : is_foliage (leaves / canopy)
//   bit 2      : is_emissive (lava / glowstone / lanterns)
//   bit 3      : reserved
const FLAG_WATER: u8 = 0b0000_0001;
const FLAG_FOLIAGE: u8 = 0b0000_0010;
const FLAG_EMISSIVE: u8 = 0b0000_0100;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct ChunkCoord {
    pub x: i32,
    pub z: i32,
}

impl ChunkCoord {
    pub const fn new(x: i32, z: i32) -> Self {
        Self { x, z }
    }

    pub const fn dist_sq(self, other: Self) -> i64 {
        let dx = (self.x - other.x) as i64;
        let dz = (self.z - other.z) as i64;
        dx * dx + dz * dz
    }

    pub const fn region_coord(self) -> (i32, i32) {
        (
            self.x.div_euclid(REGION_SIZE_CHUNKS),
            self.z.div_euclid(REGION_SIZE_CHUNKS),
        )
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PotatoPreset {
    UltraPotato,
    Potato,
    Balanced,
}

impl PotatoPreset {
    pub const fn label(self) -> &'static str {
        match self {
            Self::UltraPotato => "Ultra Potato",
            Self::Potato => "Potato (Default)",
            Self::Balanced => "Balanced",
        }
    }

    pub const fn next(self) -> Self {
        match self {
            Self::UltraPotato => Self::Potato,
            Self::Potato => Self::Balanced,
            Self::Balanced => Self::UltraPotato,
        }
    }

    pub fn parse(s: &str) -> Option<Self> {
        match s.trim().to_ascii_lowercase().as_str() {
            "ultra" | "ultrapotato" | "ultra_potato" | "toaster" => Some(Self::UltraPotato),
            "potato" | "low" | "default" => Some(Self::Potato),
            "balanced" | "med" | "medium" => Some(Self::Balanced),
            _ => None,
        }
    }

    pub const fn max_meshes_per_frame(self) -> usize {
        match self {
            Self::UltraPotato => 1,
            Self::Potato => 2,
            Self::Balanced => 4,
        }
    }

    pub const fn max_samples_per_tick(self) -> usize {
        match self {
            Self::UltraPotato => 1,
            Self::Potato => 2,
            Self::Balanced => 4,
        }
    }

    pub const fn frame_budget_us(self) -> u64 {
        match self {
            Self::UltraPotato => 600,
            Self::Potato => 1_000,
            Self::Balanced => 1_800,
        }
    }

    pub const fn include_canopy(self) -> bool {
        !matches!(self, Self::UltraPotato)
    }

    pub const fn near_tier_offset(self) -> u8 {
        match self {
            Self::UltraPotato => 3,
            Self::Potato => 5,
            Self::Balanced => 6,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Config {
    pub enabled: bool,
    pub real_distance: u8,
    pub lod_distance: u8,
    pub preset: PotatoPreset,
    pub cave_culling: bool,
    pub seam_overlap_blocks: u8,
    pub fog_start_fraction: f32,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            enabled: true,
            real_distance: 6,
            lod_distance: 16,
            preset: PotatoPreset::Potato,
            cave_culling: true,
            seam_overlap_blocks: 2,
            fog_start_fraction: 0.78,
        }
    }
}

impl Config {
    pub fn clamp(&mut self) {
        self.real_distance = self.real_distance.clamp(2, 12);
        self.lod_distance = self.lod_distance.clamp(8, 32);
        if self.lod_distance <= self.real_distance + 1 {
            self.lod_distance = (self.real_distance + 2).min(32);
        }
        self.seam_overlap_blocks = self.seam_overlap_blocks.clamp(0, 4);
        self.fog_start_fraction = self.fog_start_fraction.clamp(0.45, 0.95);
    }

    pub fn near_lod_radius(&self) -> u8 {
        let desired = self
            .real_distance
            .saturating_add(self.preset.near_tier_offset());
        desired.min(self.lod_distance)
    }

    pub fn desired_tier(&self, dist_sq: i64) -> Option<LodTier> {
        let lod_r = self.lod_distance as i64;
        if dist_sq > lod_r * lod_r {
            return None;
        }
        let real_r = self.real_distance as i64;
        if dist_sq <= real_r * real_r {
            return None;
        }
        let near_r = self.near_lod_radius() as i64;
        if dist_sq <= near_r * near_r {
            Some(LodTier::Near2x2)
        } else {
            Some(LodTier::Far4x4)
        }
    }

    pub fn effective_terrain_distance(&self, vanilla_distance: u32) -> u32 {
        if !self.enabled {
            return vanilla_distance;
        }
        vanilla_distance.max(self.lod_distance as u32)
    }

    pub fn fog_range_blocks(&self) -> (f32, f32) {
        let far_blocks = (self.lod_distance as f32) * 16.0;
        let start_blocks = (far_blocks * self.fog_start_fraction)
            .max((self.real_distance as f32 + 1.5) * 16.0);
        (start_blocks.min(far_blocks - 16.0), far_blocks)
    }

    pub fn to_kv_string(&self) -> String {
        let preset_name = match self.preset {
            PotatoPreset::UltraPotato => "ultra",
            PotatoPreset::Potato => "potato",
            PotatoPreset::Balanced => "balanced",
        };
        format!(
            "enabled={}\nreal_distance={}\nlod_distance={}\npreset={}\ncave_culling={}\nseam_overlap_blocks={}\nfog_start_fraction={:.2}\n",
            self.enabled,
            self.real_distance,
            self.lod_distance,
            preset_name,
            self.cave_culling,
            self.seam_overlap_blocks,
            self.fog_start_fraction
        )
    }

    pub fn from_kv_string(input: &str) -> Self {
        let mut cfg = Self::default();
        for line in input.lines() {
            let trimmed = line.trim();
            if trimmed.is_empty() || trimmed.starts_with('#') {
                continue;
            }
            if let Some((k, v)) = trimmed.split_once('=') {
                let key = k.trim();
                let val = v.trim();
                match key {
                    "enabled" => {
                        if let Ok(b) = val.parse::<bool>() {
                            cfg.enabled = b;
                        }
                    }
                    "real_distance" => {
                        if let Ok(n) = val.parse::<u8>() {
                            cfg.real_distance = n;
                        }
                    }
                    "lod_distance" => {
                        if let Ok(n) = val.parse::<u8>() {
                            cfg.lod_distance = n;
                        }
                    }
                    "preset" => {
                        if let Some(p) = PotatoPreset::parse(val) {
                            cfg.preset = p;
                        }
                    }
                    "cave_culling" => {
                        if let Ok(b) = val.parse::<bool>() {
                            cfg.cave_culling = b;
                        }
                    }
                    "seam_overlap_blocks" => {
                        if let Ok(n) = val.parse::<u8>() {
                            cfg.seam_overlap_blocks = n;
                        }
                    }
                    "fog_start_fraction" => {
                        if let Ok(f) = val.parse::<f32>() {
                            cfg.fog_start_fraction = f;
                        }
                    }
                    _ => {}
                }
            }
        }
        cfg.clamp();
        cfg
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum LodTier {
    Near2x2,
    Far4x4,
}

impl LodTier {
    pub const fn grid_size(self) -> usize {
        match self {
            Self::Near2x2 => 8,
            Self::Far4x4 => 4,
        }
    }

    pub const fn cell_blocks(self) -> i32 {
        match self {
            Self::Near2x2 => 2,
            Self::Far4x4 => 4,
        }
    }

    pub const fn column_count(self) -> usize {
        let g = self.grid_size();
        g * g
    }

    pub const fn to_u8(self) -> u8 {
        match self {
            Self::Near2x2 => 1,
            Self::Far4x4 => 2,
        }
    }

    pub const fn from_u8(v: u8) -> Option<Self> {
        match v {
            1 => Some(Self::Near2x2),
            2 => Some(Self::Far4x4),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ColumnSpan {
    pub top_y: i16,
    pub bottom_y: i16,
    pub block_id: u16,
    pub biome_id: u8,
    pub packed_flags: u8,
}

impl ColumnSpan {
    pub const fn new(
        top_y: i16,
        bottom_y: i16,
        block_id: u16,
        biome_id: u8,
        sky_light: u8,
        is_water: bool,
        is_foliage: bool,
        is_emissive: bool,
    ) -> Self {
        let mut flags = (sky_light & 0x0F) << 4;
        if is_water {
            flags |= FLAG_WATER;
        }
        if is_foliage {
            flags |= FLAG_FOLIAGE;
        }
        if is_emissive {
            flags |= FLAG_EMISSIVE;
        }
        Self {
            top_y,
            bottom_y,
            block_id,
            biome_id,
            packed_flags: flags,
        }
    }

    pub const fn sky_light(self) -> u8 {
        (self.packed_flags >> 4) & 0x0F
    }

    pub const fn is_water(self) -> bool {
        (self.packed_flags & FLAG_WATER) != 0
    }

    pub const fn is_foliage(self) -> bool {
        (self.packed_flags & FLAG_FOLIAGE) != 0
    }

    pub const fn is_emissive(self) -> bool {
        (self.packed_flags & FLAG_EMISSIVE) != 0
    }

    pub const fn is_empty(self) -> bool {
        self.block_id == 0
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct LodColumn {
    pub spans: [ColumnSpan; MAX_SPANS_PER_COLUMN],
    pub len: u8,
}

impl LodColumn {
    pub const fn empty() -> Self {
        Self {
            spans: [ColumnSpan {
                top_y: 0,
                bottom_y: 0,
                block_id: 0,
                biome_id: 0,
                packed_flags: 0,
            }; MAX_SPANS_PER_COLUMN],
            len: 0,
        }
    }

    pub fn push_span(&mut self, span: ColumnSpan) {
        if span.is_empty() {
            return;
        }
        let idx = self.len as usize;
        if idx < MAX_SPANS_PER_COLUMN {
            self.spans[idx] = span;
            self.len += 1;
        }
    }

    pub fn as_slice(&self) -> &[ColumnSpan] {
        &self.spans[..(self.len as usize).min(MAX_SPANS_PER_COLUMN)]
    }

    pub fn highest_span(&self) -> Option<ColumnSpan> {
        let mut best: Option<ColumnSpan> = None;
        for &span in self.as_slice() {
            match best {
                None => best = Some(span),
                Some(b) if span.top_y > b.top_y => best = Some(span),
                _ => {}
            }
        }
        best
    }

    pub fn primary_surface(&self) -> Option<ColumnSpan> {
        self.as_slice()
            .iter()
            .copied()
            .find(|s| !s.is_water() && !s.is_foliage())
            .or_else(|| self.highest_span())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ChunkSource {
    VisitedChunk,
    HeightmapSample,
    DiskCache,
}

#[derive(Debug, Clone)]
pub struct LodChunk {
    pub coord: ChunkCoord,
    pub tier: LodTier,
    pub source: ChunkSource,
    pub columns: Vec<LodColumn>,
    pub dirty_mesh: bool,
    pub dirty_disk: bool,
}

impl LodChunk {
    pub fn new(coord: ChunkCoord, tier: LodTier, source: ChunkSource) -> Self {
        Self {
            coord,
            tier,
            source,
            columns: vec![LodColumn::empty(); tier.column_count()],
            dirty_mesh: true,
            dirty_disk: source != ChunkSource::DiskCache,
        }
    }

    #[inline]
    pub fn get(&self, gx: usize, gz: usize) -> &LodColumn {
        let g = self.tier.grid_size();
        &self.columns[gz * g + gx]
    }

    #[inline]
    pub fn set(&mut self, gx: usize, gz: usize, col: LodColumn) {
        let g = self.tier.grid_size();
        self.columns[gz * g + gx] = col;
        self.dirty_mesh = true;
    }

    pub fn approx_bytes(&self) -> usize {
        std::mem::size_of::<Self>() + self.columns.capacity() * std::mem::size_of::<LodColumn>()
    }

    // Builds a Near2x2 (8x8) LOD chunk directly from a 16x16 real chunk's columns.
    // Captures dominant terrain surface, water layer, and optional tree canopy.
    pub fn capture_from_full_chunk(
        coord: ChunkCoord,
        full_columns_16x16: &[LodColumn; 256],
        include_canopy: bool,
        cave_culling: bool,
    ) -> Self {
        let mut chunk = Self::new(coord, LodTier::Near2x2, ChunkSource::VisitedChunk);
        for gz in 0..8 {
            for gx in 0..8 {
                let mut cell_cols = [LodColumn::empty(); 4];
                for dz in 0..2 {
                    for dx in 0..2 {
                        let fx = gx * 2 + dx;
                        let fz = gz * 2 + dz;
                        cell_cols[dz * 2 + dx] = full_columns_16x16[fz * 16 + fx];
                    }
                }
                let merged = merge_2x2_columns(&cell_cols, include_canopy, cave_culling);
                chunk.set(gx, gz, merged);
            }
        }
        chunk.dirty_disk = true;
        chunk
    }

    // Downsamples Near2x2 (8x8) -> Far4x4 (4x4) with zero worldgen cost.
    pub fn downsample_to_far(&self, include_canopy: bool, cave_culling: bool) -> Self {
        if self.tier == LodTier::Far4x4 {
            return self.clone();
        }
        let mut out = Self::new(self.coord, LodTier::Far4x4, self.source);
        out.dirty_disk = self.dirty_disk;
        for gz in 0..4 {
            for gx in 0..4 {
                let cell_cols = [
                    *self.get(gx * 2, gz * 2),
                    *self.get(gx * 2 + 1, gz * 2),
                    *self.get(gx * 2, gz * 2 + 1),
                    *self.get(gx * 2 + 1, gz * 2 + 1),
                ];
                let merged = merge_2x2_columns(&cell_cols, include_canopy, cave_culling);
                out.set(gx, gz, merged);
            }
        }
        out
    }
}

fn merge_2x2_columns(
    cols: &[LodColumn; 4],
    include_canopy: bool,
    cave_culling: bool,
) -> LodColumn {
    let mut out = LodColumn::empty();

    let mut ground_candidates: Vec<ColumnSpan> = Vec::with_capacity(4);
    let mut water_candidates: Vec<ColumnSpan> = Vec::with_capacity(4);
    let mut canopy_candidates: Vec<ColumnSpan> = Vec::with_capacity(4);

    for col in cols {
        for &span in col.as_slice() {
            if cave_culling && span.sky_light() == 0 && !span.is_emissive() {
                continue;
            }
            if span.is_water() {
                water_candidates.push(span);
            } else if span.is_foliage() {
                if include_canopy {
                    canopy_candidates.push(span);
                }
            } else {
                ground_candidates.push(span);
            }
        }
    }

    if let Some(best_ground) = pick_dominant_span(&ground_candidates) {
        out.push_span(best_ground);
    }
    if let Some(best_water) = pick_dominant_span(&water_candidates) {
        out.push_span(best_water);
    }
    if include_canopy && canopy_candidates.len() >= 2 {
        if let Some(best_canopy) = pick_dominant_span(&canopy_candidates) {
            out.push_span(best_canopy);
        }
    }

    out
}

fn pick_dominant_span(spans: &[ColumnSpan]) -> Option<ColumnSpan> {
    if spans.is_empty() {
        return None;
    }
    let mut best_idx = 0usize;
    let mut best_score = 0u32;
    let mut max_top = i16::MIN;
    let mut min_bot = i16::MAX;

    for (i, &s) in spans.iter().enumerate() {
        if s.top_y > max_top {
            max_top = s.top_y;
        }
        if s.bottom_y < min_bot {
            min_bot = s.bottom_y;
        }
        let freq = spans.iter().filter(|other| other.block_id == s.block_id).count() as u32;
        let score = (freq << 16) | ((s.top_y as i32 + 2048).max(0) as u32 & 0xFFFF);
        if score > best_score {
            best_score = score;
            best_idx = i;
        }
    }

    let mut chosen = spans[best_idx];
    chosen.top_y = max_top;
    chosen.bottom_y = min_bot.min(max_top - 1);
    Some(chosen)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum QuadFace {
    Top,
    North, // -Z
    South, // +Z
    West,  // -X
    East,  // +X
}

impl QuadFace {
    pub const fn shade_factor(self) -> f32 {
        match self {
            Self::Top => 1.00,
            Self::North | Self::South => 0.82,
            Self::West | Self::East => 0.72,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct LodQuad {
    pub x0: i32,
    pub y0: i32,
    pub z0: i32,
    pub x1: i32,
    pub y1: i32,
    pub z1: i32,
    pub face: QuadFace,
    pub block_id: u16,
    pub biome_id: u8,
    pub sky_light: u8,
    pub is_water: bool,
    pub shade: f32,
}

#[derive(Debug, Clone, Default)]
pub struct LodMesh {
    pub coord: Option<ChunkCoord>,
    pub tier: Option<LodTier>,
    pub quads: Vec<LodQuad>,
}

impl LodMesh {
    pub fn triangle_count(&self) -> usize {
        self.quads.len() * 2
    }

    pub fn approx_bytes(&self) -> usize {
        self.quads.capacity() * std::mem::size_of::<LodQuad>()
    }
}

// Reusable scratch buffer to avoid heap churn on low-end CPUs.
#[derive(Debug)]
pub struct MeshScratch {
    visited: [bool; 64],
    top_cells: [Option<ColumnSpan>; 64],
}

impl Default for MeshScratch {
    fn default() -> Self {
        Self {
            visited: [false; 64],
            top_cells: [None; 64],
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct TopCellKey {
    top_y: i16,
    block_id: u16,
    biome_id: u8,
    sky_light: u8,
    is_water: bool,
}

impl TopCellKey {
    fn from_span(s: ColumnSpan) -> Self {
        Self {
            top_y: s.top_y,
            block_id: s.block_id,
            biome_id: s.biome_id,
            sky_light: s.sky_light(),
            is_water: s.is_water(),
        }
    }
}

// Greedy 2D top-face + cliff-skirt mesher for a single LOD chunk.
pub fn build_chunk_mesh(
    chunk: &LodChunk,
    neighbor_min_y: [i16; 4], // [North(-Z), South(+Z), West(-X), East(+X)]
    scratch: &mut MeshScratch,
    out: &mut LodMesh,
) {
    out.quads.clear();
    out.coord = Some(chunk.coord);
    out.tier = Some(chunk.tier);

    let g = chunk.tier.grid_size();
    let step = chunk.tier.cell_blocks();
    let base_x = chunk.coord.x * 16;
    let base_z = chunk.coord.z * 16;

    // Pass 1: Extract highest visible span per cell for greedy top-face meshing.
    for gz in 0..g {
        for gx in 0..g {
            let idx = gz * g + gx;
            scratch.visited[idx] = false;
            scratch.top_cells[idx] = chunk.get(gx, gz).highest_span();
        }
    }

    // Greedy 2D rectangle merge on top faces.
    for gz in 0..g {
        for gx in 0..g {
            let idx = gz * g + gx;
            if scratch.visited[idx] {
                continue;
            }
            let Some(span) = scratch.top_cells[idx] else {
                scratch.visited[idx] = true;
                continue;
            };
            let key = TopCellKey::from_span(span);

            // Expand along +X
            let mut width = 1usize;
            while gx + width < g {
                let nidx = gz * g + (gx + width);
                if scratch.visited[nidx] {
                    break;
                }
                match scratch.top_cells[nidx] {
                    Some(ns) if TopCellKey::from_span(ns) == key => width += 1,
                    _ => break,
                }
            }

            // Expand along +Z
            let mut depth = 1usize;
            'outer: while gz + depth < g {
                for dx in 0..width {
                    let nidx = (gz + depth) * g + (gx + dx);
                    if scratch.visited[nidx] {
                        break 'outer;
                    }
                    match scratch.top_cells[nidx] {
                        Some(ns) if TopCellKey::from_span(ns) == key => {}
                        _ => break 'outer,
                    }
                }
                depth += 1;
            }

            for dz in 0..depth {
                for dx in 0..width {
                    scratch.visited[(gz + dz) * g + (gx + dx)] = true;
                }
            }

            let x0 = base_x + (gx as i32) * step;
            let z0 = base_z + (gz as i32) * step;
            let x1 = x0 + (width as i32) * step;
            let z1 = z0 + (depth as i32) * step;
            let y = span.top_y as i32;

            out.quads.push(LodQuad {
                x0,
                y0: y,
                z0,
                x1,
                y1: y,
                z1,
                face: QuadFace::Top,
                block_id: span.block_id,
                biome_id: span.biome_id,
                sky_light: span.sky_light(),
                is_water: span.is_water(),
                shade: QuadFace::Top.shade_factor(),
            });
        }
    }

    // Pass 2: Emit vertical cliff & border skirts where adjacent columns drop in height.
    for gz in 0..g {
        for gx in 0..g {
            let Some(span) = scratch.top_cells[gz * g + gx] else {
                continue;
            };
            let x0 = base_x + (gx as i32) * step;
            let z0 = base_z + (gz as i32) * step;
            let x1 = x0 + step;
            let z1 = z0 + step;
            let top = span.top_y as i32;

            let neighbors: [(QuadFace, i16); 4] = [
                (
                    QuadFace::North,
                    if gz > 0 {
                        scratch.top_cells[(gz - 1) * g + gx]
                            .map(|s| s.top_y)
                            .unwrap_or(span.bottom_y)
                    } else {
                        neighbor_min_y[0].min(span.top_y)
                    },
                ),
                (
                    QuadFace::South,
                    if gz + 1 < g {
                        scratch.top_cells[(gz + 1) * g + gx]
                            .map(|s| s.top_y)
                            .unwrap_or(span.bottom_y)
                    } else {
                        neighbor_min_y[1].min(span.top_y)
                    },
                ),
                (
                    QuadFace::West,
                    if gx > 0 {
                        scratch.top_cells[gz * g + (gx - 1)]
                            .map(|s| s.top_y)
                            .unwrap_or(span.bottom_y)
                    } else {
                        neighbor_min_y[2].min(span.top_y)
                    },
                ),
                (
                    QuadFace::East,
                    if gx + 1 < g {
                        scratch.top_cells[gz * g + (gx + 1)]
                            .map(|s| s.top_y)
                            .unwrap_or(span.bottom_y)
                    } else {
                        neighbor_min_y[3].min(span.top_y)
                    },
                ),
            ];

            for (face, n_top) in neighbors {
                let ny = (n_top.max(span.bottom_y)) as i32;
                if top <= ny {
                    continue;
                }
                let (qx0, qz0, qx1, qz1) = match face {
                    QuadFace::North => (x0, z0, x1, z0),
                    QuadFace::South => (x0, z1, x1, z1),
                    QuadFace::West => (x0, z0, x0, z1),
                    QuadFace::East => (x1, z0, x1, z1),
                    QuadFace::Top => continue,
                };
                out.quads.push(LodQuad {
                    x0: qx0,
                    y0: ny,
                    z0: qz0,
                    x1: qx1,
                    y1: top,
                    z1: qz1,
                    face,
                    block_id: span.block_id,
                    biome_id: span.biome_id,
                    sky_light: span.sky_light(),
                    is_water: span.is_water(),
                    shade: face.shade_factor(),
                });
            }
        }
    }
}

// Trait implemented by bridge.rs so runtime.rs stays decoupled from Horizon internals.
pub trait SurfaceSampler {
    fn sample_lod_chunk(&mut self, coord: ChunkCoord, tier: LodTier, cfg: &Config) -> Option<LodChunk>;
}

#[derive(Debug, Clone, Copy, Default)]
pub struct LodStats {
    pub near_chunks: usize,
    pub far_chunks: usize,
    pub meshes_ready: usize,
    pub total_quads: usize,
    pub total_triangles: usize,
    pub approx_ram_kb: usize,
    pub pending_samples: usize,
    pub pending_meshes: usize,
}

pub struct LodEngine {
    pub config: Config,
    pub player_chunk: ChunkCoord,
    chunks: HashMap<ChunkCoord, LodChunk>,
    meshes: HashMap<ChunkCoord, LodMesh>,
    real_loaded: HashSet<ChunkCoord>,
    sample_queue: Vec<ChunkCoord>,
    mesh_queue: Vec<ChunkCoord>,
    scratch: MeshScratch,
    ring_dirty: bool,
}

impl LodEngine {
    pub fn new(config: Config) -> Self {
        let mut cfg = config;
        cfg.clamp();
        Self {
            config: cfg,
            player_chunk: ChunkCoord::new(0, 0),
            chunks: HashMap::new(),
            meshes: HashMap::new(),
            real_loaded: HashSet::new(),
            sample_queue: Vec::new(),
            mesh_queue: Vec::new(),
            scratch: MeshScratch::default(),
            ring_dirty: true,
        }
    }

    pub fn update_player_chunk(&mut self, coord: ChunkCoord) {
        if coord != self.player_chunk {
            self.player_chunk = coord;
            self.ring_dirty = true;
        }
    }

    pub fn mark_real_chunk_loaded(&mut self, coord: ChunkCoord, loaded: bool) {
        if loaded {
            self.real_loaded.insert(coord);
            self.meshes.remove(&coord);
        } else {
            self.real_loaded.remove(&coord);
            if self.chunks.contains_key(&coord) {
                self.mesh_queue.push(coord);
            }
        }
    }

    // Called by bridge.rs when a real Horizon chunk is loaded/modified: captures it for free.
    pub fn ingest_visited_chunk(&mut self, coord: ChunkCoord, cols_16x16: &[LodColumn; 256]) {
        let lod = LodChunk::capture_from_full_chunk(
            coord,
            cols_16x16,
            self.config.preset.include_canopy(),
            self.config.cave_culling,
        );
        self.chunks.insert(coord, lod);
        if !self.real_loaded.contains(&coord) {
            self.mesh_queue.push(coord);
        }
    }

    pub fn insert_cached_chunk(&mut self, chunk: LodChunk) {
        let coord = chunk.coord;
        let should_mesh = !self.real_loaded.contains(&coord);
        self.chunks.insert(coord, chunk);
        if should_mesh {
            self.mesh_queue.push(coord);
        }
    }

    pub fn clear_all(&mut self) {
        self.chunks.clear();
        self.meshes.clear();
        self.sample_queue.clear();
        self.mesh_queue.clear();
        self.ring_dirty = true;
    }

    pub fn rebuild_ring_queues(&mut self) {
        self.sample_queue.clear();
        self.mesh_queue.clear();

        let r = self.config.lod_distance as i32;
        let evict_r = (self.config.lod_distance as i64) + 2;
        let evict_sq = evict_r * evict_r;
        let center = self.player_chunk;

        // Evict distant chunks beyond lod_distance + 2 (hysteresis prevents thrashing).
        self.chunks.retain(|c, _| c.dist_sq(center) <= evict_sq);
        self.meshes.retain(|c, _| {
            let d2 = c.dist_sq(center);
            d2 <= evict_sq && !self.real_loaded.contains(c)
        });

        let mut needed_samples: Vec<(i64, ChunkCoord)> = Vec::new();
        let mut needed_meshes: Vec<(i64, ChunkCoord)> = Vec::new();

        for dz in -r..=r {
            for dx in -r..=r {
                let c = ChunkCoord::new(center.x + dx, center.z + dz);
                let d2 = c.dist_sq(center);
                let Some(target_tier) = self.config.desired_tier(d2) else {
                    continue;
                };
                if self.real_loaded.contains(&c) {
                    continue;
                }

                match self.chunks.get_mut(&c) {
                    Some(existing) => {
                        if existing.tier == LodTier::Near2x2 && target_tier == LodTier::Far4x4 {
                            let down = existing.downsample_to_far(
                                self.config.preset.include_canopy(),
                                self.config.cave_culling,
                            );
                            *existing = down;
                            needed_meshes.push((d2, c));
                        } else if existing.dirty_mesh || !self.meshes.contains_key(&c) {
                            needed_meshes.push((d2, c));
                        }
                    }
                    None => {
                        needed_samples.push((d2, c));
                    }
                }
            }
        }

        // Closest chunks first so the horizon right outside chunk 6 fills immediately!
        needed_samples.sort_by_key(|(d2, _)| *d2);
        needed_meshes.sort_by_key(|(d2, _)| *d2);

        // Store reversed so Vec::pop() yields closest chunk in O(1).
        self.sample_queue = needed_samples.into_iter().rev().map(|(_, c)| c).collect();
        self.mesh_queue = needed_meshes.into_iter().rev().map(|(_, c)| c).collect();
        self.ring_dirty = false;
    }

    // Tick step: strictly capped at preset.max_samples_per_tick().
    pub fn step_tick<S: SurfaceSampler>(&mut self, sampler: &mut S) -> usize {
        if !self.config.enabled {
            return 0;
        }
        if self.ring_dirty {
            self.rebuild_ring_queues();
        }

        let budget = self.config.preset.max_samples_per_tick();
        let mut sampled = 0usize;

        while sampled < budget {
            let Some(coord) = self.sample_queue.pop() else {
                break;
            };
            if self.chunks.contains_key(&coord) || self.real_loaded.contains(&coord) {
                continue;
            }
            let d2 = coord.dist_sq(self.player_chunk);
            let Some(tier) = self.config.desired_tier(d2) else {
                continue;
            };
            if let Some(chunk) = sampler.sample_lod_chunk(coord, tier, &self.config) {
                self.chunks.insert(coord, chunk);
                self.mesh_queue.push(coord);
                sampled += 1;
            }
        }

        sampled
    }

    // Frame step: strictly capped by both max_meshes_per_frame and microsecond budget.
    pub fn step_frame(&mut self) -> usize {
        if !self.config.enabled {
            return 0;
        }
        if self.ring_dirty {
            self.rebuild_ring_queues();
        }

        let max_meshes = self.config.preset.max_meshes_per_frame();
        let budget_us = self.config.preset.frame_budget_us();
        let start = Instant::now();
        let mut built = 0usize;

        while built < max_meshes {
            if built > 0 && start.elapsed().as_micros() as u64 >= budget_us {
                break;
            }
            let Some(coord) = self.mesh_queue.pop() else {
                break;
            };
            if self.real_loaded.contains(&coord) {
                self.meshes.remove(&coord);
                continue;
            }
            let Some(chunk) = self.chunks.get(&coord) else {
                continue;
            };

            let neighbor_min_y = self.neighbor_border_heights(coord);
            let mut mesh = self.meshes.remove(&coord).unwrap_or_default();
            build_chunk_mesh(chunk, neighbor_min_y, &mut self.scratch, &mut mesh);
            self.meshes.insert(coord, mesh);
            if let Some(c) = self.chunks.get_mut(&coord) {
                c.dirty_mesh = false;
            }
            built += 1;
        }

        built
    }

    fn neighbor_border_heights(&self, coord: ChunkCoord) -> [i16; 4] {
        let offsets = [(0, -1), (0, 1), (-1, 0), (1, 0)];
        let mut heights = [i16::MIN; 4];
        for (i, (dx, dz)) in offsets.iter().enumerate() {
            let nc = ChunkCoord::new(coord.x + dx, coord.z + dz);
            if let Some(nchunk) = self.chunks.get(&nc) {
                let mut min_y = i16::MAX;
                for col in &nchunk.columns {
                    if let Some(s) = col.highest_span() {
                        if s.top_y < min_y {
                            min_y = s.top_y;
                        }
                    }
                }
                if min_y != i16::MAX {
                    heights[i] = min_y;
                }
            } else {
                // Slight skirt drop at unloaded borders so there are no sky cracks.
                heights[i] = -64;
            }
        }
        heights
    }

    pub fn active_meshes(&self) -> impl Iterator<Item = (&ChunkCoord, &LodMesh)> {
        self.meshes.iter()
    }

    pub fn dirty_disk_chunks(&self) -> impl Iterator<Item = &LodChunk> {
        self.chunks.values().filter(|c| c.dirty_disk)
    }

    pub fn mark_all_saved(&mut self) {
        for c in self.chunks.values_mut() {
            c.dirty_disk = false;
        }
    }

    pub fn stats(&self) -> LodStats {
        let mut s = LodStats {
            pending_samples: self.sample_queue.len(),
            pending_meshes: self.mesh_queue.len(),
            ..LodStats::default()
        };
        let mut bytes = 0usize;
        for c in self.chunks.values() {
            match c.tier {
                LodTier::Near2x2 => s.near_chunks += 1,
                LodTier::Far4x4 => s.far_chunks += 1,
            }
            bytes += c.approx_bytes();
        }
        for m in self.meshes.values() {
            s.meshes_ready += 1;
            s.total_quads += m.quads.len();
            s.total_triangles += m.triangle_count();
            bytes += m.approx_bytes();
        }
        s.approx_ram_kb = (bytes + 1023) / 1024;
        s
    }
}

// Compact binary region cache (.plod) — zero SQLite, zero external dependencies.
pub fn encode_region_plod<W: Write>(
    writer: &mut W,
    registry_hash: u64,
    chunks: &[&LodChunk],
) -> io::Result<()> {
    writer.write_all(&PLOD_MAGIC)?;
    writer.write_all(&PLOD_FORMAT_VERSION.to_le_bytes())?;
    writer.write_all(&registry_hash.to_le_bytes())?;
    writer.write_all(&(chunks.len() as u32).to_le_bytes())?;

    for chunk in chunks {
        writer.write_all(&chunk.coord.x.to_le_bytes())?;
        writer.write_all(&chunk.coord.z.to_le_bytes())?;
        writer.write_all(&[chunk.tier.to_u8()])?;
        for col in &chunk.columns {
            let count = (col.len as usize).min(MAX_SPANS_PER_COLUMN) as u8;
            writer.write_all(&[count])?;
            for span in col.as_slice() {
                writer.write_all(&span.top_y.to_le_bytes())?;
                writer.write_all(&span.bottom_y.to_le_bytes())?;
                writer.write_all(&span.block_id.to_le_bytes())?;
                writer.write_all(&[span.biome_id, span.packed_flags])?;
            }
        }
    }
    Ok(())
}

pub fn decode_region_plod<R: Read>(
    reader: &mut R,
    expected_registry_hash: u64,
) -> io::Result<Vec<LodChunk>> {
    let mut magic = [0u8; 4];
    reader.read_exact(&mut magic)?;
    if magic != PLOD_MAGIC {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "bad PLOD magic"));
    }
    let mut v_buf = [0u8; 2];
    reader.read_exact(&mut v_buf)?;
    let ver = u16::from_le_bytes(v_buf);
    if ver != PLOD_FORMAT_VERSION {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "unsupported PLOD version",
        ));
    }
    let mut hash_buf = [0u8; 8];
    reader.read_exact(&mut hash_buf)?;
    let hash = u64::from_le_bytes(hash_buf);
    if hash != expected_registry_hash {
        // Block registry changed; gracefully invalidate cache.
        return Ok(Vec::new());
    }
    let mut count_buf = [0u8; 4];
    reader.read_exact(&mut count_buf)?;
    let chunk_count = u32::from_le_bytes(count_buf) as usize;
    let mut out = Vec::with_capacity(chunk_count.min(1024));

    for _ in 0..chunk_count {
        let mut x_buf = [0u8; 4];
        let mut z_buf = [0u8; 4];
        let mut t_buf = [0u8; 1];
        reader.read_exact(&mut x_buf)?;
        reader.read_exact(&mut z_buf)?;
        reader.read_exact(&mut t_buf)?;

        let coord = ChunkCoord::new(i32::from_le_bytes(x_buf), i32::from_le_bytes(z_buf));
        let tier = LodTier::from_u8(t_buf[0])
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "invalid LOD tier"))?;
        let mut chunk = LodChunk::new(coord, tier, ChunkSource::DiskCache);

        for col in &mut chunk.columns {
            let mut sc = [0u8; 1];
            reader.read_exact(&mut sc)?;
            let n = (sc[0] as usize).min(MAX_SPANS_PER_COLUMN);
            for _ in 0..n {
                let mut raw = [0u8; 8];
                reader.read_exact(&mut raw)?;
                let span = ColumnSpan {
                    top_y: i16::from_le_bytes([raw[0], raw[1]]),
                    bottom_y: i16::from_le_bytes([raw[2], raw[3]]),
                    block_id: u16::from_le_bytes([raw[4], raw[5]]),
                    biome_id: raw[6],
                    packed_flags: raw[7],
                };
                col.push_span(span);
            }
        }
        chunk.dirty_disk = false;
        chunk.dirty_mesh = true;
        out.push(chunk);
    }

    Ok(out)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CommandAction {
    Reply(String),
    SaveAndReply(String),
    ClearAndReply(String),
}

// Handles `/plod ...` chat commands.
pub fn handle_command(engine: &mut LodEngine, args: &str) -> CommandAction {
    let parts: Vec<&str> = args.split_whitespace().collect();
    if parts.is_empty() || parts[0].eq_ignore_ascii_case("status") {
        let st = engine.stats();
        let (fog_start, fog_end) = engine.config.fog_range_blocks();
        return CommandAction::Reply(format!(
            "[PotatoHorizon v{}] {} | real={}ch -> LOD={}ch (Near<= {}ch) | preset={} | chunks: {} near, {} far | meshes: {} ({} quads / {} tris) | RAM ~{} KB | fog {:.0}-{:.0}m",
            VERSION,
            if engine.config.enabled { "ON" } else { "OFF" },
            engine.config.real_distance,
            engine.config.lod_distance,
            engine.config.near_lod_radius(),
            engine.config.preset.label(),
            st.near_chunks,
            st.far_chunks,
            st.meshes_ready,
            st.total_quads,
            st.total_triangles,
            st.approx_ram_kb,
            fog_start,
            fog_end,
        ));
    }

    match parts[0].to_ascii_lowercase().as_str() {
        "on" | "enable" => {
            engine.config.enabled = true;
            engine.ring_dirty = true;
            CommandAction::Reply("[PotatoHorizon] Enabled.".to_string())
        }
        "off" | "disable" => {
            engine.config.enabled = false;
            CommandAction::Reply("[PotatoHorizon] Disabled.".to_string())
        }
        "distance" | "dist" | "lod" => {
            let Some(v) = parts.get(1).and_then(|s| s.parse::<u8>().ok()) else {
                return CommandAction::Reply("Usage: /plod distance <8..32>".to_string());
            };
            engine.config.lod_distance = v;
            engine.config.clamp();
            engine.ring_dirty = true;
            CommandAction::Reply(format!(
                "[PotatoHorizon] LOD horizon set to {} chunks (real cutoff: {} chunks).",
                engine.config.lod_distance, engine.config.real_distance
            ))
        }
        "real" | "cutoff" => {
            let Some(v) = parts.get(1).and_then(|s| s.parse::<u8>().ok()) else {
                return CommandAction::Reply("Usage: /plod real <2..12>".to_string());
            };
            engine.config.real_distance = v;
            engine.config.clamp();
            engine.ring_dirty = true;
            CommandAction::Reply(format!(
                "[PotatoHorizon] Real chunk cutoff set to {} chunks (LOD horizon: {} chunks).",
                engine.config.real_distance, engine.config.lod_distance
            ))
        }
        "preset" | "budget" => {
            let Some(p) = parts.get(1).and_then(|s| PotatoPreset::parse(s)) else {
                return CommandAction::Reply(
                    "Usage: /plod preset <ultra|potato|balanced>".to_string(),
                );
            };
            engine.config.preset = p;
            engine.ring_dirty = true;
            CommandAction::Reply(format!(
                "[PotatoHorizon] Preset set to {} ({} meshes/frame, {} us budget).",
                p.label(),
                p.max_meshes_per_frame(),
                p.frame_budget_us()
            ))
        }
        "caves" => {
            let Some(flag) = parts.get(1) else {
                return CommandAction::Reply("Usage: /plod caves <on|off>".to_string());
            };
            match flag.to_ascii_lowercase().as_str() {
                "on" | "true" => {
                    engine.config.cave_culling = true;
                    CommandAction::Reply("[PotatoHorizon] Cave culling ON.".to_string())
                }
                "off" | "false" => {
                    engine.config.cave_culling = false;
                    CommandAction::Reply("[PotatoHorizon] Cave culling OFF.".to_string())
                }
                _ => CommandAction::Reply("Usage: /plod caves <on|off>".to_string()),
            }
        }
        "save" => CommandAction::SaveAndReply("[PotatoHorizon] Saving dirty LOD regions...".to_string()),
        "clear" => {
            engine.clear_all();
            CommandAction::ClearAndReply("[PotatoHorizon] Cleared LOD cache; rebuilding ring.".to_string())
        }
        _ => CommandAction::Reply(
            "Commands: /plod [status|on|off|distance <8..32>|real <2..12>|preset <ultra|potato|balanced>|caves <on|off>|save|clear]".to_string(),
        ),
    }
}

// Settings UI helper for Horizon's Options > Mods > PotatoHorizon page.
pub fn settings_button_labels(cfg: &Config) -> Vec<String> {
    vec![
        format!("PotatoHorizon: {}", if cfg.enabled { "ON" } else { "OFF" }),
        format!("Real Chunks Cutoff: {}", cfg.real_distance),
        format!("LOD Horizon Distance: {} chunks", cfg.lod_distance),
        format!("PC Budget Preset: {}", cfg.preset.label()),
        format!(
            "Cave Culling: {}",
            if cfg.cave_culling { "ON" } else { "OFF" }
        ),
    ]
}

pub fn handle_settings_click(cfg: &mut Config, button_index: usize) -> bool {
    match button_index {
        0 => cfg.enabled = !cfg.enabled,
        1 => {
            cfg.real_distance = match cfg.real_distance {
                4 => 6,
                6 => 8,
                8 => 10,
                _ => 4,
            };
        }
        2 => {
            cfg.lod_distance = match cfg.lod_distance {
                12 => 16,
                16 => 20,
                20 => 24,
                _ => 12,
            };
        }
        3 => cfg.preset = cfg.preset.next(),
        4 => cfg.cave_culling = !cfg.cave_culling,
        _ => return false,
    }
    cfg.clamp();
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    struct FlatSampler {
        height: i16,
        block_id: u16,
    }

    impl SurfaceSampler for FlatSampler {
        fn sample_lod_chunk(
            &mut self,
            coord: ChunkCoord,
            tier: LodTier,
            _cfg: &Config,
        ) -> Option<LodChunk> {
            let mut chunk = LodChunk::new(coord, tier, ChunkSource::HeightmapSample);
            let span = ColumnSpan::new(self.height, self.height - 4, self.block_id, 1, 15, false, false, false);
            let mut col = LodColumn::empty();
            col.push_span(span);
            for slot in &mut chunk.columns {
                *slot = col;
            }
            Some(chunk)
        }
    }

    #[test]
    fn default_config_targets_6_to_16_chunks() {
        let cfg = Config::default();
        assert!(cfg.enabled);
        assert_eq!(cfg.real_distance, 6);
        assert_eq!(cfg.lod_distance, 16);
        assert_eq!(cfg.near_lod_radius(), 11);
        assert_eq!(cfg.desired_tier(36), None); // d = 6 (real chunk)
        assert_eq!(cfg.desired_tier(49), Some(LodTier::Near2x2)); // d = 7
        assert_eq!(cfg.desired_tier(144), Some(LodTier::Far4x4)); // d = 12
        assert_eq!(cfg.desired_tier(256), Some(LodTier::Far4x4)); // d = 16
        assert_eq!(cfg.desired_tier(289), None); // d = 17 (outside)
    }

    #[test]
    fn greedy_mesher_merges_flat_chunk_to_single_top_quad() {
        let mut chunk = LodChunk::new(ChunkCoord::new(8, 0), LodTier::Near2x2, ChunkSource::VisitedChunk);
        let span = ColumnSpan::new(64, 60, 2, 1, 15, false, false, false);
        let mut col = LodColumn::empty();
        col.push_span(span);
        for slot in &mut chunk.columns {
            *slot = col;
        }
        let mut scratch = MeshScratch::default();
        let mut mesh = LodMesh::default();
        build_chunk_mesh(&chunk, [64, 64, 64, 64], &mut scratch, &mut mesh);
        assert_eq!(mesh.quads.len(), 1);
        assert_eq!(mesh.quads[0].face, QuadFace::Top);
        assert_eq!(mesh.quads[0].x1 - mesh.quads[0].x0, 16);
        assert_eq!(mesh.quads[0].z1 - mesh.quads[0].z0, 16);
    }

    #[test]
    fn cliff_emits_side_skirts() {
        let mut chunk = LodChunk::new(ChunkCoord::new(8, 0), LodTier::Far4x4, ChunkSource::VisitedChunk);
        let low = ColumnSpan::new(64, 60, 2, 1, 15, false, false, false);
        let high = ColumnSpan::new(72, 60, 1, 1, 15, false, false, false);
        let mut low_col = LodColumn::empty();
        low_col.push_span(low);
        let mut high_col = LodColumn::empty();
        high_col.push_span(high);

        for gz in 0..4 {
            for gx in 0..4 {
                chunk.set(gx, gz, if gx < 2 { high_col } else { low_col });
            }
        }
        let mut scratch = MeshScratch::default();
        let mut mesh = LodMesh::default();
        build_chunk_mesh(&chunk, [72, 72, 72, 72], &mut scratch, &mut mesh);

        let top_quads = mesh.quads.iter().filter(|q| q.face == QuadFace::Top).count();
        let east_quads = mesh.quads.iter().filter(|q| q.face == QuadFace::East).count();
        assert_eq!(top_quads, 2);
        assert_eq!(east_quads, 4);
    }

    #[test]
    fn scheduler_respects_potato_tick_and_frame_budgets() {
        let mut engine = LodEngine::new(Config::default());
        let mut sampler = FlatSampler { height: 64, block_id: 2 };

        let sampled = engine.step_tick(&mut sampler);
        assert_eq!(sampled, PotatoPreset::Potato.max_samples_per_tick());

        let meshed = engine.step_frame();
        assert_eq!(meshed, PotatoPreset::Potato.max_meshes_per_frame());
        assert_eq!(engine.stats().meshes_ready, meshed);
    }

    #[test]
    fn plod_binary_roundtrip_preserves_chunks() {
        let mut chunk = LodChunk::new(ChunkCoord::new(10, -5), LodTier::Near2x2, ChunkSource::VisitedChunk);
        let mut col = LodColumn::empty();
        col.push_span(ColumnSpan::new(63, 58, 9, 3, 15, true, false, false));
        chunk.set(3, 4, col);

        let mut buf = Vec::new();
        encode_region_plod(&mut buf, 0xCAFE_BABE_1234_5678, &[&chunk]).unwrap();

        let mut cursor = Cursor::new(buf);
        let decoded = decode_region_plod(&mut cursor, 0xCAFE_BABE_1234_5678).unwrap();
        assert_eq!(decoded.len(), 1);
        assert_eq!(decoded[0].coord, chunk.coord);
        assert_eq!(decoded[0].tier, LodTier::Near2x2);
        assert_eq!(decoded[0].get(3, 4), chunk.get(3, 4));
    }

    #[test]
    fn config_kv_and_commands_work() {
        let mut engine = LodEngine::new(Config::default());
        let res = handle_command(&mut engine, "distance 20");
        assert!(matches!(res, CommandAction::Reply(_)));
        assert_eq!(engine.config.lod_distance, 20);

        let serialized = engine.config.to_kv_string();
        let restored = Config::from_kv_string(&serialized);
        assert_eq!(restored.lod_distance, 20);
        assert_eq!(restored.real_distance, 6);
    }
}

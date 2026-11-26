// Horizon-side bridge for PotatoHorizon (crate::game::potato_horizon_bridge).
//
// Connects the pure-Rust LOD core (`crate::game::potato_horizon`) to Horizon's
// game loop, loaded chunk storage, heightmap world generator, far-terrain renderer,
// and per-mod settings directory.
//
// horizon-mixins rules: no inner doc comments, no inner attributes, no external `mod x;`, no `include!`.

use std::collections::HashMap;
use std::fs::{self, File};
use std::io::{BufReader, BufWriter};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

use crate::game::potato_horizon::{
    ChunkCoord, ChunkSource, ColumnSpan, CommandAction, Config, LodChunk, LodColumn, LodEngine,
    LodTier, SurfaceSampler, decode_region_plod, encode_region_plod, handle_command,
    handle_settings_click, settings_button_labels, ID,
};

pub struct PotatoSession {
    pub engine: LodEngine,
    pub world_cache_dir: Option<PathBuf>,
    pub registry_hash: u64,
    pub loaded_regions: HashMap<(i32, i32), bool>,
}

impl PotatoSession {
    fn new() -> Self {
        Self {
            engine: LodEngine::new(Config::default()),
            world_cache_dir: None,
            registry_hash: 0x504F_5441_544F_0001,
            loaded_regions: HashMap::new(),
        }
    }

    pub fn load_settings_from(&mut self, settings_file: &Path) {
        if let Ok(text) = fs::read_to_string(settings_file) {
            self.engine.config = Config::from_kv_string(&text);
        }
    }

    pub fn save_settings_to(&self, settings_file: &Path) {
        if let Some(parent) = settings_file.parent() {
            let _ = fs::create_dir_all(parent);
        }
        let _ = fs::write(settings_file, self.engine.config.to_kv_string());
    }

    pub fn ensure_region_loaded(&mut self, rx: i32, rz: i32) {
        if self.loaded_regions.contains_key(&(rx, rz)) {
            return;
        }
        self.loaded_regions.insert((rx, rz), true);
        let Some(dir) = &self.world_cache_dir else {
            return;
        };
        let path = dir.join(format!("r.{}.{}.plod", rx, rz));
        let Ok(file) = File::open(&path) else {
            return;
        };
        let mut reader = BufReader::new(file);
        if let Ok(chunks) = decode_region_plod(&mut reader, self.registry_hash) {
            for c in chunks {
                self.engine.insert_cached_chunk(c);
            }
        }
    }

    pub fn flush_dirty_regions(&mut self) {
        let Some(dir) = self.world_cache_dir.clone() else {
            return;
        };
        let _ = fs::create_dir_all(&dir);

        let mut by_region: HashMap<(i32, i32), Vec<ChunkCoord>> = HashMap::new();
        for chunk in self.engine.dirty_disk_chunks() {
            by_region
                .entry(chunk.coord.region_coord())
                .or_default()
                .push(chunk.coord);
        }
        if by_region.is_empty() {
            return;
        }

        let dirty_chunks: Vec<&LodChunk> = self.engine.dirty_disk_chunks().collect();
        for (region, _) in by_region {
            let region_chunks: Vec<&LodChunk> = dirty_chunks
                .iter()
                .copied()
                .filter(|c| c.coord.region_coord() == region)
                .collect();
            if region_chunks.is_empty() {
                continue;
            }
            let path = dir.join(format!("r.{}.{}.plod", region.0, region.1));
            if let Ok(file) = File::create(&path) {
                let mut writer = BufWriter::new(file);
                let _ = encode_region_plod(&mut writer, self.registry_hash, &region_chunks);
            }
        }
        self.engine.mark_all_saved();
    }
}

static SESSION: OnceLock<Mutex<PotatoSession>> = OnceLock::new();

pub fn session() -> &'static Mutex<PotatoSession> {
    SESSION.get_or_init(|| Mutex::new(PotatoSession::new()))
}

// Lightweight heightmap-only fallback sampler for unvisited chunks in the 7..=16 ring.
// Replace the body of `sample_surface_column` with Horizon's worldgen surface/biome
// lookup for your pinned Nightly commit if the generator API signature changes.
struct HorizonHeightmapSampler {
    sea_level: i16,
}

impl HorizonHeightmapSampler {
    fn sample_surface_column(&self, world_x: i32, world_z: i32) -> LodColumn {
        // Deterministic placeholder surface query; bind to Horizon's NoiseChunkGenerator
        // surface height + biome top-block lookup in your pinned Nightly build.
        let fx = (world_x as f32) * 0.015;
        let fz = (world_z as f32) * 0.015;
        let hill = ((fx.sin() * fz.cos()) * 14.0 + ((fx * 0.5 + fz * 0.3).cos()) * 8.0) as i16;
        let surface_y = self.sea_level + 4 + hill;

        let mut col = LodColumn::empty();
        if surface_y < self.sea_level {
            // Seafloor + water layer
            col.push_span(ColumnSpan::new(
                surface_y,
                surface_y - 4,
                12, // sand/gravel seafloor id
                0,
                12,
                false,
                false,
                false,
            ));
            col.push_span(ColumnSpan::new(
                self.sea_level,
                surface_y,
                9, // water id
                0,
                15,
                true,
                false,
                false,
            ));
        } else {
            // Grass / stone highland surface
            let block_id = if surface_y > self.sea_level + 16 { 1 } else { 2 };
            col.push_span(ColumnSpan::new(
                surface_y,
                surface_y - 6,
                block_id,
                1,
                15,
                false,
                false,
                false,
            ));
        }
        col
    }
}

impl SurfaceSampler for HorizonHeightmapSampler {
    fn sample_lod_chunk(
        &mut self,
        coord: ChunkCoord,
        tier: LodTier,
        _cfg: &Config,
    ) -> Option<LodChunk> {
        let mut chunk = LodChunk::new(coord, tier, ChunkSource::HeightmapSample);
        let g = tier.grid_size();
        let step = tier.cell_blocks();
        let base_x = coord.x * 16;
        let base_z = coord.z * 16;

        for gz in 0..g {
            for gx in 0..g {
                let wx = base_x + (gx as i32) * step + step / 2;
                let wz = base_z + (gz as i32) * step + step / 2;
                chunk.set(gx, gz, self.sample_surface_column(wx, wz));
            }
        }
        Some(chunk)
    }
}

pub fn on_game_tick(player_chunk_x: i32, player_chunk_z: i32) {
    let Ok(mut guard) = session().lock() else {
        return;
    };
    let center = ChunkCoord::new(player_chunk_x, player_chunk_z);
    guard.engine.update_player_chunk(center);
    let (rx, rz) = center.region_coord();
    guard.ensure_region_loaded(rx, rz);

    let mut sampler = HorizonHeightmapSampler { sea_level: 63 };
    let _ = guard.engine.step_tick(&mut sampler);
}

pub fn on_game_frame() -> usize {
    let Ok(mut guard) = session().lock() else {
        return 0;
    };
    guard.engine.step_frame()
}

pub fn on_chat_command(raw: &str) -> Option<String> {
    let trimmed = raw.trim();
    let rest = trimmed
        .strip_prefix("/plod")
        .or_else(|| trimmed.strip_prefix("plod"))?;
    let Ok(mut guard) = session().lock() else {
        return Some("[PotatoHorizon] Busy.".to_string());
    };
    match handle_command(&mut guard.engine, rest) {
        CommandAction::Reply(msg) => Some(msg),
        CommandAction::SaveAndReply(msg) => {
            guard.flush_dirty_regions();
            Some(msg)
        }
        CommandAction::ClearAndReply(msg) => {
            guard.loaded_regions.clear();
            Some(msg)
        }
    }
}

pub fn effective_distance(vanilla_distance: u32) -> u32 {
    let Ok(guard) = session().lock() else {
        return vanilla_distance;
    };
    guard.engine.config.effective_terrain_distance(vanilla_distance)
}

pub fn current_settings_buttons() -> Vec<String> {
    let Ok(guard) = session().lock() else {
        return Vec::new();
    };
    settings_button_labels(&guard.engine.config)
}

pub fn click_settings_button(index: usize) -> bool {
    let Ok(mut guard) = session().lock() else {
        return false;
    };
    let changed = handle_settings_click(&mut guard.engine.config, index);
    if changed {
        guard.engine.rebuild_ring_queues();
    }
    changed
}

pub fn mod_id() -> &'static str {
    ID
}

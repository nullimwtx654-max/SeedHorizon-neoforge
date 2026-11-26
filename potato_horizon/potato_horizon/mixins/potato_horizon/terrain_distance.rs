// RETURN hook on Game::terrain_distance.
// Extends Horizon's far-plane and distance fog smoothly out to the 16-chunk LOD horizon
// while your real chunk simulation stays at 6 chunks.

pub fn on_terrain_distance(game: &crate::game::Game<'_>, ret: &mut u32) {
    if !game.native_on(crate::game::potato_horizon::ID) {
        return;
    }
    *ret = crate::game::potato_horizon_bridge::effective_distance(*ret);
}

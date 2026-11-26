// HEAD hook on Horizon's `tick` hook point (native_hooks::Game::native_tick).

pub fn on_tick(game: &mut crate::game::Game<'_>) {
    if !game.native_on(crate::game::potato_horizon::ID) {
        return;
    }
    let pos = game.native_player_pos();
    let chunk_x = (pos[0].floor() as i32).div_euclid(16);
    let chunk_z = (pos[2].floor() as i32).div_euclid(16);
    crate::game::potato_horizon_bridge::on_game_tick(chunk_x, chunk_z);
}

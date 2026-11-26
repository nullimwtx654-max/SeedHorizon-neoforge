// HEAD hook on Horizon's `frame` hook point (native_hooks::Game::native_frame).

pub fn on_frame(game: &mut crate::game::Game<'_>) {
    if !game.native_on(crate::game::potato_horizon::ID) {
        return;
    }
    let _built = crate::game::potato_horizon_bridge::on_game_frame();
}

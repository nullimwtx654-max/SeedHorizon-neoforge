// HEAD hook on Horizon's `mods` hook point (native_hooks::Game::native_mods).
// Registers PotatoHorizon in Options > Mods with a Settings page.

pub fn on_mods(game: &mut crate::game::Game<'_>) {
    game.native_register_mod(
        crate::game::potato_horizon::ID,
        crate::game::potato_horizon::DISPLAY_NAME,
        crate::game::potato_horizon::VERSION,
        true,
    );
}

// HEAD hook on Horizon's `settings_click` hook point (native_hooks::Game::native_settings_click).

pub fn on_settings_click(game: &mut crate::game::Game<'_>, mod_id: &str, button_index: usize) {
    if mod_id != crate::game::potato_horizon::ID {
        return;
    }
    let _ = crate::game::potato_horizon_bridge::click_settings_button(button_index);
    let _ = game;
}

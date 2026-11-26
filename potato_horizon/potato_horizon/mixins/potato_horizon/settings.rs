// HEAD hook on Horizon's `settings` hook point (native_hooks::Game::native_settings).

pub fn on_settings(game: &mut crate::game::Game<'_>, mod_id: &str, buttons: &mut Vec<String>) {
    if mod_id != crate::game::potato_horizon::ID {
        return;
    }
    let labels = crate::game::potato_horizon_bridge::current_settings_buttons();
    buttons.extend(labels);
}

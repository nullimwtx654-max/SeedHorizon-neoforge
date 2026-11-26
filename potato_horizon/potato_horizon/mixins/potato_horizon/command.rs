// HEAD hook on Horizon's `command` hook point (native_hooks::Game::native_command).

pub fn on_command(game: &mut crate::game::Game<'_>, raw: &str, claimed: &mut bool) {
    if *claimed || !game.native_on(crate::game::potato_horizon::ID) {
        return;
    }
    if let Some(reply) = crate::game::potato_horizon_bridge::on_chat_command(raw) {
        game.native_chat_message(&reply);
        *claimed = true;
    }
}
